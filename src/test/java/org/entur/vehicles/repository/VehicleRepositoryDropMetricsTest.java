package org.entur.vehicles.repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.InvalidLocationRegistry;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every VM update the repository throws away is counted by reason, so silent loss shows up in
 * Prometheus instead of only as a WARN log line.
 */
class VehicleRepositoryDropMetricsTest {

    private PrometheusMeterRegistry registry;
    private VehicleRepository repository;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMetricsService metrics = new PrometheusMetricsService(registry);
        repository = new VehicleRepository(
                metrics,
                new LineService(PlannedDataService.disabled()),
                Mockito.mock(ServiceJourneyService.class),
                new AutoPurgingVehicleMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                1440,
                false,
                new VehicleUpdateRxPublisher(metrics),
                new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0")
        );
    }

    private static VehicleActivityRecord vehicle(LocationRecord location) {
        ZonedDateTime now = ZonedDateTime.now();
        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setVehicleRef("TST:Vehicle:1");
        journey.setVehicleLocation(location);
        journey.setLocationRecordedAtTime(now.toString());

        VehicleActivityRecord activity = new VehicleActivityRecord();
        activity.setMonitoredVehicleJourney(journey);
        activity.setRecordedAtTime(now.toString());
        activity.setValidUntilTime(now.plusMinutes(10).toString());
        return activity;
    }

    private static LocationRecord oslo() {
        LocationRecord location = new LocationRecord();
        location.setLatitude(59.91);
        location.setLongitude(10.75);
        return location;
    }

    private double dropped(String reason) {
        Counter counter = registry.find("app.vehicles.dropped").tags("dataType", "vehicle", "reason", reason).counter();
        assertNotNull(counter, reason + " counter not registered");
        return counter.count();
    }

    @Test
    void updateWithoutLocationIsCountedAsDropped() {
        repository.add(vehicle(null));

        assertEquals(1, dropped("no_location"));
        assertTrue(repository.getVehicles(null).isEmpty());
    }

    @Test
    void updateThatFailsMappingIsCountedAsDropped() {
        VehicleActivityRecord activity = vehicle(oslo());
        activity.getMonitoredVehicleJourney().setDelay("not-a-duration");

        repository.add(activity);

        assertEquals(1, dropped("mapping_error"));
    }
}
