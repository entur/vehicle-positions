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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * Pub/Sub delivers on several executor threads, and every update for one vehicle mutates the
     * same stored VehicleUpdate. Two updates racing - one valid, one at 0,0 - must never leave the
     * flag disagreeing with the location that ends up stored.
     */
    @Test
    public void concurrentUpdatesOfOneVehicleNeverLeaveTheFlagDisagreeingWithTheLocation() throws Exception {
        InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0");
        VehicleRepository repository = repository("0.0/0.0,-1.0/-1.0,1.0/1.0");
        VehicleActivityRecord valid = position(59.91, 10.75, T0);
        VehicleActivityRecord invalid = position(0.0, 0.0, T0);
        repository.add(valid);

        int rounds = 20_000;
        // The checker takes part in both barriers, so it never reads while a round is being written
        CyclicBarrier start = new CyclicBarrier(3);
        CyclicBarrier end = new CyclicBarrier(3);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[2];
        for (int t = 0; t < 2; t++) {
            int id = t;
            threads[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        start.await();
                        // Alternate which thread writes the invalid position, so either can win
                        repository.add(((i + id) % 2 == 0) ? valid : invalid);
                        end.await();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads[t].start();
        }

        int mismatches = 0;
        for (int i = 0; i < rounds; i++) {
            start.await();
            end.await();
            VehicleUpdate stored = stored(repository);
            if (stored.isLocationInvalid() != registry.isInvalid(stored.getLocation())) {
                mismatches++;
            }
        }
        for (Thread thread : threads) {
            thread.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(0, mismatches,
                "rounds where the stored flag disagreed with the stored location, out of " + rounds);
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
