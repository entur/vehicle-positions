package org.entur.vehicles.data;


import org.entur.vehicles.data.model.BoundingBox;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ObjectRef;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.ServiceJourneyIdAndDate;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.OperatorService;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

import static org.entur.vehicles.data.MetricType.UNDEFINED;

@SchemaMapping
public class VehicleUpdateFilter extends AbstractVehicleUpdate {

  private PrometheusMetricsService metricsService;
  private BoundingBox boundingBox;

  private int bufferSize;
  private int bufferTimeMillis;

  private Set<String> vehicleIds;
  private Set<DatedServiceJourney> datedServiceJourneyIds;
  private Set<ServiceJourney> serviceJourneys;

  private ZonedDateTime maxDataAge;

  private MetricType metricType = UNDEFINED;

  public VehicleUpdateFilter (
          PrometheusMetricsService metricsService, MetricType metricType,
          Set<ServiceJourneyIdAndDate> serviceJourneyIdAndDates, Set<String> datedServiceJourneyIds, String operatorRef,
      String codespaceId, VehicleModeEnumeration mode, Set<String> vehicleIds,
      String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox, Duration maxDataAge
  ) {
    this(metricsService, metricType, serviceJourneyIdAndDates, datedServiceJourneyIds, operatorRef, codespaceId, mode, vehicleIds, lineRef,
            lineName, monitored, boundingBox, maxDataAge, null, null);
  }

  public VehicleUpdateFilter(PrometheusMetricsService metricsService, MetricType metricType,
          Set<ServiceJourneyIdAndDate> serviceJourneyIdAndDates, Set<String> datedServiceJourneyIds, String operatorRef,
      String codespaceId, VehicleModeEnumeration mode, Set<String> vehicleIds,
      String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox, Duration maxDataAge,
                             Integer bufferSize, Integer bufferTimeMillis
  ) {
    this.metricsService = metricsService;
    if (serviceJourneyIdAndDates != null) {
      this.serviceJourneys = new HashSet<>();
      for (ServiceJourneyIdAndDate idAndDate : serviceJourneyIdAndDates) {
        String serviceJourneyId = idAndDate.getId();
        String date = idAndDate.getDate();

        if (date != null) {
          this.serviceJourneys.add(new ServiceJourney(serviceJourneyId, date));
        } else {
          this.serviceJourneys.add(new ServiceJourney(serviceJourneyId));
        }
      }
    }
    if (datedServiceJourneyIds != null) {
      this.datedServiceJourneyIds = new HashSet<>();
      for (String datedServiceJourneyId : datedServiceJourneyIds) {
        this.datedServiceJourneyIds.add(new DatedServiceJourney(datedServiceJourneyId));
      }
    }
    if (operatorRef != null) {
      this.operator = OperatorService.getOperator(operatorRef);
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

    if (maxDataAge != null) {
      this.maxDataAge = ZonedDateTime.now().minus(maxDataAge);
    }

    if (bufferSize != null) {
      this.bufferSize = bufferSize;
    }
    if (bufferTimeMillis != null) {
      this.bufferTimeMillis = bufferTimeMillis;
    }
    if (metricType != null) {
      this.metricType = metricType;
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
    if (isCompleteMatch && serviceJourneys != null) {
      if (vehicleUpdate.getDatedServiceJourney() == null) {
        isCompleteMatch = isCompleteMatch & matches(serviceJourneys, vehicleUpdate.getServiceJourney());

      } else {
        isCompleteMatch = isCompleteMatch & (
                        matches(serviceJourneys, vehicleUpdate.getServiceJourney())
        );
      }
    }
    if (isCompleteMatch && datedServiceJourneyIds != null) {
      isCompleteMatch = isCompleteMatch & (
              matches(datedServiceJourneyIds, vehicleUpdate.getDatedServiceJourney())
      );
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
    if (isCompleteMatch && maxDataAge != null) {
      isCompleteMatch = isCompleteMatch & vehicleUpdate.getLastUpdated().isAfter(maxDataAge);
    }

    if (metricsService != null && isCompleteMatch) {
      metricsService.markFilterMatch(vehicleUpdate.getCodespace(), metricType);
    }
    return isCompleteMatch;
  }

  private boolean matches(Set<DatedServiceJourney> datedServiceJourneyIds, DatedServiceJourney targetId) {
    if (targetId != null) {
      for (DatedServiceJourney datedServiceJourneyId : datedServiceJourneyIds) {
        if (datedServiceJourneyId.matches(targetId) || datedServiceJourneyId.matches(targetId.getServiceJourney())) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean matches(Set<ServiceJourney> serviceJourneyIds, ServiceJourney target) {
    for (ServiceJourney serviceJourney : serviceJourneyIds) {
      if (serviceJourney.matches(target)) {
        return true;
      }
    }
    return false;
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
        .add("serviceJourneyIds='" + serviceJourneys + "'")
        .add("vehicleIds='" + vehicleIds + "'")
        .add("boundingBox=" + boundingBox)
        .add("mode=" + mode)
        .add("bufferSize=" + bufferSize)
        .add("bufferTime=" + bufferTimeMillis)
        .toString();
  }
}
