package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.VehicleModeEnum;

public enum VehicleModeEnumeration {
    
    AIR, BUS, RAIL, TRAM, COACH, FERRY, METRO;

    public static VehicleModeEnumeration fromValue(VehicleModeEnum mode) {
        switch (mode) {
            case AIR:
                return AIR;
            case BUS:
                return BUS;
            case RAIL:
                return RAIL;
            case TRAM:
                return TRAM;
            case COACH:
                return COACH;
            case FERRY:
                return FERRY;
            case METRO:
                return METRO;
        }
        return null;
    }
}
