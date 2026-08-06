package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.Codespace;
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
    public void testStopOnlySituationAppearsOnlyOnItsOwnCall() {
        SituationUpdate atQuay1 = situation("TST:SituationNumber:quay1");
        atQuay1.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(atQuay1));

        Call first = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        Call second = call("NSR:Quay:2", noon.plusMinutes(10), noon.plusMinutes(11));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", first, second)))
                .withFailMessage("a stop-scoped situation belongs on its call, not on the journey")
                .isEmpty();
        assertThat(matcher.match(first))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:quay1");
        assertThat(matcher.match(second)).isEmpty();
    }

    /**
     * The stop is the more specific placement, so a situation naming both a line and one of
     * the journey's stops is reported against that call alone. Listing it on the journey too
     * would make a client rendering both fields show the same disruption twice.
     */
    @Test
    public void testStopScopedSituationIsNotAlsoListedOnTheJourney() {
        // Deliberately the journey's LAST call, so a matcher that only consulted calls.get(0)
        // would still list the situation on the journey and fail here.
        SituationUpdate lineAtQuay3 = situation("TST:SituationNumber:lineAtQuay3");
        lineAtQuay3.getAffects().addLine(new Line("TST:Line:1"));
        lineAtQuay3.getAffects().addStopPoint(new StopPoint("NSR:Quay:3"));

        SituationMatcher matcher = new SituationMatcher(List.of(lineAtQuay3));

        Call first = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        Call second = call("NSR:Quay:2", noon.plusMinutes(10), noon.plusMinutes(11));
        Call third = call("NSR:Quay:3", noon.plusMinutes(20), noon.plusMinutes(21));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", first, second, third)))
                .withFailMessage("a situation already reported against a call must not be repeated on the journey")
                .isEmpty();
        assertThat(matcher.match(third))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:lineAtQuay3");
        assertThat(matcher.match(first)).isEmpty();
        assertThat(matcher.match(second)).isEmpty();
    }

    /**
     * The journey keeps a situation whose stop match did not actually fire. Exclusion is
     * driven by what matched, not by what the situation happens to name - otherwise a
     * line-wide disruption would vanish from the journey because of a stop reference whose
     * validity had already lapsed by the time the vehicle got there.
     */
    @Test
    public void testJourneyKeepsTheSituationWhenTheStopMatchDidNotFire() {
        SituationUpdate untilNoon = situation("TST:SituationNumber:lapsedAtQuay",
                new ValidityPeriod(noon.minusHours(1), noon));
        untilNoon.getAffects().addLine(new Line("TST:Line:1"));
        untilNoon.getAffects().addStopPoint(new StopPoint("NSR:Quay:2"));

        SituationMatcher matcher = new SituationMatcher(List.of(untilNoon));

        // Journey spans 11:30-12:46, so the line match holds; the vehicle reaches Quay:2 at
        // 12:45, by which time the situation has lapsed, so the stop match does not fire.
        Call early = call("NSR:Quay:1", noon.minusMinutes(30), noon.minusMinutes(29));
        Call late = call("NSR:Quay:2", noon.plusMinutes(45), noon.plusMinutes(46));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", early, late)))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:lapsedAtQuay");
        assertThat(matcher.match(late)).isEmpty();
    }

    /**
     * A situation naming several of the journey's stops is reported against every one of
     * them - a client marks each affected stop, so it needs the situation on all of them.
     * It still does not appear at journey level.
     */
    @Test
    public void testSituationOnSeveralStopsIsReportedOnEveryAffectedCall() {
        SituationUpdate atBothQuays = situation("TST:SituationNumber:bothQuays");
        atBothQuays.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));
        atBothQuays.getAffects().addStopPoint(new StopPoint("NSR:Quay:2"));

        SituationMatcher matcher = new SituationMatcher(List.of(atBothQuays));

        Call first = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        Call second = call("NSR:Quay:2", noon.plusMinutes(10), noon.plusMinutes(11));
        Call third = call("NSR:Quay:3", noon.plusMinutes(20), noon.plusMinutes(21));

        assertThat(matcher.match(first))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:bothQuays");
        assertThat(matcher.match(second))
                .withFailMessage("every affected stop needs the situation so the client can mark it")
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:bothQuays");
        assertThat(matcher.match(third)).isEmpty();
        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", first, second, third)))
                .isEmpty();
    }

    /**
     * {@code situationNumber} alone does not identify a situation - {@code SituationRepository}
     * keys by codespace and number together. Two codespaces publishing the same number must not
     * interfere: one operator's stop-scoped situation must not knock another operator's
     * line-scoped situation off the journey.
     */
    @Test
    public void testSameSituationNumberInTwoCodespacesDoesNotInterfere() {
        SituationUpdate byLine = situation("TST:SituationNumber:shared");
        byLine.setCodespace(Codespace.getCodespace("AAA"));
        byLine.getAffects().addLine(new Line("TST:Line:1"));

        SituationUpdate byStop = situation("TST:SituationNumber:shared");
        byStop.setCodespace(Codespace.getCodespace("BBB"));
        byStop.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(byLine, byStop));

        Call call = call("NSR:Quay:1", noon, noon.plusMinutes(1));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", call)))
                .withFailMessage("codespace BBB's stop-scoped situation must not remove codespace "
                        + "AAA's line-scoped one, which merely shares a situation number")
                .extracting(SituationUpdate::getCodespace)
                .containsExactly(Codespace.getCodespace("AAA"));
        assertThat(matcher.match(call))
                .extracting(SituationUpdate::getCodespace)
                .containsExactly(Codespace.getCodespace("BBB"));
    }

    @Test
    public void testSituationMatchingSeveralWaysAppearsOnceOnTheJourney() {
        SituationUpdate broad = situation("TST:SituationNumber:broad");
        broad.getAffects().addLine(new Line("TST:Line:1"));
        broad.getAffects().addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));

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
