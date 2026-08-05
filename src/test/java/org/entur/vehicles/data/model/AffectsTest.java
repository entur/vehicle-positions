package org.entur.vehicles.data.model;

import org.entur.vehicles.data.VehicleModeEnumeration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
