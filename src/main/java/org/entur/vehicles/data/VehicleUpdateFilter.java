package org.entur.vehicles.data;


import org.entur.vehicles.data.model.*;

import java.util.StringJoiner;

public class VehicleUpdateFilter extends AbstractVehicleUpdate {

  public VehicleUpdateFilter(
      String serviceJourneyId, String operatorRef, String codespaceId, VehicleModeEnumeration mode, String vehicleId,
      String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox
  ) {
    if (serviceJourneyId != null) {
      this.serviceJourney = new ServiceJourney(serviceJourneyId);
    }
    if (operatorRef != null) {
      this.operator = new Operator(operatorRef);
    }
    if (codespaceId != null) {
      this.codespace = new Codespace(codespaceId);
    }
    this.mode = mode;
    this.vehicleRef = vehicleId;
    if (lineRef != null | lineName != null) {
      this.line = new Line(lineRef, lineName);
    }
    this.monitored = monitored;
    this.boundingBox = boundingBox;
  }

  private BoundingBox boundingBox;

  public BoundingBox getBoundingBox() {
    return boundingBox;
  }

  public void setBoundingBox(BoundingBox boundingBox) {
    this.boundingBox = boundingBox;
  }

  public void setLineRef(String lineRef) {
    if (this.getLine() == null) {
      setLine(new Line(null, null));
    }
    this.getLine().setLineRef(lineRef);
  }

  public void setLineName(String lineName) {
    if (this.getLine() == null) {
      setLine(new Line(null, null));
    }
    this.getLine().setLineName(lineName);
  }

  public boolean isMatch(VehicleUpdate vehicleUpdate) {

    boolean isCompleteMatch = true;

    if (boundingBox != null) {
      isCompleteMatch = isCompleteMatch & boundingBox.contains(vehicleUpdate.getLocation());
    }
    if (isCompleteMatch && serviceJourney != null) {
      isCompleteMatch = isCompleteMatch & matches(serviceJourney, vehicleUpdate.getServiceJourney());
    }
    if (isCompleteMatch && operator != null) {
      isCompleteMatch = isCompleteMatch & matches(operator, vehicleUpdate.getOperator());
    }
    if (isCompleteMatch && codespace != null) {
      isCompleteMatch = isCompleteMatch & matches(codespace, vehicleUpdate.getCodespace());
    }
    if (isCompleteMatch && mode != null) {
      isCompleteMatch = isCompleteMatch & matches(mode, vehicleUpdate.getMode());
    }
    if (isCompleteMatch && vehicleRef != null) {
      isCompleteMatch = isCompleteMatch & matches(vehicleRef, vehicleUpdate.getVehicleRef());
    }
    if (isCompleteMatch && line != null) {
      isCompleteMatch = isCompleteMatch & matches(line.getLineRef(), vehicleUpdate.getLine().getLineRef());
      isCompleteMatch = isCompleteMatch & matches(line.getLineName(), vehicleUpdate.getLine().getLineName());
    }
    if (isCompleteMatch && monitored != null) {
      isCompleteMatch = isCompleteMatch & monitored.equals(vehicleUpdate.isMonitored());
    }

    return isCompleteMatch;
  }

  private boolean matches(Identifier identifiedObj, Identifier identifierObj_2) {
    return identifiedObj.matches(identifierObj_2);
  }

  private boolean matches(String template, String value) {

    if (template != null) {

      if (value == null) {
        // If a template-value is set, null-values does not match
        return false;
      }
      return value.matches(template);
    }
    return true;
  }

  private boolean matches(VehicleModeEnumeration template, VehicleModeEnumeration value) {

    if (template != null) {

      if (value == null) {
        // If a template-value is set, null-values does not match
        return false;
      }
      return value.equals(template);
    }
    return true;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", VehicleUpdateFilter.class.getSimpleName() + "[", "]")
        .add("codespaceId='" + codespace + "'")
        .add("operator='" + operator + "'")
        .add("line=" + line)
        .add("serviceJourneyId='" + serviceJourney + "'")
        .add("vehicleId='" + vehicleRef + "'")
        .add("boundingBox=" + boundingBox)
        .add("mode=" + mode)
        .toString();
  }
}
