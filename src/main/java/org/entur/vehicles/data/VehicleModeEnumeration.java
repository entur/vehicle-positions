package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.VehicleModeEnum;

public enum VehicleModeEnumeration {
    
    AIR, BUS, COACH, FERRY, METRO, RAIL, TAXI, TRAM;

    public static VehicleModeEnumeration fromValue(String mode) {
        VehicleModeEnum vehicleModeEnum = VehicleModeEnum.valueOf(mode);
        switch (vehicleModeEnum) {
            case AIR:
                return AIR;
            case BUS:
                return BUS;
            case COACH:
                return COACH;
            case FERRY:
                return FERRY;
            case METRO:
                return METRO;
            case RAIL:
                return RAIL;
            case TAXI:
                return TAXI;
            case TRAM:
                return TRAM;
        }
        return null;
    }
}
