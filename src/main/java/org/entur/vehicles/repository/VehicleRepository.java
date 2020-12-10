package org.entur.vehicles.repository;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.protobuf.Timestamp;
import org.entur.vehicles.data.*;
import org.entur.vehicles.graphql.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import uk.org.siri.www.siri.VehicleActivityStructure;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class VehicleRepository {

  private static final Logger LOG = LoggerFactory.getLogger(VehicleRepository.class);
  private final PrometheusMetricsService metricsService;

  Map<VehicleKey, VehicleUpdate> vehicles = Maps.newConcurrentMap();

  private VehicleUpdateRxPublisher publisher;

  private long lastPurgeTimestamp = System.currentTimeMillis();
  private long minimumPurgeIntervalMillis = 5000;

  final ZoneId zone;

  @Value("${vehicle.updates.max.validity.minutes:1440}") // Default one day
  private long MAX_VALIDITY_TIME_MINUTES;

  public VehicleRepository(@Autowired PrometheusMetricsService metricsService) {
    this.metricsService = metricsService;
    zone = ZonedDateTime.now().getZone();
  }

  public int addAll(List<VehicleActivityStructure> vehicleList) {

    int addedCounter = 0;
    for (VehicleActivityStructure vehicleActivity : vehicleList) {
      VehicleUpdate v = new VehicleUpdate();

      try {
        final VehicleActivityStructure.MonitoredVehicleJourneyType journey = vehicleActivity.getMonitoredVehicleJourney();

        if (!journey.hasVehicleLocation()) {
          // No location set - ignoring
          continue;
        }

        v.setLocation(new Location(journey.getVehicleLocation().getLongitude(),
            journey.getVehicleLocation().getLatitude()
        ));

        v.setLine(new Line(journey.getLineRef().getValue(), buildLineName(journey)));

        v.setCodespaceId(journey.getDataSource());

        if (journey.hasLocationRecordedAtTime()) {
          v.setLastUpdated(convert(journey.getLocationRecordedAtTime()));
        } else if (vehicleActivity.hasRecordedAtTime()) {
          v.setLastUpdated(convert(vehicleActivity.getRecordedAtTime()));
        }
        else {
          v.setLastUpdated(ZonedDateTime.now());
        }

        v.setMonitored(journey.getMonitored());

        v.setBearing(Float.valueOf(journey.getBearing()).doubleValue());
        v.setSpeed(Float.valueOf(journey.getVelocity()).doubleValue());

        if (journey.getVehicleModeCount() > 0) {
          v.setMode(VehicleModeEnumeration.fromValue(journey.getVehicleMode(0)));
        } else {
          if (journey.getOperatorRef() != null) {
            v.setMode(resolveModeByOperator(journey.getOperatorRef().getValue()));
          }
        }
        v.setServiceJourneyId(journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef());

        v.setDirection(journey.getDirectionRef().getValue());

        v.setOccupancy(journey.getOccupancy().name());

        if (journey.getDelay() != null) {
          v.setDelay(journey.getDelay().getSeconds());
        }

        if (vehicleActivity.hasValidUntilTime()) {
          final ZonedDateTime expiration = convert(vehicleActivity.getValidUntilTime());

          if (expiration.isAfter(ZonedDateTime.now().plusMinutes(MAX_VALIDITY_TIME_MINUTES))) {
            v.setExpiration(ZonedDateTime.now().plusMinutes(MAX_VALIDITY_TIME_MINUTES));
          } else {
            v.setExpiration(expiration);
          }
        } else {
          v.setExpiration(ZonedDateTime.now().plusMinutes(10));
        }

        if (journey.getVehicleRef() != null) {
          String vehicleRef = journey.getVehicleRef().getValue();
          v.setVehicleId(vehicleRef);

        }
        vehicles.put(new VehicleKey(v.getCodespaceId(), v.getVehicleId()), v);
        publisher.publishUpdate(v);

        metricsService.markUpdate(1, v.getCodespaceId());
      }
      catch (RuntimeException e) {
        LOG.warn("Update ignored.", e);
      }
    }

    return addedCounter;
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

  private String buildLineName(VehicleActivityStructure.MonitoredVehicleJourneyType journey) {

    String originName;
    String destinationName;

    if (journey.getOriginNameCount() > 0) {
      originName = journey.getOriginName(0).getValue();
    } else {
      originName = " - - - ";
    }

    if (journey.getDestinationNameCount() > 0) {
      destinationName = journey.getDestinationName(0).getValue();
    } else {
      destinationName = " - - - ";
    }

    return originName + " => " + destinationName;
  }

  private ZonedDateTime convert(Timestamp timestamp) {
    ZonedDateTime time = ZonedDateTime.ofInstant(
        Instant.ofEpochSecond(timestamp.getSeconds()),
        ZoneId.of("UTC")
    ).withZoneSameInstant(zone);
    time = time.plusNanos(timestamp.getNanos());
    return time;
  }

  public Collection<VehicleUpdate> getVehicles(VehicleUpdateFilter filter) {

    long before = System.currentTimeMillis();
    if (before - lastPurgeTimestamp > minimumPurgeIntervalMillis) {

      int sizeBefore = vehicles.size();
      final boolean vehicledRemoved = vehicles.entrySet().removeIf(vehicleUpdate -> vehicleUpdate.getValue()
          .getExpiration()
          .isBefore(ZonedDateTime.now()));

      if (vehicledRemoved) {
        LOG.info("Removed {} expired vehicles", sizeBefore-vehicles.size());
      }

      long purgeCompleted = System.currentTimeMillis();

      lastPurgeTimestamp = purgeCompleted;

      if (purgeCompleted - before > 20) {
        LOG.warn("Removing expired vehicles took {} ms", (purgeCompleted - before));
      }
    }

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

  public Set<Line> getLines(String codespace) {
    return vehicles.values()
            .stream()
            .filter(vehicleUpdate -> codespace == null || vehicleUpdate.getCodespaceId().equals(codespace))
            .map(vehicleUpdate -> vehicleUpdate.getLine())
            .collect(Collectors.toSet());

  }

  public Set<Codespace> getCodespaces() {
    return vehicles.values()
        .stream()
        .map(vehicleUpdate -> new Codespace(vehicleUpdate.getCodespaceId()))
        .collect(Collectors.toSet());
  }

  static class VehicleKey {
    String codespace, vehicleRef;

    public VehicleKey(String codespace, String vehicleRef) {
      this.codespace = codespace;
      this.vehicleRef = vehicleRef;
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
      return Objects.hashCode(codespace, vehicleRef);
    }
  }
}
