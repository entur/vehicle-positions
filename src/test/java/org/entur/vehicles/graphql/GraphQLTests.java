package org.entur.vehicles.graphql;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingMap;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GraphQLTests {

    @Autowired
    VehicleRepository repository;

    Query queryService;

    @BeforeEach
    public void initData() {
        PrometheusMetricsService metricsService = new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        repository = new VehicleRepository(
                metricsService,
                new LineService(false),
                new ServiceJourneyService(false),
                new AutoPurgingMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                        180
        );
        repository.addUpdateListener(new VehicleUpdateRxPublisher(repository));
        queryService = new Query(repository, metricsService);

        VehicleActivityRecord vehicleActivityRecord = new VehicleActivityRecord();
        vehicleActivityRecord.setRecordedAtTime(ZonedDateTime.now().toString());
        vehicleActivityRecord.setValidUntilTime(ZonedDateTime.now().plusMinutes(10).toString());

        MonitoredVehicleJourneyRecord monitoredVehicleJourney = new MonitoredVehicleJourneyRecord();
        monitoredVehicleJourney.setLineRef("TST:Line:123");

        FramedVehicleJourneyRefRecord framedVehicleJourneyRef = new FramedVehicleJourneyRefRecord();
        framedVehicleJourneyRef.setDataFrameRef("2020-12-15");
        framedVehicleJourneyRef.setDatedVehicleJourneyRef("TST:ServiceJourney:1234567890");
        monitoredVehicleJourney.setFramedVehicleJourneyRef(framedVehicleJourneyRef);

        monitoredVehicleJourney.setMonitored(true);
        monitoredVehicleJourney.setDataSource("TST");

        LocationRecord vehicleLocation = new LocationRecord();
        vehicleLocation.setLongitude(10.910261);
        vehicleLocation.setLatitude(59.09739);
        monitoredVehicleJourney.setVehicleLocation(vehicleLocation);

        vehicleActivityRecord.setMonitoredVehicleJourney(monitoredVehicleJourney);

        repository.addAll(List.of(vehicleActivityRecord));
    }

    @Test
    public void testQueries() {

        // Codespaces
        final List<Codespace> codespaces = queryService.codespaces();
        assertFalse(codespaces.isEmpty());
        final Codespace codespace = codespaces.get(0);
        assertEquals("TST", codespace.getCodespaceId());

        // Lines
        List<Line> lines = queryService.lines(null);
        assertFalse(lines.isEmpty());
        assertEquals("TST:Line:123", lines.get(0).getLineRef());

        lines = queryService.lines("BAH");
        assertTrue(lines.isEmpty());

        lines = queryService.lines("TST");
        assertFalse(lines.isEmpty());
        assertEquals("TST:Line:123", lines.get(0).getLineRef());

        // ServiceJourneys
        List<ServiceJourney> serviceJourneys = queryService.serviceJourneys(null);
        assertFalse(serviceJourneys.isEmpty());
        assertEquals("TST:ServiceJourney:1234567890", serviceJourneys.get(0).getServiceJourneyId());

        serviceJourneys = queryService.serviceJourneys("BAH:Line:321");
        assertTrue(serviceJourneys.isEmpty());

        serviceJourneys = queryService.serviceJourneys("TST:Line:123");
        assertFalse(serviceJourneys.isEmpty());
        assertEquals("TST:ServiceJourney:1234567890", serviceJourneys.get(0).getServiceJourneyId());
    }
}
