package org.entur.vehicles.repository;

import com.google.common.collect.Sets;
import com.google.protobuf.Timestamp;
import org.entur.vehicles.data.Location;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.graphql.VehicleUpdateRxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import uk.org.siri.www.siri.VehicleActivityStructure;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

@Repository
public class VehicleRepository {

  private static final Logger LOG = LoggerFactory.getLogger(VehicleRepository.class);

  Set<VehicleUpdate> vehicles = Sets.newConcurrentHashSet();

  private VehicleUpdateRxPublisher publisher;

  private long lastPurgeTimestamp = System.currentTimeMillis();
  private long minimumPurgeIntervalMillis = 5000;

  public void addAll(List<VehicleActivityStructure> vehicleList) {

        for (VehicleActivityStructure vehicleActivity : vehicleList) {
          VehicleUpdate v = new VehicleUpdate();

          try {
            final VehicleActivityStructure.MonitoredVehicleJourneyType journey = vehicleActivity
                .getMonitoredVehicleJourney();

            v.setLineRef(journey.getLineRef().getValue());
            v.setCodespaceId(journey.getDataSource());

            if (journey.hasLocationRecordedAtTime()) {
              v.setLastUpdated(convert(journey.getLocationRecordedAtTime()));
            } else if (vehicleActivity.hasRecordedAtTime()) {
              v.setLastUpdated(convert(journey.getLocationRecordedAtTime()));
            } else {
              v.setLastUpdated(ZonedDateTime.now());
            }
            v.setHeading(Float.valueOf(journey.getBearing()).doubleValue());
            v.setSpeed(Float.valueOf(journey.getVelocity()).doubleValue());
            v.setLocation(new Location(journey.getVehicleLocation().getLongitude(), journey.getVehicleLocation().getLatitude()));

            if (journey.getVehicleModeCount() > 0) {
              v.setMode(journey.getVehicleMode(0).name());
            }
            v.setServiceJourneyId(journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef());

            v.setDirection(journey.getDirectionRef().getValue());

            v.setOccupancy(journey.getOccupancy().name());

            if (vehicleActivity.hasValidUntilTime()) {
              v.setExpiration(convert(vehicleActivity.getValidUntilTime()));
            } else {
              v.setExpiration(ZonedDateTime.now().plusMinutes(10));
            }


            if (journey.getVehicleRef() != null) {
              String vehicleRef = journey.getVehicleRef().getValue();
              v.setVehicleId(vehicleRef);

              vehicles.add(v);

              publisher.publishUpdate(v);

            }

          } catch (RuntimeException e) {
            LOG.warn("Update ignored.", e);
          }
        }
  }

  public Set<VehicleUpdate> getVehicles(VehicleUpdateFilter filter) {

    long before = System.currentTimeMillis();
    if (before - lastPurgeTimestamp > minimumPurgeIntervalMillis) {

      vehicles.removeIf(vehicleUpdate -> vehicleUpdate.getExpiration().isBefore(ZonedDateTime.now()));
      long purgeCompleted = System.currentTimeMillis();

      lastPurgeTimestamp = purgeCompleted;

      if (purgeCompleted - before > 20) {
        LOG.warn("Removing expired vehicles took {} ms", (purgeCompleted - before));
      }
    }

    return Sets.filter(vehicles, vehicleUpdate -> filter.isMatch(vehicleUpdate));
  }

  public void addUpdateListener(VehicleUpdateRxPublisher publisher) {
    this.publisher = publisher;
  }

  private ZonedDateTime convert(Timestamp timestamp) {
    ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp.getSeconds()), ZoneId.of("UTC"));
    time = time.plusNanos(timestamp.getNanos());
    return time;
  }
}