package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.VehicleModeEnum;

public enum VehicleModeEnumeration {

    AIR, BUS, COACH, FERRY, METRO, RAIL, TAXI, TRAM;

    /** The mode for a SIRI VehicleMode, or null for UNKNOWN or a value SIRI does not define. */
    public static VehicleModeEnumeration fromValue(String mode) {
        VehicleModeEnum vehicleModeEnum;
        try {
            vehicleModeEnum = VehicleModeEnum.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return switch (vehicleModeEnum) {
            case AIR -> AIR;
            case BUS -> BUS;
            case COACH -> COACH;
            case FERRY -> FERRY;
            case METRO, UNDERGROUND -> METRO;
            case RAIL -> RAIL;
            case TAXI -> TAXI;
            case TRAM -> TRAM;
            default -> null;
        };
    }

    /**
     * The mode for a NeTEx TransportMode, or null when it has no counterpart here (cableway,
     * funicular, lift, other, unknown) or is absent.
     */
    public static VehicleModeEnumeration fromNetexTransportMode(String transportMode) {
        if (transportMode == null) {
            return null;
        }
        return switch (transportMode) {
            case "air" -> AIR;
            case "bus", "trolleyBus" -> BUS;
            case "coach" -> COACH;
            case "water" -> FERRY;
            case "metro" -> METRO;
            case "rail" -> RAIL;
            case "taxi" -> TAXI;
            case "tram" -> TRAM;
            default -> null;
        };
    }
}
