package org.entur.vehicles.repository;

import com.google.common.base.Objects;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.protobuf.Timestamp;
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
import org.entur.vehicles.service.JourneyPlannerGraphQLClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import uk.org.siri.www.siri.VehicleActivityStructure;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Repository
public class VehicleRepository {

  private static final Logger LOG = LoggerFactory.getLogger(VehicleRepository.class);
  private final PrometheusMetricsService metricsService;

  AutoPurgingMap vehicles = new AutoPurgingMap(5);

  @Autowired
  JourneyPlannerGraphQLClient graphQLClient;

  private LoadingCache<String, Line> lineCache = CacheBuilder.newBuilder()
      .expireAfterWrite(24, TimeUnit.HOURS)
      .build(new CacheLoader<>() {
        @Override
        public Line load(String lineRef) throws Exception {
          return graphQLClient.getLine(lineRef);
        }
      });

  @PostConstruct
  private void warmUpLineCache() {
    try {
      final List<Line> allLines = graphQLClient.getAllLines();
      for (Line line : allLines) {
        lineCache.put(line.getLineRef(), line);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private VehicleUpdateRxPublisher publisher;

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


      try {
        final VehicleActivityStructure.MonitoredVehicleJourneyType journey = vehicleActivity.getMonitoredVehicleJourney();

        if (!journey.hasVehicleLocation()) {
          // No location set - ignoring
          continue;
        }

        final Codespace codespace = Codespace.getCodespace(journey.getDataSource());

        String vehicleRef = null;
        if (journey.getVehicleRef() != null) {
          vehicleRef = journey.getVehicleRef().getValue();
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

        try {
          v.setLine(lineCache.get(journey.getLineRef().getValue()));
        } catch (ExecutionException e) {
          v.setLine(new Line(journey.getLineRef().getValue()));
        }

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

        if (journey.getOperatorRef() != null) {
          v.setOperator(Operator.getOperator(journey.getOperatorRef().getValue()));
        }

        v.setServiceJourney(ServiceJourney.getServiceJourney(journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef()));

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

        vehicles.put(key, v);
        publisher.publishUpdate(v);

        metricsService.markUpdate(1, v.getCodespace());
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

  private ZonedDateTime convert(Timestamp timestamp) {
    ZonedDateTime time = ZonedDateTime.ofInstant(
        Instant.ofEpochSecond(timestamp.getSeconds()),
        ZoneId.of("UTC")
    ).withZoneSameInstant(zone);
    time = time.plusNanos(timestamp.getNanos());
    return time;
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

  static class VehicleKey {
    Codespace codespace;
    String vehicleRef;

    public VehicleKey(Codespace codespace, String vehicleRef) {
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
