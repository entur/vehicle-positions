package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.OccupancyEnum;

public enum OccupancyEnumeration {

    UNKNOWN, MANY_SEATS_AVAILABLE, SEATS_AVAILABLE, FEW_SEATS_AVAILABLE, STANDING_AVAILABLE, FULL, NOT_ACCEPTING_PASSENGERS;

    public static OccupancyEnumeration fromValue(OccupancyEnum occupancy) {
        switch (occupancy) {
            case UNKNOWN:
                return UNKNOWN;
            case MANY_SEATS_AVAILABLE:
                return MANY_SEATS_AVAILABLE;
            case SEATS_AVAILABLE:
                return SEATS_AVAILABLE;
            case FEW_SEATS_AVAILABLE:
                return FEW_SEATS_AVAILABLE;
            case STANDING_AVAILABLE:
                return STANDING_AVAILABLE;
            case FULL:
                return FULL;
            case NOT_ACCEPTING_PASSENGERS:
                return NOT_ACCEPTING_PASSENGERS;
        }
        return null;
    }
}
