package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.OccupancyEnum;

public enum OccupancyEnumeration {

    UNKNOWN, EMPTY, MANY_SEATS_AVAILABLE, SEATS_AVAILABLE,
    FEW_SEATS_AVAILABLE, STANDING_AVAILABLE, STANDING_ROOM_ONLY, FULL,
    NOT_ACCEPTING_PASSENGERS;

    public static OccupancyEnumeration fromValue(String occupancy) {
        OccupancyEnum occupancyEnum = OccupancyEnum.valueOf(occupancy);
        return switch (occupancyEnum) {
            case UNKNOWN -> UNKNOWN;
            case EMPTY -> EMPTY;
            case MANY_SEATS_AVAILABLE -> MANY_SEATS_AVAILABLE;
            case SEATS_AVAILABLE -> SEATS_AVAILABLE;
            case FEW_SEATS_AVAILABLE -> FEW_SEATS_AVAILABLE;
            case STANDING_AVAILABLE -> STANDING_AVAILABLE;
            case STANDING_ROOM_ONLY -> STANDING_ROOM_ONLY;
            case FULL -> FULL;
            case NOT_ACCEPTING_PASSENGERS -> NOT_ACCEPTING_PASSENGERS;
            default -> null;
        };
    }
}
