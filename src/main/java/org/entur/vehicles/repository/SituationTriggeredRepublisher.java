package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

    private final int chunkSize;
    private final Duration chunkDelay;

    // Refs accumulate here while the worker is busy; the worker takes the whole set at once,
    // so a burst of situation changes costs one scan. A set has no capacity and therefore no
    // overflow rule to get wrong - see the plan's note on why a bounded queue was rejected.
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Semaphore signal = new Semaphore(0);

    private final AtomicLong scanCount = new AtomicLong();
    private final AtomicLong republishedCount = new AtomicLong();
    private final AtomicLong chunkCount = new AtomicLong();

    private volatile Thread worker;
    private volatile boolean running;

    public SituationTriggeredRepublisher(
            @Autowired AutoPurgingTimetableMap timetableMap,
            @Autowired EstimatedTimetableUpdateRxPublisher etPublisher,
            @Value("${vehicle.sx.republish.chunk.size:100}") int chunkSize,
            @Value("${vehicle.sx.republish.chunk.delay:PT0.05S}") Duration chunkDelay) {
        this.timetableMap = timetableMap;
        this.etPublisher = etPublisher;
        this.chunkSize = chunkSize;
        this.chunkDelay = chunkDelay;
    }

    @PostConstruct
    public void start() {
        running = true;
        worker = new Thread(this::run, "situation-republisher");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
    }

    /**
     * Hands the change off to the worker and returns. The SX Pub/Sub executor threads must never
     * wait on a scan, and a republishing failure must never break SX ingest - a situation that
     * fails to trigger a republish is still stored and still reaches the situations subscription.
     */
    public void onSituationChanged(SituationUpdate previous, SituationUpdate current) {
        try {
            Set<String> refs = triggerRefs(previous, current);
            if (refs.isEmpty()) {
                return;
            }
            pending.addAll(refs);
            signal.release();
        } catch (RuntimeException e) {
            LOG.warn("Situation-triggered republish not scheduled.", e);
        }
    }

    private void run() {
        while (running) {
            try {
                signal.acquire();
                signal.drainPermits();
                Set<String> refs = takePending();
                if (!refs.isEmpty()) {
                    republishNow(refs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // The worker must never die: a dead worker silently stops all republishing
                // while the rest of the service looks healthy.
                LOG.warn("Situation-triggered republish failed.", e);
            }
        }
    }

    /**
     * Takes everything accumulated since the last call, leaving the pending set empty.
     * <p>
     * Refs added between the copy and the removal stay pending, and the permit released for them
     * survives the {@code drainPermits()} above, so the next loop picks them up. The worst case is
     * one extra pass that finds nothing, which {@link #run()} skips.
     */
    Set<String> takePending() {
        Set<String> taken = new HashSet<>(pending);
        pending.removeAll(taken);
        return taken;
    }

    /**
     * Scans for affected journeys and re-emits them, paced in chunks.
     * <p>
     * Returns immediately when nothing is subscribed. That is not only an optimisation: it makes
     * the 343-situation startup snapshot cost nothing, since no subscriber can exist yet, and it
     * makes the whole mechanism free for deployments that never use timetables subscriptions.
     */
    void republishNow(Set<String> refs) {
        if (etPublisher.currentSubscribers() == 0) {
            return;
        }

        long started = System.currentTimeMillis();
        List<EstimatedTimetableUpdate> affected = findAffected(refs);
        scanCount.incrementAndGet();

        for (int from = 0; from < affected.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, affected.size());
            for (EstimatedTimetableUpdate timetable : affected.subList(from, to)) {
                etPublisher.publishUpdate(timetable);
            }
            republishedCount.addAndGet(to - from);
            chunkCount.incrementAndGet();

            if (to < affected.size()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(chunkDelay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        long duration = System.currentTimeMillis() - started;
        if (duration > 1000) {
            LOG.warn("Situation-triggered republish took {} ms for {} refs and {} journeys - the "
                    + "timetable map may have outgrown a full scan per situation change.",
                    duration, refs.size(), affected.size());
        } else {
            LOG.debug("Republished {} journeys for {} refs in {} ms.", affected.size(), refs.size(), duration);
        }
    }

    public long getScanCount() {
        return scanCount.get();
    }

    public long getRepublishedCount() {
        return republishedCount.get();
    }

    public long getChunkCount() {
        return chunkCount.get();
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
