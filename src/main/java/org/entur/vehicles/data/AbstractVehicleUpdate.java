package org.entur.vehicles.data;

import com.google.common.base.Objects;
import org.entur.vehicles.data.model.Line;

abstract class AbstractVehicleUpdate {

  protected String serviceJourneyId;
  protected String operatorRef;
  protected String codespaceId;
  protected VehicleModeEnumeration mode;
  protected String vehicleId;
  protected Line line;
  protected Boolean monitored;

  public String getServiceJourneyId() {
    return serviceJourneyId;
  }

  public void setServiceJourneyId(String serviceJourneyId) {
    this.serviceJourneyId = serviceJourneyId;
  }

  public String getOperatorRef() {
    return operatorRef;
  }

  public void setOperatorRef(String operatorRef) {
    this.operatorRef = operatorRef;
  }

  public String getCodespaceId() {
    return codespaceId;
  }

  public void setCodespaceId(String codespaceId) {
    this.codespaceId = codespaceId;
  }

  public VehicleModeEnumeration getMode() {
    return mode;
  }

  public void setMode(VehicleModeEnumeration mode) {
    this.mode = mode;
  }

  public String getVehicleId() {
    return vehicleId;
  }

  public void setVehicleId(String vehicleId) {
    this.vehicleId = vehicleId;
  }

  public Line getLine() {
    return line;
  }

  public void setLine(Line line) {
    this.line = line;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) { return true; }
    AbstractVehicleUpdate that = (AbstractVehicleUpdate) o;
    return
        Objects.equal(serviceJourneyId, that.serviceJourneyId) &&
        Objects.equal(operatorRef, that.operatorRef) &&
        Objects.equal(codespaceId, that.codespaceId) &&
        Objects.equal(mode, that.mode) &&
        Objects.equal(vehicleId, that.vehicleId) &&
        Objects.equal(line, that.line);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(serviceJourneyId, operatorRef, codespaceId, mode, vehicleId, line);
  }


  public void setMonitored(boolean monitored) {
    this.monitored = monitored;
  }

  public Boolean isMonitored() {
    return monitored;
  }
}
