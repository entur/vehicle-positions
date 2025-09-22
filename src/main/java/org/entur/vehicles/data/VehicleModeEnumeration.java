package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.VehicleModeEnum;

public enum VehicleModeEnumeration {
    
    AIR, BUS, COACH, FERRY, METRO, RAIL, TAXI, TRAM;

    public static VehicleModeEnumeration fromValue(String mode) {
        VehicleModeEnum vehicleModeEnum = VehicleModeEnum.valueOf(mode);
        return switch (vehicleModeEnum) {
            case AIR -> AIR;
            case BUS -> BUS;
            case COACH -> COACH;
            case FERRY -> FERRY;
            case METRO -> METRO;
            case RAIL -> RAIL;
            case TAXI -> TAXI;
            case TRAM -> TRAM;
            default -> null;
        };
    }
}
