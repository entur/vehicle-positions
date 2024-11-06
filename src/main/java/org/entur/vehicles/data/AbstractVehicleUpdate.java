package org.entur.vehicles.data;

import com.google.common.base.Objects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;

abstract class AbstractVehicleUpdate {

  protected ServiceJourney serviceJourney;
  protected DatedServiceJourney datedServiceJourney;
  protected Operator operator;
  protected Codespace codespace;
  protected VehicleModeEnumeration mode;
  protected String vehicleRef;
  protected Line line;
  protected Boolean monitored;

  public ServiceJourney getServiceJourney() {
    if (datedServiceJourney != null) {
      return datedServiceJourney.getServiceJourney();
    }
    return serviceJourney;
  }

  public void setServiceJourney(ServiceJourney serviceJourney) {
    this.serviceJourney = serviceJourney;
  }

  public void setDatedServiceJourney(DatedServiceJourney datedServiceJourney) {
    this.datedServiceJourney = datedServiceJourney;
  }

  public DatedServiceJourney getDatedServiceJourney() {
    return datedServiceJourney;
  }

  public Operator getOperator() {
    return operator;
  }

  public void setOperator(Operator operator) {
    this.operator = operator;
  }

  public Codespace getCodespace() {
    return codespace;
  }

  public void setCodespace(Codespace codespace) {
    this.codespace = codespace;
  }

  public VehicleModeEnumeration getMode() {
    return mode;
  }

  public void setMode(VehicleModeEnumeration mode) {
    this.mode = mode;
  }

  public String getVehicleRef() {
    return vehicleRef;
  }

  public void setVehicleRef(String vehicleRef) {
    this.vehicleRef = vehicleRef;
  }

  /**
   * @deprecated
   */
  public String getVehicleId() {
    return getVehicleRef();
  }

  /**
   * @deprecated
   */
  public void setVehicleId(String vehicleRef) {
    setVehicleRef(vehicleRef);
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
        Objects.equal(serviceJourney, that.serviceJourney) &&
        Objects.equal(operator, that.operator) &&
        Objects.equal(codespace, that.codespace) &&
        Objects.equal(mode, that.mode) &&
        Objects.equal(vehicleRef, that.vehicleRef) &&
        Objects.equal(line, that.line);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(serviceJourney, operator, codespace, mode, vehicleRef, line);
  }


  public void setMonitored(boolean monitored) {
    this.monitored = monitored;
  }

  public Boolean isMonitored() {
    return monitored;
  }
}
