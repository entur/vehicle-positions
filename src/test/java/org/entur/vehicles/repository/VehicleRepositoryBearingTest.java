package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Producers that report no bearing get one calculated from the previous position to the current.
 */
public class VehicleRepositoryBearingTest {

    private static final double DELTA = 0.01;

    /** Oslo S, give or take. */
    private static final double LAT = 59.91;
    private static final double LON = 10.75;

    /** 0.001° of latitude is 111 m; 0.0001° is 11 m. */
    private static final double HUNDRED_METERS_NORTH = LAT + 0.001;

    private static final ZonedDateTime T0 = ZonedDateTime.parse("2026-09-14T10:00:00+02:00");

    private VehicleRepository repository;

    @BeforeEach
    public void init() {
        repository = repository(true);
    }

    private static VehicleRepository repository(boolean bearingCalculationEnabled) {
        return new VehicleRepository(
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)),
                new LineService(PlannedDataService.disabled()),
                Mockito.mock(ServiceJourneyService.class),
                new AutoPurgingVehicleMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                1440,
                bearingCalculationEnabled,
                new VehicleUpdateRxPublisher()
        );
    }

    private static VehicleActivityRecord position(double lat, double lon, ZonedDateTime recordedAt, Float bearing) {
        LocationRecord location = new LocationRecord();
        location.setLatitude(lat);
        location.setLongitude(lon);

        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setVehicleRef("TST:Vehicle:1");
        journey.setVehicleLocation(location);
        journey.setLocationRecordedAtTime(recordedAt.toString());
        journey.setBearing(bearing);

        VehicleActivityRecord activity = new VehicleActivityRecord();
        activity.setMonitoredVehicleJourney(journey);
        activity.setRecordedAtTime(recordedAt.toString());
        activity.setValidUntilTime(recordedAt.plusMinutes(10).toString());
        return activity;
    }

    private static VehicleActivityRecord position(double lat, double lon, ZonedDateTime recordedAt) {
        return position(lat, lon, recordedAt, null);
    }

    private VehicleUpdate stored() {
        return repository.getVehicles(null).iterator().next();
    }

    @Test
    public void testFirstPositionHasNoBearing() {
        repository.add(position(LAT, LON, T0));

        assertNull(stored().getBearing());
    }

    @Test
    public void testBearingIsCalculatedFromPreviousPosition() {
        repository.add(position(LAT, LON, T0));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));

        assertEquals(0, stored().getBearing(), DELTA);
    }

    @Test
    public void testBearingFollowsTheLatestMovement() {
        repository.add(position(LAT, LON, T0));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));
        repository.add(position(LAT, LON, T0.plusSeconds(40)));

        assertEquals(180, stored().getBearing(), DELTA);
    }

    @Test
    public void testNoBearingIsCalculatedWhenDisabled() {
        repository = repository(false);
        repository.add(position(LAT, LON, T0));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));

        assertNull(stored().getBearing());
    }

    @Test
    public void testReportedBearingIsNeverReplaced() {
        repository.add(position(LAT, LON, T0, 90f));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20), 45f));

        assertEquals(45, stored().getBearing(), DELTA);
    }

    @Test
    public void testSmallMovementKeepsPreviousBearing() {
        repository.add(position(LAT, LON, T0));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));
        // GPS jitter of 11 m south while standing at a stop
        repository.add(position(HUNDRED_METERS_NORTH - 0.0001, LON, T0.plusSeconds(40)));

        assertEquals(0, stored().getBearing(), DELTA);
    }

    @Test
    public void testOutOfOrderPositionKeepsPreviousBearing() {
        repository.add(position(LAT, LON, T0));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));
        // A delayed message recorded before the one already stored
        repository.add(position(LAT, LON, T0.plusSeconds(10)));

        assertEquals(0, stored().getBearing(), DELTA);
    }

    @Test
    public void testSmallMovementAfterLongGapKeepsPreviousBearing() {
        repository.add(position(LAT, LON, T0));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));
        // Parked at a stop, reported by a producer that sends less often than every three minutes
        repository.add(position(HUNDRED_METERS_NORTH - 0.0001, LON, T0.plusMinutes(10)));

        assertEquals(0, stored().getBearing(), DELTA);
    }

    @Test
    public void testReportedBearingSurvivesLongGapWithoutBearing() {
        repository.add(position(LAT, LON, T0, 90f));
        // Only bearings this service calculated are cleared; a reported one stays as it always has
        repository.add(position(HUNDRED_METERS_NORTH, LON + 0.01, T0.plusMinutes(10)));

        assertEquals(90, stored().getBearing(), DELTA);
    }

    @Test
    public void testPositionWithoutCoordinatesIsIgnoredAndLaterUpdatesStillApply() {
        repository.add(position(LAT, LON, T0));

        VehicleActivityRecord noCoordinates = position(LAT, LON, T0.plusSeconds(10));
        noCoordinates.getMonitoredVehicleJourney().getVehicleLocation().setLatitude(null);
        repository.add(noCoordinates);

        assertEquals(LAT, stored().getLocation().getLatitude(), DELTA);

        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));

        assertEquals(HUNDRED_METERS_NORTH, stored().getLocation().getLatitude(), DELTA);
        assertEquals(0, stored().getBearing(), DELTA);
        assertTrue(stored().getLastUpdated().isEqual(T0.plusSeconds(20)));
    }

    @Test
    public void testLongGapBetweenPositionsClearsTheBearing() {
        repository.add(position(LAT, LON, T0));
        repository.add(position(HUNDRED_METERS_NORTH, LON, T0.plusSeconds(20)));
        repository.add(position(HUNDRED_METERS_NORTH, LON + 0.01, T0.plusMinutes(10)));

        assertNull(stored().getBearing());
    }
}
