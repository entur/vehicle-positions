package org.entur.vehicles.graphql;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingVehicleMap;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VehicleGraphQLTests {

    VehicleRepository repository;

    Query queryService;
    private VehicleUpdateRxPublisher publisher = new VehicleUpdateRxPublisher();

    private ServiceJourneyService serviceJourneyService = Mockito.mock(ServiceJourneyService.class);

    @BeforeEach
    public void initData() throws ExecutionException {
        PrometheusMetricsService metricsService = new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        repository = new VehicleRepository(
                metricsService,
                new LineService(false),
                serviceJourneyService,
                new AutoPurgingVehicleMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                        180,
                publisher
        );
        publisher = new VehicleUpdateRxPublisher();
        queryService = new Query(repository, null, metricsService);

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

        VehicleActivityRecord dsj_vehicleActivityRecord = new VehicleActivityRecord();
        dsj_vehicleActivityRecord.setRecordedAtTime(ZonedDateTime.now().toString());
        dsj_vehicleActivityRecord.setValidUntilTime(ZonedDateTime.now().plusMinutes(10).toString());

        MonitoredVehicleJourneyRecord dsj_monitoredVehicleJourney = new MonitoredVehicleJourneyRecord();
        dsj_monitoredVehicleJourney.setLineRef("DSJ:Line:321");

        dsj_monitoredVehicleJourney.setVehicleJourneyRef("DSJ:DatedServiceJourney:1234567890");

        dsj_monitoredVehicleJourney.setMonitored(true);
        dsj_monitoredVehicleJourney.setDataSource("DSJ");

        LocationRecord dsj_vehicleLocation = new LocationRecord();
        dsj_vehicleLocation.setLongitude(10.12345);
        dsj_vehicleLocation.setLatitude(59.12345);
        dsj_monitoredVehicleJourney.setVehicleLocation(dsj_vehicleLocation);

        dsj_vehicleActivityRecord.setMonitoredVehicleJourney(dsj_monitoredVehicleJourney);


        Mockito.when(serviceJourneyService.getDatedServiceJourney(
                Mockito.anyString())).thenReturn(new DatedServiceJourney("DSJ:DatedServiceJourney:1234567890",
                new ServiceJourney("DSJ:ServiceJourney:1234567890")));

        Mockito.when(serviceJourneyService.getServiceJourney(
                "TST:ServiceJourney:1234567890")).thenReturn(new ServiceJourney("TST:ServiceJourney:1234567890"));

        Mockito.when(serviceJourneyService.getServiceJourney(
                "DSJ:DatedServiceJourney:1234567890")).thenReturn(new ServiceJourney("DSJ:ServiceJourney:1234567890"));


        repository.addAll(List.of(vehicleActivityRecord, dsj_vehicleActivityRecord));

    }

    @Test
    public void testQueries() {

        // Codespaces
        final List<Codespace> codespaces = queryService.codespaces();
        assertFalse(codespaces.isEmpty());
        assertTrue(codespaces.stream().anyMatch(cs -> cs.getCodespaceId().equals("TST")));
        assertTrue(codespaces.stream().anyMatch(cs -> cs.getCodespaceId().equals("DSJ")));

        // Lines
        List<Line> lines = queryService.lines(null);
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().anyMatch(l -> l.getLineRef().equals("TST:Line:123")));
        assertTrue(lines.stream().anyMatch(l -> l.getLineRef().equals("DSJ:Line:321")));

        lines = queryService.lines("BAH");
        assertTrue(lines.isEmpty());

        lines = queryService.lines("TST");
        assertFalse(lines.isEmpty());
        assertEquals("TST:Line:123", lines.get(0).getLineRef());

        lines = queryService.lines("DSJ");
        assertFalse(lines.isEmpty());
        assertEquals("DSJ:Line:321", lines.get(0).getLineRef());

        // ServiceJourneys
        List<ServiceJourney> serviceJourneys = queryService.serviceJourneys(null, null);
        assertFalse(serviceJourneys.isEmpty());
        assertTrue(serviceJourneys.stream().anyMatch(sj -> sj.getServiceJourneyId().equals("TST:ServiceJourney:1234567890")));
        assertTrue(serviceJourneys.stream().anyMatch(sj -> sj.getServiceJourneyId().equals("DSJ:ServiceJourney:1234567890")));

        serviceJourneys = queryService.serviceJourneys("BAH:Line:321", null);
        assertTrue(serviceJourneys.isEmpty());

        serviceJourneys = queryService.serviceJourneys("TST:Line:123", null);
        assertFalse(serviceJourneys.isEmpty());
        assertEquals("TST:ServiceJourney:1234567890", serviceJourneys.get(0).getServiceJourneyId());

        ServiceJourney serviceJourney1 = queryService.serviceJourney("DSJ:DatedServiceJourney:1234567890");
        ServiceJourney dsj_serviceJourney1 = queryService.serviceJourney("DSJ:ServiceJourney:1234567890");
        assertEquals(serviceJourney1, dsj_serviceJourney1);
    }
}
