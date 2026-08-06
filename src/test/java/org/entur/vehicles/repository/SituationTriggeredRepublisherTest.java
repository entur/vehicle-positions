package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class SituationTriggeredRepublisherTest {

    /** Comfortably above anything these tests store, so it never spuriously triggers the WARN. */
    private static final int DEFAULT_THRESHOLD = 2000;

    private AutoPurgingTimetableMap timetableMap;
    private PrometheusMeterRegistry registry;
    private PrometheusMetricsService metricsService;
    private SituationTriggeredRepublisher republisher;

    @BeforeEach
    public void setUp() {
        timetableMap = new AutoPurgingTimetableMap(Duration.parse("PT1M"), Duration.parse("PT10M"));
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metricsService = new PrometheusMetricsService(registry);
        republisher = new SituationTriggeredRepublisher(metricsService, timetableMap,
                new EstimatedTimetableUpdateRxPublisher(), 100, Duration.ofMillis(1), DEFAULT_THRESHOLD);
    }

    /** A filter that matches everything, with the small buffer the publisher requires. */
    private QueryFilter matchAll() {
        return new QueryFilter(
                null, MetricType.SUBSCRIPTION,
                null, null, null, null, null, null, null, null, null, null, null, null,
                1, 1);
    }

    private SituationUpdate situation() {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber("TST:SituationNumber:1");
        situation.setCodespace(Codespace.getCodespace("TST"));
        situation.setAffects(new Affects());
        return situation;
    }

    /**
     * Stores a journey under a key built the way TimetableRepository builds one. The two-arg
     * DatedServiceJourney constructor is required: AbstractUpdate.getServiceJourney()
     * delegates through datedServiceJourney, so a one-arg fixture would leave the
     * service-journey dimension permanently null.
     */
    private EstimatedTimetableUpdate storeJourney(String lineRef,
                                                  String serviceJourneyId,
                                                  String datedServiceJourneyId,
                                                  String... stopRefs) {
        EstimatedTimetableUpdate timetable = new EstimatedTimetableUpdate();
        timetable.setCodespace(Codespace.getCodespace("TST"));
        timetable.setLine(new Line(lineRef));
        timetable.setDatedServiceJourney(new DatedServiceJourney(
                datedServiceJourneyId, new ServiceJourney(serviceJourneyId)));
        for (String stopRef : stopRefs) {
            Call call = new Call();
            call.setStopPoint(new StopPoint(stopRef));
            timetable.addCall(call);
        }
        timetableMap.put(
                new StorageKey(Codespace.getCodespace("TST"), null, lineRef, serviceJourneyId, datedServiceJourneyId),
                timetable);
        return timetable;
    }

    @Test
    public void testTriggerRefsUnionsPreviousAndCurrentVersions() {
        SituationUpdate previous = situation();
        previous.getAffects().addStopPoint(new StopPoint("NSR:Quay:A"));

        SituationUpdate current = situation();
        current.getAffects().addStopPoint(new StopPoint("NSR:Quay:B"));

        assertThat(SituationTriggeredRepublisher.triggerRefs(previous, current))
                .withFailMessage("a situation narrowing from A to B must still republish the "
                        + "journeys calling at A, which only the previous version names")
                .containsExactlyInAnyOrder("NSR:Quay:A", "NSR:Quay:B");
    }

    @Test
    public void testTriggerRefsHandlesAFirstTimeSituation() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));

        assertThat(SituationTriggeredRepublisher.triggerRefs(null, current))
                .containsExactly("TST:Line:1");
    }

    @Test
    public void testTriggerRefsCollectsEveryMatchDimensionButNotOperator() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));
        current.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));
        current.getAffects().addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));
        current.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:1"));
        current.getAffects().addOperator(new Operator("TST:Operator:1"));

        assertThat(SituationTriggeredRepublisher.triggerRefs(null, current))
                .withFailMessage("operator is not a match dimension in SituationMatcher, so it "
                        + "must not be a trigger dimension here either")
                .containsExactlyInAnyOrder(
                        "TST:Line:1",
                        "NSR:Quay:1",
                        "TST:ServiceJourney:1",
                        "TST:DatedServiceJourney:1");
    }

    @Test
    public void testFindsAffectedJourneyByLine() {
        EstimatedTimetableUpdate journey =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:Line:1"))).containsExactly(journey);
    }

    @Test
    public void testFindsAffectedJourneyByServiceJourney() {
        EstimatedTimetableUpdate journey =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:ServiceJourney:1"))).containsExactly(journey);
    }

    @Test
    public void testFindsAffectedJourneyByDatedServiceJourney() {
        EstimatedTimetableUpdate journey =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:DatedServiceJourney:1"))).containsExactly(journey);
    }

    @Test
    public void testFindsAffectedJourneyByAnyCalledAtStop() {
        EstimatedTimetableUpdate journey = storeJourney(
                "TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1",
                "NSR:Quay:1", "NSR:Quay:2", "NSR:Quay:3");

        assertThat(republisher.findAffected(Set.of("NSR:Quay:3")))
                .withFailMessage("a stop anywhere in the journey counts, not just the first call")
                .containsExactly(journey);
    }

    @Test
    public void testDoesNotFindAnUnrelatedJourney() {
        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:Line:999", "NSR:Quay:999"))).isEmpty();
    }

    @Test
    public void testEmptyRefSetFindsNothing() {
        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of())).isEmpty();
    }

    @Test
    public void testPendingRefsAccumulateIntoASingleTake() {
        SituationUpdate first = situation();
        first.getAffects().addLine(new Line("TST:Line:1"));

        SituationUpdate second = situation();
        second.getAffects().addStopPoint(new StopPoint("NSR:Quay:2"));

        republisher.onSituationChanged(null, first);
        republisher.onSituationChanged(null, second);

        assertThat(republisher.takePending())
                .withFailMessage("a burst of situation changes must cost one scan, not one each")
                .containsExactlyInAnyOrder("TST:Line:1", "NSR:Quay:2");
        assertThat(republisher.takePending())
                .withFailMessage("taking must clear the pending set, or every later scan would "
                        + "redo all the work of every earlier one")
                .isEmpty();
    }

    @Test
    public void testNoScanHappensWhenNobodyIsSubscribed() {
        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        republisher.republishNow(Set.of("TST:Line:1"));

        assertThat(republisher.getScanCount())
                .withFailMessage("with no timetables subscribers there is nobody to tell, so the "
                        + "scan must not run at all - this is what makes the startup snapshot free")
                .isZero();
        assertThat(republisher.getRepublishedCount()).isZero();
    }

    @Test
    public void testRepublishesAffectedJourneysToSubscribers() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 100, Duration.ofMillis(1), DEFAULT_THRESHOLD);

        EstimatedTimetableUpdate affected =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        storeJourney("TST:Line:2", "TST:ServiceJourney:2", "TST:DatedServiceJourney:2", "NSR:Quay:2");

        List<EstimatedTimetableUpdate> received = new ArrayList<>();
        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(received::addAll);

        try {
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(received).containsExactly(affected);
            assertThat(republisher.getScanCount()).isEqualTo(1);
            assertThat(republisher.getRepublishedCount()).isEqualTo(1);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void testLargeFanOutIsEmittedInChunksRatherThanOneBurst() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 100, Duration.ofMillis(1), DEFAULT_THRESHOLD);

        for (int i = 0; i < 250; i++) {
            storeJourney("TST:Line:1", "TST:ServiceJourney:" + i, "TST:DatedServiceJourney:" + i, "NSR:Quay:1");
        }

        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(republisher.getRepublishedCount()).isEqualTo(250);
            assertThat(republisher.getChunkCount())
                    .withFailMessage("250 journeys at a chunk size of 100 must go out as 3 chunks - "
                            + "emitting them in one tight loop would discard messages for slower "
                            + "subscribers on a directBestEffort sink")
                    .isEqualTo(3);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void testHandOffEventuallyRunsAScan() throws Exception {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 100, Duration.ofMillis(1), DEFAULT_THRESHOLD);
        republisher.start();

        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            SituationUpdate current = situation();
            current.getAffects().addLine(new Line("TST:Line:1"));

            republisher.onSituationChanged(null, current);

            long deadline = System.currentTimeMillis() + 5000;
            while (republisher.getRepublishedCount() == 0 && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(republisher.getRepublishedCount())
                    .withFailMessage("onSituationChanged must hand off to the worker, which must "
                            + "then scan and emit")
                    .isEqualTo(1);
        } finally {
            subscription.dispose();
            republisher.stop();
        }
    }

    /**
     * A configured chunk size of 0 must not be trusted as-is: with {@code chunkSize == 0},
     * {@code from += chunkSize} in the emission loop never advances, so {@code republishNow}
     * would sleep {@code chunkDelay} forever without emitting anything. The 5-second timeout
     * turns that hang into a reported failure instead of stalling the whole build; the
     * constructor is expected to fall back to 100 so this terminates well within it.
     */
    @Test
    @Timeout(5)
    public void testChunkSizeZeroFallsBackAndEmitsEveryCandidateExactlyOnce() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 0, Duration.ofMillis(1), DEFAULT_THRESHOLD);

        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        storeJourney("TST:Line:1", "TST:ServiceJourney:2", "TST:DatedServiceJourney:2", "NSR:Quay:1");

        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(republisher.getRepublishedCount())
                    .withFailMessage("a chunk size of 0 must fall back to a positive default and "
                            + "still emit every matching candidate exactly once")
                    .isEqualTo(2);
            assertThat(republisher.getChunkCount()).isEqualTo(1);
        } finally {
            subscription.dispose();
        }
    }

    /**
     * {@code version} is null on the large majority of real situations, so
     * {@code SituationRepository}'s version guard cannot filter out a redelivery - an
     * at-least-once Pub/Sub redelivery, a producer's periodic full resend, or a
     * purged-then-resnapshotted situation all reach {@code onSituationChanged} with a
     * {@code previous} that is value-equal to {@code current} in everything the matcher
     * reads. None of those must schedule a scan.
     */
    @Test
    public void testIdenticalRedeliveryDoesNotScheduleWork() {
        SituationUpdate previous = situation();
        previous.setProgress(WorkflowStatusEnumeration.published);
        previous.getAffects().addLine(new Line("TST:Line:1"));
        previous.setValidityPeriods(List.of(new ValidityPeriod(null, null)));

        SituationUpdate current = situation();
        current.setProgress(WorkflowStatusEnumeration.published);
        current.getAffects().addLine(new Line("TST:Line:1"));
        current.setValidityPeriods(List.of(new ValidityPeriod(null, null)));

        republisher.onSituationChanged(previous, current);

        assertThat(republisher.takePending())
                .withFailMessage("a redelivery that changed nothing the matcher reads must not "
                        + "schedule a scan - the cost model is per change, not per message")
                .isEmpty();
    }

    @Test
    public void testChangedProgressStillSchedulesWork() {
        SituationUpdate previous = situation();
        previous.setProgress(WorkflowStatusEnumeration.published);
        previous.getAffects().addLine(new Line("TST:Line:1"));

        SituationUpdate current = situation();
        current.setProgress(WorkflowStatusEnumeration.closed);
        current.getAffects().addLine(new Line("TST:Line:1"));

        republisher.onSituationChanged(previous, current);

        assertThat(republisher.takePending())
                .withFailMessage("a progress change must still schedule work, even with identical refs")
                .containsExactly("TST:Line:1");
    }

    @Test
    public void testChangedRefSetStillSchedulesWork() {
        SituationUpdate previous = situation();
        previous.setProgress(WorkflowStatusEnumeration.published);
        previous.getAffects().addStopPoint(new StopPoint("NSR:Quay:A"));

        SituationUpdate current = situation();
        current.setProgress(WorkflowStatusEnumeration.published);
        current.getAffects().addStopPoint(new StopPoint("NSR:Quay:B"));

        republisher.onSituationChanged(previous, current);

        assertThat(republisher.takePending())
                .withFailMessage("a narrowing/widening ref set must still schedule work")
                .containsExactlyInAnyOrder("NSR:Quay:A", "NSR:Quay:B");
    }

    @Test
    public void testChangedValidityPeriodStillSchedulesWork() {
        ZonedDateTime now = ZonedDateTime.now();

        SituationUpdate previous = situation();
        previous.setProgress(WorkflowStatusEnumeration.published);
        previous.getAffects().addLine(new Line("TST:Line:1"));
        previous.setValidityPeriods(List.of(new ValidityPeriod(now, now.plusHours(1))));

        SituationUpdate current = situation();
        current.setProgress(WorkflowStatusEnumeration.published);
        current.getAffects().addLine(new Line("TST:Line:1"));
        current.setValidityPeriods(List.of(new ValidityPeriod(now, now.plusHours(2))));

        republisher.onSituationChanged(previous, current);

        assertThat(republisher.takePending())
                .withFailMessage("an extended/shortened validity period must still schedule work, "
                        + "even with identical progress and refs - this is why ValidityPeriod needs "
                        + "real equals/hashCode rather than falling back to identity")
                .containsExactly("TST:Line:1");
    }

    @Test
    public void testFirstSightingAlwaysSchedulesWork() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));

        republisher.onSituationChanged(null, current);

        assertThat(republisher.takePending())
                .withFailMessage("a situation's first sighting has nothing to compare against and "
                        + "must always schedule work")
                .containsExactly("TST:Line:1");
    }

    /**
     * TimetableRepository.add() mutates a stored update in place (including
     * {@code getCalls().clear()}), so a journey being updated concurrently with a scan can throw
     * more than {@code ConcurrentModificationException} - an NPE from a nulled-but-not-yet-removed
     * slot, for instance. The per-journey guard must catch that broadly, and the failure of one
     * journey must not cost the rest of the scan.
     */
    @Test
    public void testFindAffectedSkipsAThrowingJourneyButReturnsTheRest() {
        // Triggering on the shared stop ref, not the line, matters: isAffected() checks line,
        // then serviceJourney, then datedServiceJourney before it ever reaches getCalls() - a
        // throwing journey matched on one of the earlier dimensions would short-circuit before
        // the throw and this test would prove nothing.
        EstimatedTimetableUpdate before =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        EstimatedTimetableUpdate throwing = new EstimatedTimetableUpdate() {
            @Override
            public List<Call> getCalls() {
                throw new NullPointerException("simulating a mid-update read race");
            }
        };
        throwing.setCodespace(Codespace.getCodespace("TST"));
        throwing.setLine(new Line("TST:Line:999"));
        throwing.setDatedServiceJourney(new DatedServiceJourney(
                "TST:DatedServiceJourney:throwing", new ServiceJourney("TST:ServiceJourney:throwing")));
        timetableMap.put(
                new StorageKey(Codespace.getCodespace("TST"), null, "TST:Line:999",
                        "TST:ServiceJourney:throwing", "TST:DatedServiceJourney:throwing"),
                throwing);
        EstimatedTimetableUpdate after =
                storeJourney("TST:Line:1", "TST:ServiceJourney:2", "TST:DatedServiceJourney:2", "NSR:Quay:1");

        List<EstimatedTimetableUpdate> affected = republisher.findAffected(Set.of("NSR:Quay:1"));

        assertThat(affected)
                .withFailMessage("the throwing journey must be skipped, not abort the whole scan - "
                        + "the journeys stored before and after it must both still come back")
                .containsExactlyInAnyOrder(before, after);
        assertThat(republisher.getSkippedCount()).isEqualTo(1);
        assertThat(registry.find("app.vehicles.situation.republish.skipped").counter().count())
                .withFailMessage("the skip must also be visible on PrometheusMetricsService, not "
                        + "only on the in-process counter")
                .isEqualTo(1.0);
    }

    @Test
    public void testChunkDelayNegativeFallsBackToDefault() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 100, Duration.ofSeconds(-1), DEFAULT_THRESHOLD);

        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            // A negative delay would throw out of Thread.sleep - reaching this line at all,
            // with the single candidate emitted, is the proof the fallback engaged.
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(republisher.getRepublishedCount()).isEqualTo(1);
        } finally {
            subscription.dispose();
        }
    }

    /**
     * A misconfigured {@code chunkDelay} of, say, minutes would stall the single worker thread
     * for the whole fan-out with no operator signal beyond a very slow subscription. Capped and
     * reported the same way {@code chunkSize} already is.
     */
    @Test
    @Timeout(5)
    public void testChunkDelayAboveMaximumFallsBackToDefault() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 1, Duration.ofMinutes(1), DEFAULT_THRESHOLD);

        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        storeJourney("TST:Line:1", "TST:ServiceJourney:2", "TST:DatedServiceJourney:2", "NSR:Quay:1");
        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            // With chunkSize=1 and 2 candidates there is one inter-chunk sleep. A genuine
            // PT1M delay would blow the 5-second @Timeout; the fallback keeps it well inside.
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(republisher.getRepublishedCount()).isEqualTo(2);
        } finally {
            subscription.dispose();
        }
    }

    /**
     * Do NOT drop republishes on a large fan-out - silently not republishing is exactly the
     * failure this feature exists to prevent. Crossing the threshold only logs a warning;
     * every candidate must still go out.
     */
    @Test
    public void testLargeFanoutCrossingThresholdStillEmitsEveryCandidate() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 100, Duration.ofMillis(1), 2);

        for (int i = 0; i < 5; i++) {
            storeJourney("TST:Line:1", "TST:ServiceJourney:" + i, "TST:DatedServiceJourney:" + i, "NSR:Quay:1");
        }

        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(republisher.getRepublishedCount())
                    .withFailMessage("crossing the large fan-out threshold must not drop a single "
                            + "candidate - it only logs a warning naming the counts")
                    .isEqualTo(5);
        } finally {
            subscription.dispose();
        }
    }

    /**
     * A multi-second fan-out must not keep emitting into a sink whose last subscriber already
     * left - re-checking currentSubscribers() at the top of each chunk is free and stops that.
     * A mocked publisher makes the subscriber-count sequence deterministic: present at the
     * initial guard and for the first chunk, gone by the second.
     */
    @Test
    public void testRepublishNowStopsEarlyWhenSubscribersDropMidFanOut() {
        EstimatedTimetableUpdateRxPublisher etPublisher = Mockito.mock(EstimatedTimetableUpdateRxPublisher.class);
        Mockito.when(etPublisher.currentSubscribers()).thenReturn(1, 1, 0);

        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 1, Duration.ofMillis(1), DEFAULT_THRESHOLD);

        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        storeJourney("TST:Line:1", "TST:ServiceJourney:2", "TST:DatedServiceJourney:2", "NSR:Quay:1");
        storeJourney("TST:Line:1", "TST:ServiceJourney:3", "TST:DatedServiceJourney:3", "NSR:Quay:1");

        republisher.republishNow(Set.of("TST:Line:1"));

        assertThat(republisher.getRepublishedCount())
                .withFailMessage("the per-chunk subscriber re-check must stop emission once the "
                        + "last subscriber has left, rather than emitting all 3 candidates")
                .isEqualTo(1);
        Mockito.verify(etPublisher, Mockito.times(1)).publishUpdate(Mockito.any());
    }

    @Test
    public void testScanRepublishAndChunkCountsAreAlsoRecordedOnPrometheusMetricsService() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(metricsService,
                timetableMap, etPublisher, 100, Duration.ofMillis(1), DEFAULT_THRESHOLD);

        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(registry.find("app.vehicles.situation.republish.scan").counter().count())
                    .withFailMessage("the in-process getScanCount() moving is not enough - nothing "
                            + "outside tests can see it, since DEBUG logging is off in production")
                    .isEqualTo(1.0);
            assertThat(registry.find("app.vehicles.situation.republish.journey").counter().count())
                    .isEqualTo(1.0);
            assertThat(registry.find("app.vehicles.situation.republish.chunk").counter().count())
                    .isEqualTo(1.0);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void testHandOffDoesNotThrowWhenTheWorkerIsNotRunning() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));

        // SX ingest must never be broken by a republishing failure - a situation that fails to
        // trigger a republish is still stored and still reaches the situations subscription.
        republisher.onSituationChanged(null, current);
    }
}
