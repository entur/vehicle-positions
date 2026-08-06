package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public SituationMatcher(Collection<SituationUpdate> situations) {
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
        Map<String, SituationUpdate> matched = new LinkedHashMap<>();

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

        if (calls != null && !matched.isEmpty()) {
            for (Call call : calls) {
                for (SituationUpdate situation : match(call)) {
                    matched.remove(situation.getSituationNumber());
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
     */
    public List<SituationUpdate> match(Call call) {
        if (call.getStopPoint() == null || call.getStopPoint().getId() == null) {
            return List.of();
        }
        Map<String, SituationUpdate> matched = new LinkedHashMap<>();
        collect(byStopRef.get(call.getStopPoint().getId()),
                call.getWindowStart(), call.getWindowEnd(), matched);
        return new ArrayList<>(matched.values());
    }

    private static void collect(List<SituationUpdate> candidates,
                                ZonedDateTime from,
                                ZonedDateTime to,
                                Map<String, SituationUpdate> matched) {
        if (candidates == null) {
            return;
        }
        for (SituationUpdate situation : candidates) {
            if (situation.isValidDuring(from, to)) {
                matched.putIfAbsent(situation.getSituationNumber(), situation);
            }
        }
    }
}
