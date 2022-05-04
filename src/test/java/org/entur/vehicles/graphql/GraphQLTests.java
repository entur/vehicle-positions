package org.entur.vehicles.graphql;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.org.siri.www.siri.VehicleActivityStructure;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GraphQLTests {

    VehicleRepository repository;

    Query queryService;

    @BeforeEach
    public void initData() {
        repository = new VehicleRepository(
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)),
                new LineService(false),
                new ServiceJourneyService()
        );
        repository.addUpdateListener(new VehicleUpdateRxPublisher(repository));
        queryService = new Query(repository);

        try {
            final VehicleActivityStructure.Builder builder = VehicleActivityStructure.newBuilder();
            JsonFormat.parser().merge(new FileReader("src/test/resources/protobuf/vehicle.json"), builder);
            repository.addAll(Arrays.asList(builder.build()));
        } catch (InvalidProtocolBufferException | FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testQueries() throws IOException {

        initData();

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
