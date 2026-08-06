package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SituationMatcherTest {

    private final ZonedDateTime noon = ZonedDateTime.parse("2026-08-06T12:00:00Z");

    private SituationUpdate situation(String number, ValidityPeriod... periods) {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber(number);
        situation.setProgress(WorkflowStatusEnumeration.published);
        situation.setAffects(new Affects());
        situation.setValidityPeriods(List.of(periods));
        return situation;
    }

    private Call call(String stopRef, ZonedDateTime arrival, ZonedDateTime departure) {
        Call call = new Call();
        call.setStopPoint(new StopPoint(stopRef));
        call.setAimedArrivalTime(arrival);
        call.setAimedDepartureTime(departure);
        return call;
    }

    private EstimatedTimetableUpdate timetable(String lineRef, String serviceJourneyId, Call... calls) {
        EstimatedTimetableUpdate timetable = new EstimatedTimetableUpdate();
        timetable.setLine(new Line(lineRef));
        timetable.setServiceJourney(new ServiceJourney(serviceJourneyId));
        for (Call call : calls) {
            timetable.addCall(call);
        }
        return timetable;
    }

    @Test
    public void testMatchesOnLine() {
        SituationUpdate situation = situation("TST:SituationNumber:line");
        situation.getAffects().addLine(new Line("TST:Line:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)))))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:line");
    }

    @Test
    public void testMatchesOnServiceJourneyAndDatedServiceJourney() {
        SituationUpdate bySj = situation("TST:SituationNumber:sj");
        bySj.getAffects().addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));

        SituationUpdate byDsj = situation("TST:SituationNumber:dsj");
        byDsj.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(bySj, byDsj));

        EstimatedTimetableUpdate timetable = timetable("TST:Line:9", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)));
        // The inner ServiceJourney is required: AbstractUpdate.getServiceJourney() delegates
        // through datedServiceJourney when one is set, so the two-arg constructor is what
        // TimetableRepository actually builds (see TimetableRepository.java:144,149).
        timetable.setDatedServiceJourney(new DatedServiceJourney(
                "TST:DatedServiceJourney:1", new ServiceJourney("TST:ServiceJourney:1")));

        assertThat(matcher.match(timetable))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactlyInAnyOrder("TST:SituationNumber:sj", "TST:SituationNumber:dsj");
    }

    @Test
    public void testDoesNotMatchAnUnrelatedSituation() {
        SituationUpdate situation = situation("TST:SituationNumber:elsewhere");
        situation.getAffects().addLine(new Line("TST:Line:999"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1))))).isEmpty();
    }

    @Test
    public void testClosedSituationNeverAttaches() {
        SituationUpdate closed = situation("TST:SituationNumber:closed");
        closed.setProgress(WorkflowStatusEnumeration.closed);
        closed.getAffects().addLine(new Line("TST:Line:1"));
        closed.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(closed));

        Call call = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", call))).isEmpty();
        assertThat(matcher.match(call)).isEmpty();
    }

    /** The case that motivated the whole design. */
    @Test
    public void testStopSituationIsTestedAgainstThatCallsOwnWindow() {
        SituationUpdate untilNoon = situation("TST:SituationNumber:quay",
                new ValidityPeriod(noon.minusHours(3), noon));
        untilNoon.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(untilNoon));

        Call arrivesLate = call("NSR:Quay:1", noon.plusMinutes(30), noon.plusMinutes(31));
        assertThat(matcher.match(arrivesLate))
                .withFailMessage("a quay message ending at 12:00 must not attach to a call at 12:30")
                .isEmpty();

        Call arrivesEarly = call("NSR:Quay:1", noon.minusMinutes(30), noon.minusMinutes(29));
        assertThat(matcher.match(arrivesEarly))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:quay");
    }

    @Test
    public void testLateCallDoesNotPullTheStopSituationOntoTheJourneyEither() {
        SituationUpdate untilNoon = situation("TST:SituationNumber:quay",
                new ValidityPeriod(noon.minusHours(3), noon));
        untilNoon.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(untilNoon));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon.plusMinutes(30), noon.plusMinutes(31))))).isEmpty();
    }

    @Test
    public void testStopSituationAppearsOnTheJourneyAndOnlyOnItsOwnCall() {
        SituationUpdate atQuay1 = situation("TST:SituationNumber:quay1");
        atQuay1.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(atQuay1));

        Call first = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        Call second = call("NSR:Quay:2", noon.plusMinutes(10), noon.plusMinutes(11));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", first, second)))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:quay1");
        assertThat(matcher.match(first)).hasSize(1);
        assertThat(matcher.match(second)).isEmpty();
    }

    @Test
    public void testSituationMatchingSeveralWaysAppearsOnceOnTheJourney() {
        SituationUpdate broad = situation("TST:SituationNumber:broad");
        broad.getAffects().addLine(new Line("TST:Line:1"));
        broad.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));
        broad.getAffects().addStopPoint(new StopPoint("NSR:Quay:2"));

        SituationMatcher matcher = new SituationMatcher(List.of(broad));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)),
                call("NSR:Quay:2", noon.plusMinutes(10), noon.plusMinutes(11)))))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:broad");
    }

    @Test
    public void testCallWithNoTimesStillAttachesAMatchingSituation() {
        SituationUpdate longExpired = situation("TST:SituationNumber:quay",
                new ValidityPeriod(noon.minusYears(2), noon.minusYears(1)));
        longExpired.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(longExpired));

        assertThat(matcher.match(call("NSR:Quay:1", null, null)))
                .withFailMessage("missing timestamps must not make a disruption disappear")
                .hasSize(1);
    }

    @Test
    public void testOpenEndedSituationAlwaysAttaches() {
        SituationUpdate openEnded = situation("TST:SituationNumber:openended",
                new ValidityPeriod(noon.minusYears(3), null));
        openEnded.getAffects().addLine(new Line("TST:Line:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(openEnded));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon.plusYears(5), noon.plusYears(5))))).hasSize(1);
    }

    @Test
    public void testJourneyLevelMatchUsesTheWholeJourneySpan() {
        // Valid only during the journey's later half - a line match must still attach,
        // because the journey as a whole overlaps it.
        SituationUpdate laterToday = situation("TST:SituationNumber:later",
                new ValidityPeriod(noon.plusMinutes(20), noon.plusMinutes(40)));
        laterToday.getAffects().addLine(new Line("TST:Line:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(laterToday));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)),
                call("NSR:Quay:2", noon.plusMinutes(30), noon.plusMinutes(31))))).hasSize(1);
    }
}
