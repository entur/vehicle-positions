package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class SituationTriggeredRepublisherTest {

    private AutoPurgingTimetableMap timetableMap;
    private SituationTriggeredRepublisher republisher;

    @BeforeEach
    public void setUp() {
        timetableMap = new AutoPurgingTimetableMap(Duration.parse("PT1M"), Duration.parse("PT10M"));
        republisher = new SituationTriggeredRepublisher(
                timetableMap, new EstimatedTimetableUpdateRxPublisher(), 100, Duration.ofMillis(1));
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
        SituationTriggeredRepublisher republisher =
                new SituationTriggeredRepublisher(timetableMap, etPublisher, 100, Duration.ofMillis(1));

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
        SituationTriggeredRepublisher republisher =
                new SituationTriggeredRepublisher(timetableMap, etPublisher, 100, Duration.ofMillis(1));

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
        SituationTriggeredRepublisher republisher =
                new SituationTriggeredRepublisher(timetableMap, etPublisher, 100, Duration.ofMillis(1));
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
        SituationTriggeredRepublisher republisher =
                new SituationTriggeredRepublisher(timetableMap, etPublisher, 0, Duration.ofMillis(1));

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

    @Test
    public void testHandOffDoesNotThrowWhenTheWorkerIsNotRunning() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));

        // SX ingest must never be broken by a republishing failure - a situation that fails to
        // trigger a republish is still stored and still reaches the situations subscription.
        republisher.onSituationChanged(null, current);
    }
}
