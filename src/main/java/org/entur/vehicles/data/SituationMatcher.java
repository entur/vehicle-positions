package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.AffectedLine;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.Codespace;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Matches situations to estimated timetable data.
 * <p>
 * Built from a snapshot of the situation map and then discarded - it is not a maintained
 * index. Rebuilding per batch is always correct by construction, whereas an index kept in
 * sync with situation replacement and the purge thread could leave a closed situation
 * attached to journeys.
 * <p>
 * Deliberately free of Spring and GraphQL dependencies so the match rule can be tested
 * directly.
 */
public class SituationMatcher {

    private final Map<String, List<SituationUpdate>> byLineRef = new HashMap<>();
    private final Map<String, List<SituationUpdate>> byStopRef = new HashMap<>();
    private final Map<String, List<SituationUpdate>> byServiceJourneyId = new HashMap<>();
    private final Map<String, List<SituationUpdate>> byDatedServiceJourneyId = new HashMap<>();

    /**
     * Stops that only apply to the object they were nested under, keyed by that object's
     * service journey id, dated service journey id or line ref.
     * <p>
     * A journey entry is keyed by its journey refs only, never by its line: the line on an
     * AffectedVehicleJourney is context for display, and keying on it would widen the
     * situation back to every journey on that line - the exact over-matching this index exists
     * to remove. Only an AffectedLine entry is keyed by line.
     */
    private final Map<String, List<ScopedStops>> scopedByObject = new HashMap<>();

    private record ScopedStops(SituationUpdate situation, Set<String> stopRefs) {}

    private final Function<String, Set<String>> ancestorResolver;

    /**
     * Matches on literal stop ids only. Used where no stop hierarchy is available - which is
     * every deployment with {@code vehicle.nsr.lookup.enabled=false}, and every unit test that
     * is not specifically about hierarchy.
     */
    public SituationMatcher(Collection<SituationUpdate> situations) {
        this(situations, ref -> Set.of());
    }

    /**
     * @param ancestorResolver a ref's ancestors, NOT including the ref itself - typically
     *                         {@code NSRService::ancestorsOf}. Passed as a function rather than
     *                         the service so this class stays free of Spring, which is what lets
     *                         the match rule be unit-tested directly.
     */
    public SituationMatcher(Collection<SituationUpdate> situations,
                            Function<String, Set<String>> ancestorResolver) {
        this.ancestorResolver = ancestorResolver;
        for (SituationUpdate situation : situations) {
            if (situation.getProgress() != null && situation.getProgress().isClosed()) {
                continue;
            }
            Affects affects = situation.getAffects();
            if (affects == null) {
                continue;
            }
            index(byLineRef, affects.getLineRefs(), situation);
            index(byStopRef, affects.getStopRefs(), situation);
            index(byServiceJourneyId, affects.getServiceJourneyIds(), situation);
            index(byDatedServiceJourneyId, affects.getDatedServiceJourneyIds(), situation);
            indexScoped(situation, affects);
        }
    }

    private static void index(Map<String, List<SituationUpdate>> target,
                              Set<String> refs,
                              SituationUpdate situation) {
        for (String ref : refs) {
            target.computeIfAbsent(ref, key -> new ArrayList<>()).add(situation);
        }
    }

    /**
     * Allocates nothing per entry: the stop-ref set is derived once at ingest, in the entry's
     * constructor, and merely referenced here. This class is rebuilt for every GraphQL batch -
     * and a situation-triggered fan-out rebuilds it once per republished journey - so building a
     * set per entry here made a situation naming thousands of dated journeys cost megabytes of
     * garbage, thousands of times over. The entries are immutable, so sharing the set is safe.
     */
    private void indexScoped(SituationUpdate situation, Affects affects) {
        for (AffectedVehicleJourney journey : affects.getVehicleJourneys()) {
            Set<String> stopRefs = journey.stopRefs();
            if (stopRefs.isEmpty()) {
                // No stops means the journey is affected as a whole - journey-level matching
                // through the flat id sets already covers it.
                continue;
            }
            ScopedStops scoped = new ScopedStops(situation, stopRefs);
            if (journey.getServiceJourney() != null && journey.getServiceJourney().getId() != null) {
                scopedByObject.computeIfAbsent(journey.getServiceJourney().getId(), key -> new ArrayList<>()).add(scoped);
            }
            if (journey.getDatedServiceJourney() != null && journey.getDatedServiceJourney().getId() != null) {
                scopedByObject.computeIfAbsent(journey.getDatedServiceJourney().getId(), key -> new ArrayList<>()).add(scoped);
            }
        }
        for (AffectedLine affectedLine : affects.getAffectedLines()) {
            Set<String> stopRefs = affectedLine.stopRefs();
            if (stopRefs.isEmpty() || affectedLine.getLine() == null || affectedLine.getLine().getLineRef() == null) {
                continue;
            }
            scopedByObject.computeIfAbsent(affectedLine.getLine().getLineRef(), key -> new ArrayList<>())
                    .add(new ScopedStops(situation, stopRefs));
        }
    }

    /**
     * Situations affecting this journey as a whole: those naming the journey, its dated
     * journey or its line and overlapping the journey's span, deduplicated by situation
     * number.
     * <p>
     * A situation that also matched one of this journey's calls is left out - the stop is
     * the more specific placement, so it is reported there instead. Otherwise a client
     * rendering both fields would show the same disruption twice. Exclusion is driven by
     * what actually matched rather than by what the situation names: if a stop reference
     * had already lapsed by the time the vehicle called there, the stop match never fired
     * and the journey keeps the situation.
     * <p>
     * The consequence is that the two fields partition: a client that wants every
     * disruption affecting a journey must select {@code calls { situations }} as well.
     */
    public List<SituationUpdate> match(EstimatedTimetableUpdate timetable) {
        Map<Identity, SituationUpdate> matched = new LinkedHashMap<>();

        ZonedDateTime spanStart = null;
        ZonedDateTime spanEnd = null;
        List<Call> calls = timetable.getCalls();
        if (calls != null) {
            for (Call call : calls) {
                ZonedDateTime start = call.getWindowStart();
                ZonedDateTime end = call.getWindowEnd();
                if (start != null && (spanStart == null || start.isBefore(spanStart))) {
                    spanStart = start;
                }
                if (end != null && (spanEnd == null || end.isAfter(spanEnd))) {
                    spanEnd = end;
                }
            }
        }

        if (timetable.getLine() != null) {
            collect(byLineRef.get(timetable.getLine().getLineRef()), spanStart, spanEnd, matched);
        }
        if (timetable.getServiceJourney() != null) {
            collect(byServiceJourneyId.get(timetable.getServiceJourney().getId()), spanStart, spanEnd, matched);
        }
        if (timetable.getDatedServiceJourney() != null) {
            collect(byDatedServiceJourneyId.get(timetable.getDatedServiceJourney().getId()),
                    spanStart, spanEnd, matched);
        }

        // Skipping the walk when nothing matched at journey level is a pure shortcut: the loop
        // body only removes, so it cannot change an empty result. Revisit if match(Call) ever
        // gains a side effect.
        //
        // Still safe with scoped matching: a scoped match requires an entry naming this call's
        // journey, dated journey or line, and every such ref is also in the flat id sets that
        // drove the journey-level pass above. So match(Call) cannot return a situation the
        // journey did not already match, and skipping the loop on an empty result loses nothing.
        if (calls != null && !matched.isEmpty()) {
            for (Call call : calls) {
                for (SituationUpdate situation : match(call)) {
                    matched.remove(Identity.of(situation));
                }
            }
        }

        return new ArrayList<>(matched.values());
    }

    /**
     * Situations affecting this call's stop while the vehicle is there. A situation on a
     * quay is only relevant if it is in force when the vehicle actually calls: one ending
     * at 12:00 does not apply to a call at 12:30, even though it may be valid right now.
     * <p>
     * A situation naming several of the journey's stops is reported against every one of
     * them, so a client can mark each affected stop.
     * <p>
     * A stop is also matched by any ancestor above it - its stop place, and any multimodal
     * parent above that - because timetable data references quays while a situation may be
     * tagged on the stop place that owns them. The temporal rule is unchanged: an
     * ancestor-matched situation is still tested against this call's own window.
     */
    public List<SituationUpdate> match(Call call) {
        if (call.getStopPoint() == null || call.getStopPoint().getId() == null) {
            return List.of();
        }
        String stopId = call.getStopPoint().getId();
        Set<String> ancestors = ancestorResolver.apply(stopId);
        Map<Identity, SituationUpdate> matched = new LinkedHashMap<>();
        collect(byStopRef.get(stopId), call.getWindowStart(), call.getWindowEnd(), matched);
        for (String ancestor : ancestors) {
            collect(byStopRef.get(ancestor), call.getWindowStart(), call.getWindowEnd(), matched);
        }
        collectScoped(call, stopId, ancestors, matched);
        return new ArrayList<>(matched.values());
    }

    /**
     * Situations whose stops were nested under this call's own journey, dated journey or line.
     * The temporal rule is the same as for an unscoped stop: the situation still has to be in
     * force while the vehicle is at this call.
     */
    private void collectScoped(Call call,
                               String stopId,
                               Set<String> ancestors,
                               Map<Identity, SituationUpdate> matched) {
        EstimatedTimetableUpdate owner = call.getOwner();
        if (owner == null) {
            return;
        }
        if (owner.getServiceJourney() != null) {
            collectScopedFor(owner.getServiceJourney().getId(), call, stopId, ancestors, matched);
        }
        if (owner.getDatedServiceJourney() != null) {
            collectScopedFor(owner.getDatedServiceJourney().getId(), call, stopId, ancestors, matched);
        }
        if (owner.getLine() != null) {
            collectScopedFor(owner.getLine().getLineRef(), call, stopId, ancestors, matched);
        }
    }

    private void collectScopedFor(String key,
                                  Call call,
                                  String stopId,
                                  Set<String> ancestors,
                                  Map<Identity, SituationUpdate> matched) {
        if (key == null) {
            return;
        }
        List<ScopedStops> candidates = scopedByObject.get(key);
        if (candidates == null) {
            return;
        }
        for (ScopedStops scoped : candidates) {
            if (scoped.stopRefs().contains(stopId) || !Collections.disjoint(scoped.stopRefs(), ancestors)) {
                collect(List.of(scoped.situation()), call.getWindowStart(), call.getWindowEnd(), matched);
            }
        }
    }

    private static void collect(List<SituationUpdate> candidates,
                                ZonedDateTime from,
                                ZonedDateTime to,
                                Map<Identity, SituationUpdate> matched) {
        if (candidates == null) {
            return;
        }
        for (SituationUpdate situation : candidates) {
            if (situation.isValidDuring(from, to)) {
                matched.putIfAbsent(Identity.of(situation), situation);
            }
        }
    }

    /**
     * What identifies a situation, for deduplicating the journey's matches and for excluding
     * the ones already reported against a call. {@code situationNumber} alone is not enough -
     * {@code SituationRepository} keys by codespace and number together, so two codespaces
     * publishing the same number are different situations and must not displace each other.
     */
    private record Identity(Codespace codespace, String situationNumber) {

        static Identity of(SituationUpdate situation) {
            return new Identity(situation.getCodespace(), situation.getSituationNumber());
        }
    }
}
