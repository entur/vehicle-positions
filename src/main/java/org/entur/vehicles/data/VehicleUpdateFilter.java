package org.entur.vehicles.data;


import org.entur.vehicles.data.model.BoundingBox;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ObjectRef;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.Set;
import java.util.StringJoiner;

@SchemaMapping
public class VehicleUpdateFilter extends AbstractVehicleUpdate {

  private BoundingBox boundingBox;

  private int bufferSize;
  private int bufferTimeMillis;

  private Set<String> vehicleIds;

  public VehicleUpdateFilter (
      String serviceJourneyId, String date, String datedServiceJourneyId, String operatorRef,
      String codespaceId, VehicleModeEnumeration mode, Set<String> vehicleIds,
      String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox
  ) {
    this(serviceJourneyId, date, datedServiceJourneyId, operatorRef, codespaceId, mode, vehicleIds, lineRef, lineName, monitored, boundingBox, null, null);
  }

  public VehicleUpdateFilter(
      String serviceJourneyId,  String date, String datedServiceJourneyId, String operatorRef,
      String codespaceId, VehicleModeEnumeration mode, Set<String> vehicleIds,
      String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox, Integer bufferSize, Integer bufferTimeMillis
  ) {
    if (serviceJourneyId != null) {
      if (date != null) {
        this.serviceJourney = new ServiceJourney(serviceJourneyId, date);
      } else {
        this.serviceJourney = new ServiceJourney(serviceJourneyId);
      }
    }
    if (datedServiceJourneyId != null) {
      this.datedServiceJourney = new DatedServiceJourney(datedServiceJourneyId);
    }
    if (operatorRef != null) {
      this.operator = Operator.getOperator(operatorRef);
    }
    if (codespaceId != null) {
      this.codespace = Codespace.getCodespace(codespaceId);
    }
    this.mode = mode;
    this.vehicleIds = vehicleIds;
    if (lineRef != null | lineName != null) {
      this.line = new Line(lineRef, lineName);
    }
    this.monitored = monitored;
    this.boundingBox = boundingBox;
    if (bufferSize != null) {
      this.bufferSize = bufferSize;
    }
    if (bufferTimeMillis != null) {
      this.bufferTimeMillis = bufferTimeMillis;
    }
  }

  public int getBufferSize() {
    return bufferSize;
  }

  public int getBufferTimeMillis() {
    return bufferTimeMillis;
  }

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
      if (vehicleUpdate.getDatedServiceJourney() == null) {
        isCompleteMatch = isCompleteMatch & matches(serviceJourney, vehicleUpdate.getServiceJourney());
        if (serviceJourney.getDate() != null) {
          isCompleteMatch = isCompleteMatch & matches(serviceJourney.getDate(), vehicleUpdate.getServiceJourney().getDate());
        }
        isCompleteMatch = isCompleteMatch & matches(serviceJourney, vehicleUpdate.getServiceJourney());
      } else {
        isCompleteMatch = isCompleteMatch & (
                        matches(serviceJourney, vehicleUpdate.getDatedServiceJourney()) ||
                        matches(serviceJourney, vehicleUpdate.getDatedServiceJourney().getServiceJourney())
        );
      }
    }
    if (isCompleteMatch && datedServiceJourney != null && vehicleUpdate.getDatedServiceJourney() != null) {
      isCompleteMatch = isCompleteMatch & matches(datedServiceJourney, vehicleUpdate.getDatedServiceJourney());
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
    if (isCompleteMatch && vehicleIds != null) {
      isCompleteMatch = isCompleteMatch & matches(vehicleIds, vehicleUpdate.getVehicleId());
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

  private boolean matches(ObjectRef identifiedObj, ObjectRef objectRef_2) {
    return identifiedObj.matches(objectRef_2);
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

  private boolean matches(Set<String> template, String value) {

    if (template != null) {

      if (value == null) {
        // If a template-value is set, null-values does not match
        return false;
      }
      return template.contains(value);
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
        .add("vehicleIds='" + vehicleIds + "'")
        .add("boundingBox=" + boundingBox)
        .add("mode=" + mode)
        .add("bufferSize=" + bufferSize)
        .add("bufferTime=" + bufferTimeMillis)
        .toString();
  }
}
