package org.entur.vehicles.repository;

import com.google.common.collect.Maps;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.OccupancyEnumeration;
import org.entur.vehicles.data.OccupancyStatus;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleStatusEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.MonitoredCall;
import org.entur.vehicles.data.model.ObjectRef;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ProgressBetweenStops;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.helpers.Util;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.OperatorService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.entur.vehicles.repository.helpers.Util.containsValues;
import static org.entur.vehicles.repository.helpers.Util.convert;

@Repository
public class VehicleRepository {

  private static final Logger LOG = LoggerFactory.getLogger(VehicleRepository.class);
  private final PrometheusMetricsService metricsService;

  private final AutoPurgingVehicleMap vehicles;

  private final LineService lineService;

  private final ServiceJourneyService serviceJourneyService;

  private VehicleUpdateRxPublisher publisher;

  private final long maxValidityInMinutes;

  public VehicleRepository(
          @Autowired PrometheusMetricsService metricsService,
          @Autowired LineService lineService,
          @Autowired ServiceJourneyService serviceJourneyService,
          @Autowired AutoPurgingVehicleMap vehicles,
          @Value("${vehicle.updates.max.validity.minutes}") long maxValidityInMinutes,
          @Autowired VehicleUpdateRxPublisher publisher) {
    this.metricsService = metricsService;
    this.lineService = lineService;
    this.serviceJourneyService = serviceJourneyService;
    this.vehicles = vehicles;
    this.maxValidityInMinutes = maxValidityInMinutes;
    this.publisher = publisher;
    this.publisher.setRepository(this);
  }

  public void addAll(List<VehicleActivityRecord> vehicleList) {
    for (VehicleActivityRecord vehicleActivity : vehicleList) {
      add(vehicleActivity);
    }
  }

  public void add(VehicleActivityRecord vehicleActivity) {
    try {
      MonitoredVehicleJourneyRecord journey = vehicleActivity.getMonitoredVehicleJourney();

      if (journey.getVehicleLocation() == null) {
        // No location set - ignoring
        return;
      }

      final Codespace codespace = Codespace.getCodespace(journey.getDataSource().toString());

      String vehicleRef = null;
      if (journey.getVehicleRef() != null) {
        vehicleRef = journey.getVehicleRef().toString();
      }
      String lineRef = null;
      if (journey.getLineRef() != null) {
        lineRef = journey.getLineRef().toString();
      }

      String serviceJourneyId = null;
      String datedServiceJourneyId = null;
      String date = null;
      if (journey.getFramedVehicleJourneyRef() != null &&
              journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef() != null) {
        serviceJourneyId = journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef().toString();
        date = journey.getFramedVehicleJourneyRef().getDataFrameRef().toString();
      }
      if (journey.getVehicleJourneyRef() != null) {
        datedServiceJourneyId = journey.getVehicleJourneyRef().toString();
      }

      final StorageKey key = new StorageKey(codespace, vehicleRef, lineRef, serviceJourneyId, datedServiceJourneyId);

      final VehicleUpdate v = vehicles.getOrDefault(key, new VehicleUpdate());

      v.setCodespace(codespace);

      v.setVehicleId(vehicleRef);

      if (v.getLocation() != null) {
        v.getLocation().setLongitude(journey.getVehicleLocation().getLongitude());
        v.getLocation().setLatitude(journey.getVehicleLocation().getLatitude());
      } else {
        v.setLocation(new Location(
                journey.getVehicleLocation().getLongitude(),
                journey.getVehicleLocation().getLatitude()
        ));
      }

      if (vehicleActivity.getProgressBetweenStops() != null) {
        if (vehicleActivity.getProgressBetweenStops().getLinkDistance() != null &&
                vehicleActivity.getProgressBetweenStops().getPercentage() != null
        ) {
          ProgressBetweenStops progressBetweenStops = new ProgressBetweenStops(
                  vehicleActivity.getProgressBetweenStops().getLinkDistance(),
                  vehicleActivity.getProgressBetweenStops().getPercentage()
          );
          v.setProgressBetweenStops(progressBetweenStops);
        }
      }

      if (lineRef != null) {
        try {
          v.setLine(lineService.getLine(lineRef));
        } catch (ExecutionException e) {
          v.setLine(new Line(lineRef));
        }
      } else {
        v.setLine(Line.DEFAULT);
      }

      if (serviceJourneyId != null) {
        try {
          ServiceJourney serviceJourney = serviceJourneyService.getServiceJourney(serviceJourneyId);
          if (serviceJourney != null) {
            serviceJourney.setDate(date);
            v.setServiceJourney(serviceJourney);
          }
        } catch (ExecutionException e) {
          v.setServiceJourney(new ServiceJourney(serviceJourneyId, date));
        }
      }
      if (datedServiceJourneyId != null) {
        try {
          DatedServiceJourney datedServiceJourney = serviceJourneyService.getDatedServiceJourney(datedServiceJourneyId);
          if (datedServiceJourney != null) {
            if (v.getServiceJourney() != null) {
              datedServiceJourney.setServiceJourney(v.getServiceJourney());
            }
            v.setDatedServiceJourney(datedServiceJourney);
          }
        } catch (ExecutionException e) {
          v.setDatedServiceJourney(new DatedServiceJourney(datedServiceJourneyId, new ServiceJourney(datedServiceJourneyId)));
        }
      }

      if (journey.getLocationRecordedAtTime() != null) {
        v.setLastUpdated(convert(journey.getLocationRecordedAtTime()));
      } else if (vehicleActivity.getRecordedAtTime() != null) {
        v.setLastUpdated(convert(vehicleActivity.getRecordedAtTime()));
      } else {
        v.setLastUpdated(ZonedDateTime.now());
      }

      v.setMonitored(journey.getMonitored() != null ? journey.getMonitored():true);


      if (journey.getBearing() != null) {
        v.setBearing(journey.getBearing().doubleValue());
      }
      if (journey.getVelocity() != null) {
        v.setSpeed(journey.getVelocity().doubleValue());
      }

      CharSequence operatorRef = journey.getOperatorRef();

      if (containsValues(journey.getVehicleModes())) {
        v.setMode(VehicleModeEnumeration.fromValue( journey.getVehicleModes().get(0).toString()));
      } else if (operatorRef != null) {
          v.setMode(Util.resolveModeByOperator(operatorRef.toString()));
      } else {
        v.setMode(VehicleModeEnumeration.BUS);
      }

      if (operatorRef != null) {
        v.setOperator(OperatorService.getOperator(operatorRef.toString()));
      }else {
        v.setOperator(Operator.DEFAULT);
      }

      if (journey.getDirectionRef() != null) {
        v.setDirection( journey.getDirectionRef().toString());
      }

      if (containsValues(journey.getOriginNames())) {
        v.setOriginName(journey.getOriginNames().get(0).getValue().toString());
      }

      if (journey.getOriginRef() != null) {
        v.setOriginRef(journey.getOriginRef().toString());
      }

      if (containsValues(journey.getDestinationNames())) {
        v.setDestinationName(journey.getDestinationNames().get(0).getValue().toString());
      }

      if (journey.getDestinationRef() != null) {
        v.setDestinationRef(journey.getDestinationRef().toString());
      }

      if (journey.getOccupancy() != null) {
        v.setOccupancy(OccupancyEnumeration.fromValue(journey.getOccupancy().toString()));
        v.setOccupancyStatus(OccupancyStatus.fromValue(journey.getOccupancy().toString()));
      } else {
        v.setOccupancy(OccupancyEnumeration.UNKNOWN);
        v.setOccupancyStatus(OccupancyStatus.noData);
      }

      if (journey.getInCongestion() != null) {
        v.setInCongestion(journey.getInCongestion());
      }

      if (journey.getDelay() != null) {
        v.setDelay(Duration.parse(journey.getDelay()).getSeconds());
      }

      if (journey.getMonitoredCall() != null) {
        MonitoredCall monitoredCall = new MonitoredCall();
        if (journey.getMonitoredCall().getStopPointRef() != null) {
          monitoredCall.setStopPointRef(journey.getMonitoredCall().getStopPointRef().toString());
        }
        if (journey.getMonitoredCall().getOrder() != null) {
          monitoredCall.setOrder(journey.getMonitoredCall().getOrder());
        }
        if (journey.getMonitoredCall().getVehicleAtStop() != null) {
          monitoredCall.setVehicleAtStop(journey.getMonitoredCall().getVehicleAtStop());
        }
        v.setMonitoredCall(monitoredCall);
      }

      if (vehicleActivity.getValidUntilTime() != null) {
        final ZonedDateTime expiration = convert(vehicleActivity.getValidUntilTime());

        if (expiration.isAfter(ZonedDateTime.now().plusMinutes(maxValidityInMinutes))) {
          v.setExpiration(ZonedDateTime.now().plusMinutes(maxValidityInMinutes));
        } else {
          v.setExpiration(expiration);
        }
      } else {
        v.setExpiration(ZonedDateTime.now().plusMinutes(10));
      }

      if (journey.getVehicleStatus() != null) {
        v.setVehicleStatus(VehicleStatusEnumeration.fromValue(journey.getVehicleStatus().toString()));
      }

      vehicles.put(key, v);
      publisher.publishUpdate(v);

      metricsService.markVehicleUpdate(1, v.getCodespace());
    }
    catch (RuntimeException e) {
      LOG.warn("Update ignored.", e);
    }
  }

  public Collection<VehicleUpdate> getVehicles(QueryFilter filter) {

    if (filter != null) {
      final long filteringStart = System.currentTimeMillis();

      final Map<StorageKey, VehicleUpdate> vehicleUpdates = Maps.filterValues(vehicles, filter::isMatch);
      final long filteringDone = System.currentTimeMillis();

      if (filteringDone - filteringStart > 50) {
        LOG.info("Filtering vehicles took {} ms", (filteringDone - filteringStart));
      }
      return vehicleUpdates.values();
    }

    return vehicles.values();
  }

  public List<Line> getLines(String codespace) {
    return vehicles.values()
            .stream()
            .filter(vehicleUpdate -> codespace == null || vehicleUpdate.getCodespace().getCodespaceId().equals(codespace))
            .map(vehicleUpdate -> vehicleUpdate.getLine())
            .distinct()
            .sorted(Comparator.comparing(Line::getLineRef))
            .collect(Collectors.toList());

  }

  public List<Codespace> getCodespaces() {
    return vehicles.values()
        .stream()
        .map(vehicleUpdate -> vehicleUpdate.getCodespace())
        .distinct()
        .sorted(Comparator.comparing(Codespace::getCodespaceId))
        .collect(Collectors.toList());
  }

  public List<Operator> getOperators(String codespace) {
    return vehicles.values()
        .stream()
        .filter(vehicleUpdate -> codespace == null || isMatch(vehicleUpdate.getCodespace(), codespace))
        .map(vehicleUpdate -> vehicleUpdate.getOperator())
        .filter(operator -> operator != null && !operator.getOperatorRef().isEmpty())
        .distinct()
        .sorted(Comparator.comparing(Operator::getOperatorRef))
        .collect(Collectors.toList());
  }

  public List<ServiceJourney> getServiceJourneys(String lineRef, String codespaceId) {
    return vehicles.values()
        .stream()
        .filter(vehicleUpdate ->
                (lineRef == null || isMatch(vehicleUpdate.getLine(), lineRef) ) &&
                        (codespaceId == null || isMatch(vehicleUpdate.getCodespace(), codespaceId))
        )
        .map(vehicleUpdate -> vehicleUpdate.getServiceJourney())
        .distinct()
        .sorted(Comparator.comparing(ServiceJourney::getServiceJourneyId))
        .collect(Collectors.toList());
  }

  private boolean isMatch(ObjectRef obj, String ref){
    if (obj == null) return false;
    return obj.getRef().matches(ref);
  }

  public ServiceJourney getServiceJourney(String id) {
    Optional<ServiceJourney> serviceJourney = vehicles.values()
            .stream()
            .filter(vehicleUpdate ->
                    isMatch(vehicleUpdate.getServiceJourney(), id) ||
                    isMatch(vehicleUpdate.getDatedServiceJourney(), id))
            .map(vehicleUpdate -> vehicleUpdate.getServiceJourney())
            .findAny();
      return serviceJourney.orElse(null);
  }
}
