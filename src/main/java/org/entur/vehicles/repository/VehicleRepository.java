package org.entur.vehicles.repository;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.ObjectRef;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.graphql.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Repository
public class VehicleRepository {

  private static final Logger LOG = LoggerFactory.getLogger(VehicleRepository.class);
  private final PrometheusMetricsService metricsService;

  AutoPurgingMap vehicles = new AutoPurgingMap(5);

  private LineService lineService;

  private ServiceJourneyService serviceJourneyService;

  private VehicleUpdateRxPublisher publisher;

  final ZoneId zone;

  private long maxValidityInMinutes;

  public VehicleRepository(
          @Autowired PrometheusMetricsService metricsService,
          @Autowired LineService lineService,
          @Autowired ServiceJourneyService serviceJourneyService,
          @Value("${vehicle.updates.max.validity.minutes}") long maxValidityInMinutes) {
    this.metricsService = metricsService;
    this.lineService = lineService;
    this.serviceJourneyService = serviceJourneyService;
    this.maxValidityInMinutes = maxValidityInMinutes;
    zone = ZonedDateTime.now().getZone();
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

      final VehicleKey key = new VehicleKey(codespace, vehicleRef);

      final VehicleUpdate v = vehicles.getOrDefault(key, new VehicleUpdate());

      v.setCodespace(codespace);

      v.setVehicleRef(vehicleRef);

      if (v.getLocation() != null) {
        v.getLocation().setLongitude(journey.getVehicleLocation().getLongitude());
        v.getLocation().setLatitude(journey.getVehicleLocation().getLatitude());
      } else {
        v.setLocation(new Location(
            journey.getVehicleLocation().getLongitude(),
            journey.getVehicleLocation().getLatitude()
        ));
      }

      if (journey.getLineRef() != null) {
        String lineRef = journey.getLineRef().toString();
        try {
          v.setLine(lineService.getLine(lineRef));
        } catch (ExecutionException e) {
          v.setLine(new Line(lineRef));
        }
      }

      String serviceJourneyId = null;
      if (journey.getFramedVehicleJourneyRef() != null &&
              journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef() != null) {
        serviceJourneyId = journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef().toString();
      } else if (journey.getVehicleJourneyRef() != null) {
        serviceJourneyId = journey.getVehicleJourneyRef().toString();
      }

      if (serviceJourneyId != null) {
        try {
          v.setServiceJourney(serviceJourneyService.getServiceJourney(serviceJourneyId));
        } catch (ExecutionException e) {
          v.setServiceJourney(new ServiceJourney(serviceJourneyId));
        }
      }

      if (journey.getLocationRecordedAtTime() != null) {
        v.setLastUpdated(convert(journey.getLocationRecordedAtTime()));
      } else if (vehicleActivity.getRecordedAtTime() != null) {
        v.setLastUpdated(convert(vehicleActivity.getRecordedAtTime()));
      }
      else {
        v.setLastUpdated(ZonedDateTime.now());
      }

      v.setMonitored(journey.getMonitored());

      if (journey.getBearing() != null) {
        v.setBearing(Float.valueOf(journey.getBearing()).doubleValue());
      }
      if (journey.getVelocity() != null) {
        v.setSpeed(Integer.valueOf(journey.getVelocity()).doubleValue());
      }

      if (journey.getVehicleModes() != null && journey.getVehicleModes().size() > 0) {
        v.setMode(VehicleModeEnumeration.fromValue(journey.getVehicleModes().get(0)));
      } else if (journey.getOperatorRef() != null) {
          v.setMode(resolveModeByOperator(journey.getOperatorRef().toString()));
      } else {
        v.setMode(VehicleModeEnumeration.BUS);
      }

      if (journey.getOperatorRef() != null) {
        v.setOperator(Operator.getOperator(journey.getOperatorRef().toString()));
      }

      if (journey.getDirectionRef() != null) {
        v.setDirection( journey.getDirectionRef().toString());
      }

      if (journey.getOccupancy() != null) {
        v.setOccupancy(journey.getOccupancy().name());
      }

      if (journey.getDelay() != null) {
        v.setDelay(Duration.parse(journey.getDelay()).getSeconds());
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

      vehicles.put(key, v);
      publisher.publishUpdate(v);

      metricsService.markUpdate(1, v.getCodespace());
    }
    catch (RuntimeException e) {
      LOG.warn("Update ignored.", e);
    }
  }

  private VehicleModeEnumeration resolveModeByOperator(String operator) {
    switch (operator) {
      case "Sporvognsdrift":
        return VehicleModeEnumeration.TRAM;
      case "Tide_sjø_AS":
        return VehicleModeEnumeration.FERRY;
    }
    return VehicleModeEnumeration.BUS;
   }

  private ZonedDateTime convert(CharSequence timestamp) {
    return ZonedDateTime.parse(timestamp);
  }

  public Collection<VehicleUpdate> getVehicles(VehicleUpdateFilter filter) {

    final long filteringStart = System.currentTimeMillis();
    final Map<VehicleKey, VehicleUpdate> vehicleUpdates = Maps.filterValues(vehicles, vehicleUpdate -> filter.isMatch(vehicleUpdate));
    final long filteringDone = System.currentTimeMillis();

    if (filteringDone - filteringStart > 50) {
      LOG.info("Filtering vehicles took {} ms", (filteringDone - filteringStart));
    }

    return vehicleUpdates.values();
  }

  public void addUpdateListener(VehicleUpdateRxPublisher publisher) {
    this.publisher = publisher;
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
        .distinct()
        .sorted(Comparator.comparing(Operator::getOperatorRef))
        .collect(Collectors.toList());
  }

  public List<ServiceJourney> getServiceJourneys(String lineRef) {
    return vehicles.values()
        .stream()
        .filter(vehicleUpdate -> lineRef == null || isMatch(vehicleUpdate.getLine(), lineRef))
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
            .filter(vehicleUpdate -> isMatch(vehicleUpdate.getServiceJourney(), id))
            .map(vehicleUpdate -> vehicleUpdate.getServiceJourney())
            .findAny();
    if (serviceJourney.isEmpty()) {
      return null;
    } else {
      return serviceJourney.get();
    }
  }

  static class VehicleKey {
    Codespace codespace;
    String vehicleRef;
    int hashCode = -1;

    public VehicleKey(Codespace codespace, String vehicleRef) {
      this.codespace = codespace;
      this.vehicleRef = vehicleRef;
      hashCode = Objects.hashCode(codespace, vehicleRef);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof VehicleKey)) return false;
      VehicleKey that = (VehicleKey) o;
      return Objects.equal(codespace, that.codespace) &&
          Objects.equal(vehicleRef, that.vehicleRef);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
