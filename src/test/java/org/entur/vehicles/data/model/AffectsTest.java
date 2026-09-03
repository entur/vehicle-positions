package org.entur.vehicles.data.model;

import org.entur.vehicles.data.StopConditionEnumeration;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

public class AffectsTest {

    @Test
    public void testEmptyByDefault() {
        Affects affects = new Affects();
        assertTrue(affects.isEmpty());
        assertTrue(affects.getLines().isEmpty());
        assertTrue(affects.getLineRefs().isEmpty());
    }

    @Test
    public void testAddingLinePopulatesBothListAndRefSet() {
        Affects affects = new Affects();
        affects.addLine(new Line("TST:Line:1"));

        assertFalse(affects.isEmpty());
        assertEquals(1, affects.getLines().size());
        assertTrue(affects.getLineRefs().contains("TST:Line:1"));
    }

    @Test
    public void testDuplicateLineIsAddedOnce() {
        Affects affects = new Affects();
        affects.addLine(new Line("TST:Line:1", "First"));
        affects.addLine(new Line("TST:Line:1", "Duplicate from vehicle journey"));

        assertEquals(1, affects.getLines().size());
        assertEquals("First", affects.getLines().get(0).getLineName());
        assertEquals(1, affects.getLineRefs().size());
    }

    @Test
    public void testStopPointsAndStopPlacesShareOneRefSet() {
        Affects affects = new Affects();
        affects.addStopPoint(new StopPoint("TST:Quay:1"));
        affects.addStopPlace(new StopPoint("TST:StopPlace:9"));

        assertEquals(1, affects.getStopPoints().size());
        assertEquals(1, affects.getStopPlaces().size());
        assertEquals(2, affects.getStopRefs().size());
        assertTrue(affects.getStopRefs().contains("TST:Quay:1"));
        assertTrue(affects.getStopRefs().contains("TST:StopPlace:9"));
    }

    @Test
    public void testJourneysOperatorsAndModes() {
        Affects affects = new Affects();
        affects.addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));
        affects.addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:1"));
        affects.addOperator(new Operator("TST:Operator:1"));
        affects.addVehicleMode(VehicleModeEnumeration.BUS);
        affects.addVehicleMode(VehicleModeEnumeration.BUS);

        assertTrue(affects.getServiceJourneyIds().contains("TST:ServiceJourney:1"));
        assertTrue(affects.getDatedServiceJourneyIds().contains("TST:DatedServiceJourney:1"));
        assertTrue(affects.getOperatorRefs().contains("TST:Operator:1"));
        assertEquals(1, affects.getVehicleModes().size());
    }

    @Test
    public void testNullsAreIgnored() {
        Affects affects = new Affects();
        affects.addLine(null);
        affects.addLine(new Line((String) null));
        affects.addStopPoint(null);
        affects.addOperator(null);
        affects.addVehicleMode(null);

        assertTrue(affects.isEmpty());
    }

    @Test
    public void testScopedStopsReachAllStopRefsButNotStopRefs() {
        Affects affects = new Affects();
        affects.addStopPlace(new StopPoint("NSR:StopPlace:top"));

        AffectedStop scoped = new AffectedStop(new StopPoint("NSR:StopPlace:157"),
                List.of(StopConditionEnumeration.startPoint, StopConditionEnumeration.notStopping));
        affects.addVehicleJourney(new AffectedVehicleJourney(
                null, new DatedServiceJourney("TST:DatedServiceJourney:1"), null, null, List.of(scoped)));

        // The matcher's set stays top-level only - widening it would re-create the
        // over-matching this change exists to fix.
        assertThat(affects.getStopRefs()).containsExactly("NSR:StopPlace:top");
        // The filter's set is the union, so filtering by stop still finds the situation.
        assertThat(affects.getAllStopRefs())
                .containsExactlyInAnyOrder("NSR:StopPlace:top", "NSR:StopPlace:157");
        // The flat display lists are top-level only too.
        assertThat(affects.getStopPlaces()).hasSize(1);

        assertThat(affects.getVehicleJourneys()).hasSize(1);
        AffectedVehicleJourney entry = affects.getVehicleJourneys().get(0);
        assertThat(entry.getDatedServiceJourney().getId()).isEqualTo("TST:DatedServiceJourney:1");
        assertThat(entry.getStops()).hasSize(1);
        assertThat(entry.getStops().get(0).getStopConditions())
                .containsExactly(StopConditionEnumeration.startPoint, StopConditionEnumeration.notStopping);
    }

    @Test
    public void testAffectedLineEntryCarriesItsOwnStops() {
        Affects affects = new Affects();
        Line line = new Line("TST:Line:1");
        assertThat(affects.addLine(line)).isTrue();
        assertThat(affects.addLine(line)).isFalse();

        affects.addAffectedLine(new AffectedLine(line,
                List.of(new AffectedStop(new StopPoint("NSR:StopPlace:288"), List.of()))));

        assertThat(affects.getAffectedLines()).hasSize(1);
        assertThat(affects.getAffectedLines().get(0).getLine().getLineRef()).isEqualTo("TST:Line:1");
        assertThat(affects.getAllStopRefs()).containsExactly("NSR:StopPlace:288");
        assertThat(affects.getStopRefs()).isEmpty();
    }

    @Test
    public void testStopConditionFromValueIsNullForUnknownValues() {
        assertThat(StopConditionEnumeration.fromValue("startPoint"))
                .isEqualTo(StopConditionEnumeration.startPoint);
        assertThat(StopConditionEnumeration.fromValue("somethingElse")).isNull();
        assertThat(StopConditionEnumeration.fromValue(null)).isNull();
    }
}
