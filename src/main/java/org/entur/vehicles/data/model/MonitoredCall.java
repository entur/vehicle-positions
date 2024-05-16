package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public class MonitoredCall {
    String stopPointRef;
    Integer order;

    Boolean vehicleAtStop;

    public String getStopPointRef() {
        return stopPointRef;
    }

    public void setStopPointRef(String stopPointRef) {
        this.stopPointRef = stopPointRef;
    }

    public Integer getOrder() {
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }

    public Boolean getVehicleAtStop() {
        return vehicleAtStop;
    }

    public void setVehicleAtStop(Boolean vehicleAtStop) {
        this.vehicleAtStop = vehicleAtStop;
    }
}
