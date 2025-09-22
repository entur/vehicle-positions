package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.OccupancyEnum;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public enum OccupancyStatus {

    noData,
    empty,
    seatsAvailable,
    manySeatsAvailable,
    fewSeatsAvailable,
    standingRoomOnly,
    standingAvailable,
    crushedStandingRoomOnly,
    full,
    notAcceptingPassengers;

    public static OccupancyStatus fromValue(String occupancy) {
        OccupancyEnum occupancyEnum = OccupancyEnum.valueOf(occupancy);
        return switch (occupancyEnum) {
            case EMPTY -> empty;
            case UNKNOWN, UNDEFINED -> noData;
            case MANY_SEATS_AVAILABLE -> manySeatsAvailable;
            case SEATS_AVAILABLE -> seatsAvailable;
            case FEW_SEATS_AVAILABLE -> fewSeatsAvailable;
            case STANDING_AVAILABLE -> standingAvailable;
            case STANDING_ROOM_ONLY -> standingRoomOnly;
            case CRUSHED_STANDING_ROOM_ONLY -> crushedStandingRoomOnly;
            case FULL -> full;
            case NOT_ACCEPTING_PASSENGERS -> notAcceptingPassengers;
        };
    }
}
