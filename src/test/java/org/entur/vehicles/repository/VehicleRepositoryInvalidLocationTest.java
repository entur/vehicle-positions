package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.InvalidLocationRegistry;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a vehicle's location is one of the known-invalid coordinates is decided once, when the
 * position is stored, instead of per vehicle on every query and subscription update. The flag must
 * follow every new position of the same vehicle.
 */
public class VehicleRepositoryInvalidLocationTest {

    private static final ZonedDateTime T0 = ZonedDateTime.parse("2026-10-23T10:00:00+02:00");

    private static VehicleRepository repository(String invalidLocations) {
        return new VehicleRepository(
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)),
                new LineService(PlannedDataService.disabled()),
                Mockito.mock(ServiceJourneyService.class),
                new AutoPurgingVehicleMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                1440,
                false,
                new VehicleUpdateRxPublisher(),
                new InvalidLocationRegistry(invalidLocations)
        );
    }

    private static VehicleActivityRecord position(double lat, double lon, ZonedDateTime recordedAt) {
        LocationRecord location = new LocationRecord();
        location.setLatitude(lat);
        location.setLongitude(lon);

        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setVehicleRef("TST:Vehicle:1");
        journey.setVehicleLocation(location);
        journey.setLocationRecordedAtTime(recordedAt.toString());

        VehicleActivityRecord activity = new VehicleActivityRecord();
        activity.setMonitoredVehicleJourney(journey);
        activity.setRecordedAtTime(recordedAt.toString());
        activity.setValidUntilTime(recordedAt.plusMinutes(10).toString());
        return activity;
    }

    private static VehicleUpdate stored(VehicleRepository repository) {
        return repository.getVehicles(null).iterator().next();
    }

    @Test
    public void aPositionAtAnInvalidCoordinateIsFlagged() {
        VehicleRepository repository = repository("0.0/0.0,-1.0/-1.0,1.0/1.0");
        repository.add(position(0.0, 0.0, T0));

        assertTrue(stored(repository).isLocationInvalid());
    }

    @Test
    public void aRealPositionIsNotFlagged() {
        VehicleRepository repository = repository("0.0/0.0,-1.0/-1.0,1.0/1.0");
        repository.add(position(59.91, 10.75, T0));

        assertFalse(stored(repository).isLocationInvalid());
    }

    @Test
    public void theFlagFollowsEachNewPositionOfTheSameVehicle() {
        VehicleRepository repository = repository("0.0/0.0,-1.0/-1.0,1.0/1.0");

        repository.add(position(0.0, 0.0, T0));
        assertTrue(stored(repository).isLocationInvalid());

        repository.add(position(59.91, 10.75, T0.plusSeconds(20)));
        assertFalse(stored(repository).isLocationInvalid());

        repository.add(position(-1.0, -1.0, T0.plusSeconds(40)));
        assertTrue(stored(repository).isLocationInvalid());
    }

    @Test
    public void theConfiguredCoordinatesDecide() {
        VehicleRepository repository = repository("12.5/13.5");

        repository.add(position(0.0, 0.0, T0));
        assertFalse(stored(repository).isLocationInvalid());

        repository.add(position(12.5, 13.5, T0.plusSeconds(20)));
        assertTrue(stored(repository).isLocationInvalid());
    }
}
