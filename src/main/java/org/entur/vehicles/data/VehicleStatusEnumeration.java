package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.VehicleStatusEnum;

public enum VehicleStatusEnumeration {

    ASSIGNED, AT_ORIGIN, CANCELLED, COMPLETED, IN_PROGRESS, OFF_ROUTE;

    public static VehicleStatusEnumeration fromValue(String vehicleStatus) {
        VehicleStatusEnum vehicleStatusEnum = VehicleStatusEnum.valueOf(vehicleStatus);
        switch (vehicleStatusEnum) {
            case ASSIGNED:
                return ASSIGNED;
            case AT_ORIGIN:
                return AT_ORIGIN;
            case CANCELLED:
                return CANCELLED;
            case COMPLETED:
                return COMPLETED;
            case IN_PROGRESS:
                return IN_PROGRESS;
            case OFF_ROUTE:
                return OFF_ROUTE;
            default:
                return null;
        }
    }
}
