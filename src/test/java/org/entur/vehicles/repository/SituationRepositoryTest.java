package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationRepositoryTest {

    private SituationRepository repository;

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
}
