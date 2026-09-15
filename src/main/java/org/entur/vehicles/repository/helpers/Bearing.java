package org.entur.vehicles.repository.helpers;

/**
 * Spherical-earth geometry for deriving a vehicle's bearing from two consecutive positions.
 */
public class Bearing {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    /** Compass bearing in degrees [0, 360) from the first point towards the second. */
    public static double initialBearing(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double y = Math.sin(deltaLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);

        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Great-circle (haversine) distance in meters. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);

        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }
}
