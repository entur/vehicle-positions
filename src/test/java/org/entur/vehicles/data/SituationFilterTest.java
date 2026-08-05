package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationFilterTest {

    private SituationUpdate situation() {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber("TST:SituationNumber:1");
        situation.setCodespace(Codespace.getCodespace("TST"));
        situation.setSeverity(SeverityEnumeration.severe);
        situation.setProgress(WorkflowStatusEnumeration.published);
        situation.setReportType("general");
        situation.setCreationTime(ZonedDateTime.now().minusDays(40));
        situation.setLastUpdated(ZonedDateTime.now());
        situation.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(1))));

        Affects affects = new Affects();
        affects.addLine(new Line("TST:Line:1"));
        affects.addStopPoint(new StopPoint("TST:Quay:1"));
        affects.addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));
        affects.addOperator(new Operator("TST:Operator:1"));
        affects.addVehicleMode(VehicleModeEnumeration.BUS);
        situation.setAffects(affects);

        return situation;
    }

    private SituationFilter filter(String codespaceId, String operatorRef, String lineRef, String stopRef,
                                   String serviceJourneyId, VehicleModeEnumeration mode,
                                   SeverityEnumeration severity, Boolean validNow, Boolean openEnded,
                                   Duration minAge, Boolean includeClosed) {
        return new SituationFilter(null, MetricType.QUERY, null, codespaceId, operatorRef, lineRef, stopRef,
                serviceJourneyId, null, mode, severity, null, validNow, openEnded, minAge, includeClosed,
                null, null);
    }

    @Test
    public void testEmptyFilterMatchesEverything() {
        assertTrue(filter(null, null, null, null, null, null, null, null, null, null, null)
                .isMatch(situation()));
    }

    @Test
    public void testMatchesOnAffectedObjects() {
        SituationUpdate situation = situation();
        assertTrue(filter("TST", null, null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, "TST:Operator:1", null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, "TST:Line:1", null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, "TST:Quay:1", null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, "TST:ServiceJourney:1", null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, null, VehicleModeEnumeration.BUS, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, null, null, SeverityEnumeration.severe, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testRejectsNonMatchingAffectedObjects() {
        SituationUpdate situation = situation();
        assertFalse(filter("ABC", null, null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, "TST:Line:999", null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, "TST:Quay:999", null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, null, null, VehicleModeEnumeration.RAIL, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, null, null, null, SeverityEnumeration.slight, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testCombinedCriteriaMustAllMatch() {
        SituationUpdate situation = situation();
        assertTrue(filter("TST", null, "TST:Line:1", null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter("TST", null, "TST:Line:999", null, null, null, null, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testValidNow() {
        SituationUpdate current = situation();
        assertTrue(filter(null, null, null, null, null, null, null, true, null, null, null).isMatch(current));

        SituationUpdate future = situation();
        future.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().plusDays(1), ZonedDateTime.now().plusDays(2))));
        assertFalse(filter(null, null, null, null, null, null, null, true, null, null, null).isMatch(future));
    }

    @Test
    public void testOpenEnded() {
        SituationUpdate bounded = situation();
        assertFalse(filter(null, null, null, null, null, null, null, null, true, null, null).isMatch(bounded));

        SituationUpdate openEnded = situation();
        openEnded.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(400), null)));
        assertTrue(filter(null, null, null, null, null, null, null, null, true, null, null).isMatch(openEnded));
    }

    @Test
    public void testMinAge() {
        SituationUpdate old = situation();
        assertTrue(filter(null, null, null, null, null, null, null, null, null, Duration.ofDays(30), null).isMatch(old));

        SituationUpdate fresh = situation();
        fresh.setCreationTime(ZonedDateTime.now().minusDays(1));
        assertFalse(filter(null, null, null, null, null, null, null, null, null, Duration.ofDays(30), null).isMatch(fresh));
    }

    @Test
    public void testClosedSituationsAreExcludedByDefault() {
        SituationUpdate closed = situation();
        closed.setProgress(WorkflowStatusEnumeration.closed);

        assertFalse(filter(null, null, null, null, null, null, null, null, null, null, null).isMatch(closed));
        assertFalse(filter(null, null, null, null, null, null, null, null, null, null, false).isMatch(closed));
        assertTrue(filter(null, null, null, null, null, null, null, null, null, null, true).isMatch(closed));
    }

    @Test
    public void testSituationNumbers() {
        SituationFilter byNumber = new SituationFilter(null, MetricType.QUERY,
                Set.of("TST:SituationNumber:1"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertTrue(byNumber.isMatch(situation()));

        SituationFilter byOtherNumber = new SituationFilter(null, MetricType.QUERY,
                Set.of("TST:SituationNumber:999"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertFalse(byOtherNumber.isMatch(situation()));
    }

    @Test
    public void testBufferDefaults() {
        SituationFilter defaults = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(defaults.getBufferSize() > 0);
        assertTrue(defaults.getBufferTimeMillis() > 0);

        SituationFilter explicit = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 5, 100);
        assertTrue(explicit.getBufferSize() == 5);
        assertTrue(explicit.getBufferTimeMillis() == 100);
    }
}
