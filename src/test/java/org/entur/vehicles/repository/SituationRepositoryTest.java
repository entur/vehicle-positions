package org.entur.vehicles.repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationRepositoryTest {

    private SituationRepository repository;
    private SituationUpdateRxPublisher publisher;
    private PrometheusMeterRegistry registry;

    @BeforeEach
    public void init() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMetricsService metricsService = new PrometheusMetricsService(registry);

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));

        publisher = new SituationUpdateRxPublisher();
        // A real republisher, never started: onSituationChanged() only accumulates pending
        // refs and releases a semaphore permit, which is harmless with no worker thread
        // running to consume it - these tests exercise storage, not republishing.
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new EstimatedTimetableUpdateRxPublisher(),
                100,
                Duration.ofMillis(50));
        repository = new SituationRepository(
                metricsService,
                new SituationMapper(new LineService(false), nsrService),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                publisher,
                republisher
        );
    }

    private PtSituationElementRecord record(String situationNumber, Integer version, String progress) {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().toString());
        record.setReportType("general");
        record.setVersion(version);
        record.setProgress(progress);
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        return record;
    }

    private SituationFilter allSituations() {
        return new SituationFilter(null, MetricType.QUERY, null, null, null, null, null, null, null,
                null, null, null, null, null, null, true, null, null);
    }

    @Test
    public void testAddAndRetrieve() {
        repository.addAll(List.of(record("TST:SituationNumber:1", 1, "PUBLISHED")));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals("TST:SituationNumber:1", situations.iterator().next().getSituationNumber());
    }

    @Test
    public void testSameSituationNumberReplacesPreviousVersion() {
        repository.add(record("TST:SituationNumber:1", 1, "PUBLISHED"));
        repository.add(record("TST:SituationNumber:1", 2, "PUBLISHED"));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals(2, situations.iterator().next().getVersion());
    }

    @Test
    public void testOlderVersionIsIgnored() {
        repository.add(record("TST:SituationNumber:1", 5, "PUBLISHED"));
        repository.add(record("TST:SituationNumber:1", 2, "PUBLISHED"));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals(5, situations.iterator().next().getVersion());
    }

    @Test
    public void testNullVersionIsAlwaysAccepted() {
        repository.add(record("TST:SituationNumber:1", 5, "PUBLISHED"));
        repository.add(record("TST:SituationNumber:1", null, "CLOSED"));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals(WorkflowStatusEnumeration.closed, situations.iterator().next().getProgress());
    }

    @Test
    public void testUnmappableRecordIsIgnoredWithoutThrowing() {
        PtSituationElementRecord broken = record("no-codespace-here", 1, "PUBLISHED");
        broken.setParticipantRef(null);

        repository.add(broken);

        assertTrue(repository.getSituations(null).isEmpty());
    }

    @Test
    public void testFilterIsApplied() {
        repository.addAll(List.of(
                record("TST:SituationNumber:1", 1, "PUBLISHED"),
                record("TST:SituationNumber:2", 1, "CLOSED")));

        assertEquals(2, repository.getSituations(allSituations()).size());

        SituationFilter excludingClosed = new SituationFilter(null, MetricType.QUERY, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false, null, null);
        assertEquals(1, repository.getSituations(excludingClosed).size());
    }

    @Test
    public void testAcceptedUpdateIsPublishedToSubscribers() throws InterruptedException {
        // Small buffer size so a single published update flushes immediately rather
        // than waiting out the default 250ms/20-item buffer window.
        SituationFilter subscription = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, true, 1, 50);

        List<List<SituationUpdate>> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch emitted = new CountDownLatch(1);

        Disposable subscription1 = publisher.getPublisher(subscription, "uuid").subscribe(batch -> {
            received.add(batch);
            emitted.countDown();
        });
        try {
            repository.add(record("TST:SituationNumber:1", 1, "PUBLISHED"));

            assertTrue(emitted.await(2, TimeUnit.SECONDS), "Expected the accepted update to be published");
            assertEquals(1, received.size());
            assertEquals(1, received.get(0).size());
            assertEquals("TST:SituationNumber:1", received.get(0).get(0).getSituationNumber());
        } finally {
            subscription1.dispose();
        }
    }

    @Test
    public void testVersionRejectedUpdateEmitsNothing() throws InterruptedException {
        repository.add(record("TST:SituationNumber:1", 5, "PUBLISHED"));

        SituationFilter subscription = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, true, 1, 50);

        List<List<SituationUpdate>> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch initialSnapshotEmitted = new CountDownLatch(1);

        Disposable subscription1 = publisher.getPublisher(subscription, "uuid").subscribe(batch -> {
            received.add(batch);
            initialSnapshotEmitted.countDown();
        });
        try {
            // Initial snapshot carries the already-stored v5 situation.
            assertTrue(initialSnapshotEmitted.await(2, TimeUnit.SECONDS), "Expected the initial snapshot to be published");
            assertEquals(1, received.size());
            assertEquals(5, received.get(0).get(0).getVersion());

            repository.add(record("TST:SituationNumber:1", 2, "PUBLISHED")); // rejected - older version

            Thread.sleep(300);
            assertEquals(1, received.size(), "No further emission expected for a rejected update");
        } finally {
            subscription1.dispose();
        }
    }

    @Test
    public void testMetricIsRecordedOnAcceptedUpdateOnlyAndNotOnRejectedUpdate() {
        repository.add(record("TST:SituationNumber:1", 5, "PUBLISHED"));
        assertEquals(1.0, situationMetricCount());

        repository.add(record("TST:SituationNumber:1", 2, "PUBLISHED")); // rejected - older version
        assertEquals(1.0, situationMetricCount(), "Metric must not increment for a rejected update");
    }

    private double situationMetricCount() {
        Counter counter = registry.find("app.vehicles.situation.data").tag("codespaceId", "TST").counter();
        return counter != null ? counter.count() : 0.0;
    }
}
