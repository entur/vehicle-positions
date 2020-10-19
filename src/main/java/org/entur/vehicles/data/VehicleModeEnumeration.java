package org.entur.vehicles.data;

import uk.org.siri.www.siri.VehicleModesEnumeration;

public enum VehicleModeEnumeration {
    
    AIR, BUS, RAIL, TRAM, COACH, FERRY, METRO;

    public static VehicleModeEnumeration fromValue(VehicleModesEnumeration mode) {
        switch (mode) {
            case VEHICLE_MODES_ENUMERATION_AIR:
                return AIR;
            case VEHICLE_MODES_ENUMERATION_BUS:
                return BUS;
            case VEHICLE_MODES_ENUMERATION_RAIL:
                return RAIL;
            case VEHICLE_MODES_ENUMERATION_TRAM:
                return TRAM;
            case VEHICLE_MODES_ENUMERATION_COACH:
                return COACH;
            case VEHICLE_MODES_ENUMERATION_FERRY:
                return FERRY;
            case VEHICLE_MODES_ENUMERATION_METRO:
                return METRO;
        }
        return null;
    }
}
