package org.entur.vehicles.data;


import java.util.StringJoiner;

public class VehicleUpdateFilter extends AbstractVehicleUpdate {

  public VehicleUpdateFilter() { }
  public VehicleUpdateFilter(
      String serviceJourneyId, String operator, String codespaceId, VehicleModeEnumeration mode, String vehicleId,
      String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox
  ) {
    this.serviceJourneyId = serviceJourneyId;
    this.operator = operator;
    this.codespaceId = codespaceId;
    this.mode = mode;
    this.vehicleId = vehicleId;
    this.line = new Line(lineRef, lineName);
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

  public boolean isMatch(VehicleUpdate vehicleUpdate) {

    boolean isCompleteMatch = true;

    if (boundingBox != null) {
      isCompleteMatch = isCompleteMatch & boundingBox.contains(vehicleUpdate.getLocation());
    }
    if (isCompleteMatch && serviceJourneyId != null) {
      isCompleteMatch = isCompleteMatch & matches(serviceJourneyId, vehicleUpdate.getServiceJourneyId());
    }
    if (isCompleteMatch && operator != null) {
      isCompleteMatch = isCompleteMatch & matches(operator, vehicleUpdate.getOperator());
    }
    if (isCompleteMatch && codespaceId != null) {
      isCompleteMatch = isCompleteMatch & matches(codespaceId, vehicleUpdate.getCodespaceId());
    }
    if (isCompleteMatch && mode != null) {
      isCompleteMatch = isCompleteMatch & matches(mode, vehicleUpdate.getMode());
    }
    if (isCompleteMatch && vehicleId != null) {
      isCompleteMatch = isCompleteMatch & matches(vehicleId, vehicleUpdate.getVehicleId());
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
        .add("codespaceId='" + codespaceId + "'")
        .add("operator='" + operator + "'")
        .add("line=" + line)
        .add("serviceJourneyId='" + serviceJourneyId + "'")
        .add("vehicleId='" + vehicleId + "'")
        .add("boundingBox=" + boundingBox)
        .add("mode=" + mode)
        .toString();
  }
}
