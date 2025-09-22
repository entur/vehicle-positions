package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.VehicleStatusEnum;

public enum VehicleStatusEnumeration {

    ASSIGNED, AT_ORIGIN, CANCELLED, COMPLETED, IN_PROGRESS, OFF_ROUTE;

    public static VehicleStatusEnumeration fromValue(String vehicleStatus) {
        VehicleStatusEnum vehicleStatusEnum = VehicleStatusEnum.valueOf(vehicleStatus);
        return switch (vehicleStatusEnum) {
            case ASSIGNED -> ASSIGNED;
            case AT_ORIGIN -> AT_ORIGIN;
            case CANCELLED -> CANCELLED;
            case COMPLETED -> COMPLETED;
            case IN_PROGRESS -> IN_PROGRESS;
            case OFF_ROUTE -> OFF_ROUTE;
            default -> null;
        };
    }
}
