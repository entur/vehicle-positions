package org.entur.vehicles.repository.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BearingTest {

    private static final double DELTA = 0.01;

    @Test
    public void testCardinalDirections() {
        assertEquals(0, Bearing.initialBearing(59.0, 10.0, 59.001, 10.0), DELTA, "north");
        assertEquals(90, Bearing.initialBearing(0.0, 0.0, 0.0, 0.001), DELTA, "east");
        assertEquals(180, Bearing.initialBearing(59.001, 10.0, 59.0, 10.0), DELTA, "south");
        assertEquals(270, Bearing.initialBearing(0.0, 0.001, 0.0, 0.0), DELTA, "west");
    }

    @Test
    public void testDiagonalOnTheEquator() {
        assertEquals(45, Bearing.initialBearing(0.0, 0.0, 0.001, 0.001), DELTA, "north-east");
        assertEquals(225, Bearing.initialBearing(0.001, 0.001, 0.0, 0.0), DELTA, "south-west");
    }

    @Test
    public void testDiagonalAtOsloLatitudeAccountsForNarrowerLongitudes() {
        // At 60°N a degree of longitude is half a degree of latitude, so equal steps
        // in degrees point atan(0.5) = 26.57° east of north
        assertEquals(26.57, Bearing.initialBearing(60.0, 10.0, 60.001, 10.001), 0.05);
    }

    @Test
    public void testDistance() {
        // One thousandth of a degree of latitude is 111.2 m on a 6371 km sphere
        assertEquals(111.2, Bearing.distanceMeters(59.0, 10.0, 59.001, 10.0), 0.1);
        assertEquals(0, Bearing.distanceMeters(59.0, 10.0, 59.0, 10.0), DELTA);
    }
}
