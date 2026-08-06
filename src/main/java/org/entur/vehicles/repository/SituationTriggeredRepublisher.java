package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Republishes stored estimated timetables when a situation affecting them changes.
 * <p>
 * The {@code timetables} subscription is fed only by {@link EstimatedTimetableUpdateRxPublisher},
 * and the {@code situations} field on an estimated timetable is resolved once per emitted event.
 * Without this, a situation that appears, changes or closes stays invisible to a subscriber until
 * that journey's producer happens to send another ET message - which for a quiet producer may be
 * never, and which for a closing situation means the disruption lingers on the client's display.
 */
@Component
public class SituationTriggeredRepublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SituationTriggeredRepublisher.class);

    private final AutoPurgingTimetableMap timetableMap;
    private final EstimatedTimetableUpdateRxPublisher etPublisher;

    private final AtomicLong skippedCount = new AtomicLong();

    public SituationTriggeredRepublisher(@Autowired AutoPurgingTimetableMap timetableMap,
                                         @Autowired EstimatedTimetableUpdateRxPublisher etPublisher) {
        this.timetableMap = timetableMap;
        this.etPublisher = etPublisher;
    }

    /**
     * The identifier refs a situation change should trigger on: everything either version names.
     * <p>
     * The previous version is required for two cases where the new one alone is not enough. When a
     * situation closes, the matcher excludes it, so matching the new state finds no journeys at all -
     * exactly the case that most needs to reach the client. When a situation narrows, say from one
     * stop to another, the new state no longer names the stop whose journeys must be told.
     * <p>
     * Operator refs are deliberately absent: {@code SituationMatcher} does not match on operator,
     * and triggering on it would republish journeys whose situation list cannot have changed.
     */
    static Set<String> triggerRefs(SituationUpdate previous, SituationUpdate current) {
        Set<String> refs = new HashSet<>();
        addRefs(refs, previous);
        addRefs(refs, current);
        return refs;
    }

    private static void addRefs(Set<String> refs, SituationUpdate situation) {
        if (situation == null || situation.getAffects() == null) {
            return;
        }
        Affects affects = situation.getAffects();
        refs.addAll(affects.getLineRefs());
        refs.addAll(affects.getStopRefs());
        refs.addAll(affects.getServiceJourneyIds());
        refs.addAll(affects.getDatedServiceJourneyIds());
    }

    /**
     * Stored journeys touching any of these refs.
     * <p>
     * Deliberately looser than the read-path match rule: validity windows and {@code progress} are
     * ignored, so this asks only whether the situation names something the journey touches. Some
     * journeys are therefore republished whose situation list did not actually change. That is the
     * safe direction - a redundant republish carries data the client already has and is applied
     * idempotently, whereas a missed one leaves a disruption on screen after it has ended. Computing
     * an exact before/after diff would need per-subscription state this service does not keep.
     */
    List<EstimatedTimetableUpdate> findAffected(Set<String> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        List<EstimatedTimetableUpdate> affected = new ArrayList<>();
        // ConcurrentHashMap iteration is weakly consistent, so this is safe while ingest writes.
        for (EstimatedTimetableUpdate timetable : timetableMap.values()) {
            try {
                if (isAffected(timetable, refs)) {
                    affected.add(timetable);
                }
            } catch (ConcurrentModificationException e) {
                // TimetableRepository.add() mutates a stored update in place, including
                // getCalls().clear(), so a journey being updated right now can throw here.
                // Skipping is safe: that journey is mid-update, so an ET event for it is about
                // to be published anyway, carrying the fresh situations with it.
                skippedCount.incrementAndGet();
            }
        }
        return affected;
    }

    private static boolean isAffected(EstimatedTimetableUpdate timetable, Set<String> refs) {
        if (timetable.getLine() != null && refs.contains(timetable.getLine().getLineRef())) {
            return true;
        }
        if (timetable.getServiceJourney() != null && refs.contains(timetable.getServiceJourney().getId())) {
            return true;
        }
        if (timetable.getDatedServiceJourney() != null
                && refs.contains(timetable.getDatedServiceJourney().getId())) {
            return true;
        }
        List<Call> calls = timetable.getCalls();
        if (calls != null) {
            for (Call call : calls) {
                if (call.getStopPoint() != null && refs.contains(call.getStopPoint().getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    public long getSkippedCount() {
        return skippedCount.get();
    }
}
