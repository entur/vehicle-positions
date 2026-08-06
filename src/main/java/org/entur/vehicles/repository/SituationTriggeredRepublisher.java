package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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

    /** Upper bound on {@code chunkDelay}: past this, a misconfigured value stalls the single
     * worker thread for the whole fan-out with no operator-visible signal beyond a slow
     * subscription. */
    private static final Duration MAX_CHUNK_DELAY = Duration.ofSeconds(5);
    private static final Duration DEFAULT_CHUNK_DELAY = Duration.ofMillis(50);

    private final PrometheusMetricsService metricsService;
    private final AutoPurgingTimetableMap timetableMap;
    private final EstimatedTimetableUpdateRxPublisher etPublisher;

    private final AtomicLong skippedCount = new AtomicLong();

    private final int chunkSize;
    private final Duration chunkDelay;
    private final int largeFanoutThreshold;

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
            @Autowired PrometheusMetricsService metricsService,
            @Autowired AutoPurgingTimetableMap timetableMap,
            @Autowired EstimatedTimetableUpdateRxPublisher etPublisher,
            @Value("${vehicle.sx.republish.chunk.size:100}") int chunkSize,
            @Value("${vehicle.sx.republish.chunk.delay:PT0.05S}") Duration chunkDelay,
            @Value("${vehicle.sx.republish.large.fanout.threshold:2000}") int largeFanoutThreshold) {
        this.metricsService = metricsService;
        this.timetableMap = timetableMap;
        this.etPublisher = etPublisher;
        // chunkSize < 1 would never advance `from` in the emission loop in republishNow(),
        // spinning forever while sleeping chunkDelay each pass; negative also throws out of
        // subList(from, to). Both are configuration mistakes, not states worth honouring.
        if (chunkSize < 1) {
            LOG.warn("vehicle.sx.republish.chunk.size={} is not a positive number - falling back to 100.",
                    chunkSize);
            this.chunkSize = 100;
        } else {
            this.chunkSize = chunkSize;
        }
        // A negative chunkDelay throws out of Thread.sleep, and an unreasonably large one (a
        // misconfigured PT1M, say) stalls the single worker thread for minutes per fan-out.
        if (chunkDelay.isNegative() || chunkDelay.compareTo(MAX_CHUNK_DELAY) > 0) {
            LOG.warn("vehicle.sx.republish.chunk.delay={} is out of range (must be between 0 and {}) "
                    + "- falling back to {}.", chunkDelay, MAX_CHUNK_DELAY, DEFAULT_CHUNK_DELAY);
            this.chunkDelay = DEFAULT_CHUNK_DELAY;
        } else {
            this.chunkDelay = chunkDelay;
        }
        this.largeFanoutThreshold = largeFanoutThreshold;
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
     * <p>
     * {@code version} is null on the large majority of real situations, so
     * {@code SituationRepository}'s version guard cannot filter out a redelivery - an
     * at-least-once Pub/Sub redelivery, a producer's periodic full resend of its active set, or a
     * purged-then-resnapshotted situation all reach here. {@link #hasMatchRelevantChange} keeps
     * the hand-off proportional to actual change rather than message volume: a plain redelivery
     * that changed nothing {@code SituationMatcher} reads must not cost a scan and a fan-out.
     */
    public void onSituationChanged(SituationUpdate previous, SituationUpdate current) {
        try {
            if (!hasMatchRelevantChange(previous, current)) {
                return;
            }
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
                // Republishing stops permanently from here on, while the rest of the service
                // keeps looking healthy - this must not be a silent return.
                LOG.warn("Situation-triggered republish worker interrupted - republishing has stopped.");
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
     * A ref added between the copy and the removal is not guaranteed to survive here: if it was
     * already present in {@code taken}, {@code removeAll} deletes it too, and the permit released
     * for it finds an empty pending set on the next pass. That is still safe, but for a different
     * reason than surviving the race: {@code SituationRepository} only calls
     * {@code onSituationChanged} after the situation is already stored, and any scan that runs
     * re-resolves against the timetable map's current state rather than a snapshot from when the
     * ref was queued - so a scan starting after this {@code removeAll} always sees the change that
     * triggered it, regardless of which particular ref made it into {@code taken}.
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
     * <p>
     * Subscriber presence is re-checked at the top of every chunk, not only once at the start: a
     * multi-second fan-out must not keep emitting into a sink whose last subscriber has since
     * left, and the check is free.
     */
    void republishNow(Set<String> refs) {
        if (etPublisher.currentSubscribers() == 0) {
            return;
        }

        // Timed on its own, separate from the paced emission loop below: mixing the two blamed
        // "scan cost" for what was actually chunkDelay sleeps, and warned routinely for any
        // legitimately wide situation rather than for what was actually slow.
        long scanStarted = System.currentTimeMillis();
        List<EstimatedTimetableUpdate> affected = findAffected(refs);
        long scanDuration = System.currentTimeMillis() - scanStarted;
        scanCount.incrementAndGet();
        metricsService.markSituationRepublishScan();

        if (scanDuration > 1000) {
            LOG.warn("Situation-triggered scan took {} ms for {} refs, matching {} candidate "
                    + "journeys - the timetable map may have outgrown a full scan per situation change.",
                    scanDuration, refs.size(), affected.size());
        }

        // Do NOT drop candidates here - silently not republishing is exactly the failure this
        // feature exists to prevent. This only makes a large fan-out visible to an operator;
        // emission below still completes in full.
        if (affected.size() >= largeFanoutThreshold) {
            LOG.warn("Situation-triggered republish for {} refs matched {} candidate journeys, at "
                    + "or above the large fan-out threshold of {} - emitting in chunks over "
                    + "several seconds so it does not crowd out ordinary timetable updates.",
                    refs.size(), affected.size(), largeFanoutThreshold);
        }

        long emissionStarted = System.currentTimeMillis();
        int emitted = 0;
        for (int from = 0; from < affected.size(); from += chunkSize) {
            if (etPublisher.currentSubscribers() == 0) {
                break;
            }
            int to = Math.min(from + chunkSize, affected.size());
            for (EstimatedTimetableUpdate timetable : affected.subList(from, to)) {
                etPublisher.publishUpdate(timetable);
            }
            int chunkEmitted = to - from;
            emitted += chunkEmitted;
            republishedCount.addAndGet(chunkEmitted);
            chunkCount.incrementAndGet();
            metricsService.markSituationRepublishedJourneys(chunkEmitted);
            metricsService.markSituationRepublishChunk();

            if (to < affected.size()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(chunkDelay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        long emissionDuration = System.currentTimeMillis() - emissionStarted;
        LOG.debug("Republished {} of {} candidate journeys for {} refs - {} ms scan, {} ms emission.",
                emitted, affected.size(), refs.size(), scanDuration, emissionDuration);
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
     * True when the transition from {@code previous} to {@code current} could change what
     * {@code SituationMatcher} decides for some journey. A plain redelivery of an unchanged
     * situation - which {@code version} being null on most real situations cannot rule out, see
     * {@link #onSituationChanged} - must not schedule a scan.
     * <p>
     * What the matcher reads is exactly: {@code progress}, the four match-dimension ref sets, and
     * {@code validityPeriods}. Codespace and situationNumber cannot differ - they are the map key
     * a redelivery of the same situation is stored under.
     * <p>
     * {@code previous == null} - a situation's first sighting - always counts as changed: there is
     * nothing to compare against, and it is also not a state a redelivery of an already-stored
     * situation can produce.
     */
    static boolean hasMatchRelevantChange(SituationUpdate previous, SituationUpdate current) {
        if (previous == null) {
            return true;
        }
        return previous.getProgress() != current.getProgress()
                || !matchRefs(previous, Affects::getLineRefs).equals(matchRefs(current, Affects::getLineRefs))
                || !matchRefs(previous, Affects::getStopRefs).equals(matchRefs(current, Affects::getStopRefs))
                || !matchRefs(previous, Affects::getServiceJourneyIds).equals(matchRefs(current, Affects::getServiceJourneyIds))
                || !matchRefs(previous, Affects::getDatedServiceJourneyIds).equals(matchRefs(current, Affects::getDatedServiceJourneyIds))
                || !Objects.equals(previous.getValidityPeriods(), current.getValidityPeriods());
    }

    private static Set<String> matchRefs(SituationUpdate situation, Function<Affects, Set<String>> getter) {
        return situation.getAffects() != null ? getter.apply(situation.getAffects()) : Set.of();
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
            } catch (RuntimeException e) {
                // TimetableRepository.add() mutates a stored update in place, including
                // getCalls().clear(), so a journey being updated right now can throw here - and
                // not only ConcurrentModificationException from the iterator's modCount check.
                // clear() bumps modCount and then nulls elements, so an iterator that already
                // passed checkForComodification() can still read a nulled slot and NPE. addCall()
                // also lazily assigns a non-final, non-volatile calls field, so a concurrently
                // reading thread is not guaranteed to see a fully published list either. Catching
                // broadly is what keeps one journey's race from silently costing every journey
                // still left in this scan - by the time a narrower catch let it escape to run()'s
                // batch-level handler, takePending() had already cleared the refs, so none of them
                // would be retried.
                // Skipping is safe: that journey is mid-update, so an ET event for it is about
                // to be published anyway, carrying the fresh situations with it.
                skippedCount.incrementAndGet();
                metricsService.markSituationRepublishSkipped();
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
