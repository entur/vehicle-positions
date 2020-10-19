package org.entur.vehicles.data;


public class VehicleUpdateFilter extends AbstractVehicleUpdate {

  public VehicleUpdateFilter() { }
  public VehicleUpdateFilter(
      String serviceJourneyId, String operator, String codespaceId, VehicleModeEnumeration mode, String vehicleId,
      String lineRef, BoundingBox boundingBox
  ) {
    this.serviceJourneyId = serviceJourneyId;
    this.operator = operator;
    this.codespaceId = codespaceId;
    this.mode = mode;
    this.vehicleId = vehicleId;
    this.lineRef = lineRef;
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

    if (serviceJourneyId != null) {
      isCompleteMatch = isCompleteMatch & matches(serviceJourneyId, vehicleUpdate.getServiceJourneyId());
    }
    if (operator != null) {
      isCompleteMatch = isCompleteMatch & matches(operator, vehicleUpdate.getOperator());
    }
    if (codespaceId != null) {
      isCompleteMatch = isCompleteMatch & matches(codespaceId, vehicleUpdate.getCodespaceId());
    }
    if (mode != null) {
      isCompleteMatch = isCompleteMatch & matches(mode, vehicleUpdate.getMode());
    }
    if (vehicleId != null) {
      isCompleteMatch = isCompleteMatch & matches(vehicleId, vehicleUpdate.getVehicleId());
    }
    if (lineRef != null) {
      isCompleteMatch = isCompleteMatch & matches(lineRef, vehicleUpdate.getLineRef());
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
}
