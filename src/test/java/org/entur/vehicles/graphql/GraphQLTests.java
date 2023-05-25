package org.entur.vehicles.graphql;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.converter.jaxb2avro.Jaxb2AvroConverter;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.org.siri.siri21.DataFrameRefStructure;
import uk.org.siri.siri21.FramedVehicleJourneyRefStructure;
import uk.org.siri.siri21.LineRef;
import uk.org.siri.siri21.LocationStructure;
import uk.org.siri.siri21.VehicleActivityStructure;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Arrays;
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
        repository = new VehicleRepository(
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)),
                new LineService(false),
                new ServiceJourneyService(),
                180
        );
        repository.addUpdateListener(new VehicleUpdateRxPublisher(repository));
        queryService = new Query(repository);

        VehicleActivityStructure vm = new VehicleActivityStructure();
        vm.setRecordedAtTime(ZonedDateTime.now());
        vm.setValidUntilTime(ZonedDateTime.now().plusMinutes(10));
        VehicleActivityStructure.MonitoredVehicleJourney monitoredVehicleJourney = new VehicleActivityStructure.MonitoredVehicleJourney();
            LineRef lineRef = new LineRef();
            lineRef.setValue("TST:Line:123");
        monitoredVehicleJourney.setLineRef(lineRef);
            FramedVehicleJourneyRefStructure framedVehicleJourneyRef = new FramedVehicleJourneyRefStructure();
            DataFrameRefStructure dataFrameRef = new DataFrameRefStructure();
            dataFrameRef.setValue("2020-12-15");
            framedVehicleJourneyRef.setDataFrameRef(dataFrameRef);
            framedVehicleJourneyRef.setDatedVehicleJourneyRef("TST:ServiceJourney:1234567890");
            monitoredVehicleJourney.setFramedVehicleJourneyRef(framedVehicleJourneyRef);
            monitoredVehicleJourney.setMonitored(true);
            monitoredVehicleJourney.setDataSource("TST");
        LocationStructure vehicleLocation = new LocationStructure();
        vehicleLocation.setLongitude(BigDecimal.valueOf(10.910261));
        vehicleLocation.setLatitude(BigDecimal.valueOf(59.09739));
        monitoredVehicleJourney.setVehicleLocation(vehicleLocation);
        vm.setMonitoredVehicleJourney(monitoredVehicleJourney);
        VehicleActivityRecord vehicleActivityRecord = Jaxb2AvroConverter.convert(vm);

        repository.addAll(Arrays.asList(vehicleActivityRecord));
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
