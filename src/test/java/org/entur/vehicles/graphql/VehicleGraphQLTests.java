package org.entur.vehicles.graphql;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingVehicleMap;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.InvalidLocationRegistry;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.planned.PlannedDataService;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
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
                new LineService(PlannedDataService.disabled()),
                serviceJourneyService,
                new AutoPurgingVehicleMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                        180,
                publisher
        );
        publisher = new VehicleUpdateRxPublisher();
        PlannedDataService plannedDataService = Mockito.mock(PlannedDataService.class);
        Mockito.when(plannedDataService.current()).thenReturn(plannedDataset());
        queryService = new Query(repository, null, null, new NSRService(false, null), metricsService,
                new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0"), plannedDataService);

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

    /**
     * The catalogue queries answer from planned data, not from live vehicles: the dataset
     * below declares the two lines the vehicles run on plus one nobody is driving.
     */
    private static PlannedDataset plannedDataset() {
        return new PlannedDataset.Builder()
                .addOperator("TST:Operator:1", "Test")
                .addLine("TST:Line:123", "One two three", "123")
                .addLine("DSJ:Line:321", "Three two one", "321")
                .addLine("TST:Line:idle", "Nobody drives this", "0")
                .addJourneyPattern("JP", List.of())
                .addServiceJourney("TST:ServiceJourney:1234567890", "JP", "TST:Line:123")
                .addServiceJourney("DSJ:ServiceJourney:1234567890", "JP", "DSJ:Line:321")
                .addServiceJourney("TST:ServiceJourney:idle", "JP", "TST:Line:idle")
                .addOperatingDay("DSJ:OperatingDay:1", "2020-12-15")
                .addDatedServiceJourney("DSJ:DatedServiceJourney:1234567890", "DSJ:ServiceJourney:1234567890", "DSJ:OperatingDay:1")
                .build();
    }

    @Test
    public void catalogueQueriesAnswerFromPlannedData() {
        final List<Codespace> codespaces = queryService.codespaces();
        assertEquals(List.of("DSJ", "TST"), codespaces.stream().map(Codespace::getCodespaceId).toList());

        List<Line> lines = queryService.lines(null);
        assertEquals(List.of("DSJ:Line:321", "TST:Line:123", "TST:Line:idle"),
                lines.stream().map(Line::getLineRef).toList());
        assertTrue(queryService.lines("BAH").isEmpty());
        assertEquals("TST:Line:123", queryService.lines("TST").get(0).getLineRef());
        assertEquals("DSJ:Line:321", queryService.lines("DSJ").get(0).getLineRef());

        assertEquals("TST:Operator:1", queryService.operators(null).get(0).getOperatorRef());
        assertTrue(queryService.operators("DSJ").isEmpty());

        List<ServiceJourney> serviceJourneys = queryService.serviceJourneys(null, "TST:Line:123", null);
        assertEquals(List.of("TST:ServiceJourney:1234567890"),
                serviceJourneys.stream().map(ServiceJourney::getId).toList());
        assertTrue(queryService.serviceJourneys(null, "BAH:Line:321", null).isEmpty());
        assertEquals(2, queryService.serviceJourneys(null, null, "TST").size());

        ServiceJourney byDatedId = queryService.serviceJourney("DSJ:DatedServiceJourney:1234567890");
        ServiceJourney byId = queryService.serviceJourney("DSJ:ServiceJourney:1234567890");
        assertEquals(byId, byDatedId);
        assertEquals("2020-12-15", byDatedId.getDate());
        assertNull(queryService.serviceJourney("TST:ServiceJourney:unknown"));

        List<ServiceJourney> byIds = queryService.serviceJourneys(
                List.of("TST:ServiceJourney:unknown", "DSJ:DatedServiceJourney:1234567890", "TST:ServiceJourney:1234567890"),
                null, null);
        assertEquals(List.of("DSJ:ServiceJourney:1234567890", "TST:ServiceJourney:1234567890"),
                byIds.stream().map(ServiceJourney::getId).toList());
        assertEquals("2020-12-15", byIds.get(0).getDate());
        assertEquals(List.of("TST:ServiceJourney:1234567890"),
                queryService.serviceJourneys(List.of("DSJ:ServiceJourney:1234567890", "TST:ServiceJourney:1234567890"), null, "TST")
                        .stream().map(ServiceJourney::getId).toList());
        assertTrue(queryService.serviceJourneys(List.of(), null, null).isEmpty());

        DatedServiceJourney dated = queryService.datedServiceJourney("DSJ:DatedServiceJourney:1234567890");
        assertEquals("DSJ:DatedServiceJourney:1234567890", dated.getId());
        assertEquals("2020-12-15", dated.getOperatingDay());
        assertEquals("DSJ:ServiceJourney:1234567890", dated.getServiceJourney().getId());
        assertNull(queryService.datedServiceJourney("DSJ:DatedServiceJourney:unknown"));
        assertNull(queryService.datedServiceJourney("DSJ:ServiceJourney:1234567890"));

        assertEquals(List.of("DSJ:DatedServiceJourney:1234567890"),
                queryService.datedServiceJourneys(List.of("DSJ:DatedServiceJourney:unknown", "DSJ:DatedServiceJourney:1234567890"))
                        .stream().map(DatedServiceJourney::getId).toList());
        assertTrue(queryService.datedServiceJourneys(List.of()).isEmpty());
    }

    @Test
    public void serviceJourneysRequiresAFilter() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> queryService.serviceJourneys(null, null, null));
        assertTrue(e.getMessage().contains("lineRef"), e.getMessage());
    }

    private VehicleActivityRecord createVehicleActivity(String codespace, String vehicleRef, String lineRef,
                                                        double latitude, double longitude) {
        VehicleActivityRecord record = new VehicleActivityRecord();
        record.setRecordedAtTime(ZonedDateTime.now().toString());
        record.setValidUntilTime(ZonedDateTime.now().plusMinutes(10).toString());

        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setLineRef(lineRef);
        journey.setVehicleRef(vehicleRef);
        journey.setMonitored(true);
        journey.setDataSource(codespace);

        LocationRecord location = new LocationRecord();
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        journey.setVehicleLocation(location);

        record.setMonitoredVehicleJourney(journey);
        return record;
    }

    private Collection<VehicleUpdate> queryVehicles(String codespaceId, Boolean includeInvalidLocations) {
        return queryService.getVehicles(
                null,   // serviceJourneyId
                null,   // date
                null,   // serviceJourneyIdAndDates
                null,   // datedServiceJourneyId
                null,   // datedServiceJourneyIds
                null,   // operatorRef
                codespaceId,
                null,   // mode
                null,   // vehicleId
                null,   // vehicleIds
                null,   // lineRef
                null,   // lineName
                null,   // monitored
                null,   // boundingBox
                null,   // maxDataAge
                includeInvalidLocations
        );
    }

    @Test
    public void testInvalidLocationsAreExcludedUnlessRequested() {
        repository.addAll(List.of(
                createVehicleActivity("INV", "INV:Vehicle:valid", "INV:Line:1", 59.911491, 10.757933),
                createVehicleActivity("INV", "INV:Vehicle:invalid", "INV:Line:1", 0.0, 0.0)
        ));

        // Argument omitted by the client - GraphQL applies the schema default of false
        Collection<VehicleUpdate> defaultResult = queryVehicles("INV", null);
        assertEquals(1, defaultResult.size());
        assertEquals("INV:Vehicle:valid", defaultResult.iterator().next().getVehicleId());

        Collection<VehicleUpdate> explicitlyExcluded = queryVehicles("INV", false);
        assertEquals(1, explicitlyExcluded.size());

        Collection<VehicleUpdate> included = queryVehicles("INV", true);
        assertEquals(2, included.size());
        assertTrue(included.stream().anyMatch(v -> "INV:Vehicle:invalid".equals(v.getVehicleId())));
    }
}
