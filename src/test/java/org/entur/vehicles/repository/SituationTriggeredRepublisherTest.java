package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
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

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class SituationTriggeredRepublisherTest {

    private AutoPurgingTimetableMap timetableMap;
    private SituationTriggeredRepublisher republisher;

    @BeforeEach
    public void setUp() {
        timetableMap = new AutoPurgingTimetableMap(Duration.parse("PT1M"), Duration.parse("PT10M"));
        republisher = new SituationTriggeredRepublisher(
                timetableMap, new EstimatedTimetableUpdateRxPublisher());
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
}
