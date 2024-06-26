package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.OccupancyEnum;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public enum OccupancyStatus {

    noData,
    empty,
    manySeatsAvailable,
    fewSeatsAvailable,
    standingRoomOnly,
    crushedStandingRoomOnly,
    full,
    notAcceptingPassengers;

    public static OccupancyStatus fromValue(String occupancy) {
        OccupancyEnum occupancyEnum = OccupancyEnum.valueOf(occupancy);
        switch (occupancyEnum) {
            case EMPTY:
                return empty;
            case UNKNOWN:
                return noData;
            case MANY_SEATS_AVAILABLE:
                return manySeatsAvailable;
            case SEATS_AVAILABLE:
            case FEW_SEATS_AVAILABLE:
                return fewSeatsAvailable;
            case STANDING_AVAILABLE:
                return standingRoomOnly;
            case CRUSHED_STANDING_ROOM_ONLY:
                return crushedStandingRoomOnly;
            case FULL:
                return full;
            case NOT_ACCEPTING_PASSENGERS:
                return notAcceptingPassengers;
        }
        return noData;
    }
}
