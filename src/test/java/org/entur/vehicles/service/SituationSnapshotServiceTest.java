package org.entur.vehicles.service;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingSituationMap;
import org.entur.vehicles.repository.SituationMapper;
import org.entur.vehicles.repository.SituationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationSnapshotServiceTest {

    private SituationRepository repository;
    private SituationSnapshotService snapshotService;

    @BeforeEach
    public void init() {
        PrometheusMetricsService metricsService =
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));

        repository = new SituationRepository(
                metricsService,
                new SituationMapper(new LineService(false), nsrService),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new SituationUpdateRxPublisher()
        );

        snapshotService = new SituationSnapshotService(
                repository, "http://localhost:0/unused", "test", Duration.parse("PT5S"), true);
    }

    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/sx/" + name)) {
            assertNotNull(in, "fixture must be on the test classpath: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Set<String> storedSituationNumbers() {
        Collection<SituationUpdate> stored = repository.getSituations(null);
        return stored.stream().map(SituationUpdate::getSituationNumber).collect(Collectors.toSet());
    }

    @Test
    public void testLoadsEverySituationFromTheSnapshot() throws IOException {
        int loaded = snapshotService.load(fixture("sx-snapshot-response.json"));

        assertEquals(4, loaded);
        assertEquals(
                Set.of("RUT:SituationNumber:823246",
                        "RUT:SituationNumber:2026-64179-1",
                        "SKY:SituationNumber:TX1221961",
                        "RUT:SituationNumber:823380"),
                storedSituationNumbers());
    }

    @Test
    public void testClosedSituationsAreLoadedLikeAnyOther() throws IOException {
        snapshotService.load(fixture("sx-snapshot-response.json"));

        SituationUpdate closed = repository.getSituations(null).stream()
                .filter(s -> "RUT:SituationNumber:2026-64179-1".equals(s.getSituationNumber()))
                .findFirst()
                .orElseThrow();

        assertEquals(org.entur.vehicles.data.WorkflowStatusEnumeration.closed, closed.getProgress());
        assertNotNull(closed.getExpiration(), "a closed situation expires immediately");
    }

    @Test
    public void testOneMalformedSituationDoesNotDiscardTheRest() throws IOException {
        int loaded = snapshotService.load(fixture("sx-snapshot-response-one-malformed.json"));

        assertEquals(3, loaded, "the three well-formed situations must still load");
        assertTrue(storedSituationNumbers().contains("RUT:SituationNumber:823246"));
        assertTrue(storedSituationNumbers().contains("SKY:SituationNumber:TX1221961"));
        assertTrue(storedSituationNumbers().contains("RUT:SituationNumber:823380"));
        assertEquals(3, storedSituationNumbers().size());
    }

    @Test
    public void testAnUnparseableBodyLoadsNothingAndDoesNotThrow() {
        assertEquals(0, snapshotService.load("this is not json"));
        assertTrue(repository.getSituations(null).isEmpty());
    }

    @Test
    public void testAnEmptyDeliveryLoadsNothing() {
        assertEquals(0, snapshotService.load(
                "{\"version\":\"2.1\",\"serviceDelivery\":{\"situationExchangeDeliveries\":[]}}"));
        assertTrue(repository.getSituations(null).isEmpty());
    }

    @Test
    public void testAFailingFetchDoesNotAbortStartup() {
        // The URL points at a port nothing listens on, so the fetch fails.
        SituationSnapshotService failing = new SituationSnapshotService(
                repository, "http://localhost:1/sx", "test", Duration.parse("PT1S"), true);

        failing.loadSnapshot();

        assertTrue(repository.getSituations(null).isEmpty(),
                "a failed snapshot leaves the repository empty rather than throwing");
    }

    @Test
    public void testDisabledServiceDoesNotFetch() {
        // Asserting an empty repository alone is not enough here: a service that ignored the
        // `enabled` guard entirely and simply failed to connect to localhost:1 would also leave
        // the repository empty, so that assertion cannot distinguish "never fetched" from
        // "fetched and failed". Overriding the fetch() seam makes the absence of a fetch directly
        // observable instead of inferring it from a side effect that a broken guard would also
        // produce.
        AtomicBoolean fetchAttempted = new AtomicBoolean(false);
        SituationSnapshotService disabled = new SituationSnapshotService(
                repository, "http://localhost:1/sx", "test", Duration.parse("PT1S"), false) {
            @Override
            protected String fetch() {
                fetchAttempted.set(true);
                throw new AssertionError("fetch() must not be called when the service is disabled");
            }
        };

        disabled.loadSnapshot();

        assertFalse(fetchAttempted.get(), "a disabled service must never attempt a fetch");
        assertTrue(repository.getSituations(null).isEmpty());
    }
}
