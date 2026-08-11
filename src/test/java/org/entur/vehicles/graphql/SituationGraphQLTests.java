package org.entur.vehicles.graphql;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.avro.realtime.siri.model.ValidityPeriodRecord;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.SeverityEnumeration;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingSituationMap;
import org.entur.vehicles.repository.AutoPurgingTimetableMap;
import org.entur.vehicles.repository.SituationMapper;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.repository.SituationTriggeredRepublisher;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationGraphQLTests {

    private Query queryService;
    private SituationRepository repository;
    private SituationUpdateRxPublisher publisher;
    private PrometheusMetricsService metricsService;

    @BeforeEach
    public void initData() {
        metricsService =
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));

        publisher = new SituationUpdateRxPublisher();
        // A real republisher, never started: onSituationChanged() only accumulates pending
        // refs and releases a semaphore permit, which is harmless with no worker thread
        // running to consume it - these tests exercise queries, not republishing.
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(
                metricsService,
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new EstimatedTimetableUpdateRxPublisher(),
                100,
                Duration.ofMillis(50),
                2000,
                nsrService);
        repository = new SituationRepository(
                metricsService,
                new SituationMapper(new LineService(false), nsrService),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                publisher,
                republisher
        );

        repository.addAll(List.of(
                busSituation(),
                openEndedSituation(),
                closedSituation()
        ));

        queryService = new Query(null, null, repository, new NSRService(false, null), metricsService, null);
    }

    private PtSituationElementRecord baseRecord(String situationNumber) {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusDays(60).toString());
        record.setReportType("general");
        record.setVersion(1);
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        return record;
    }

    private PtSituationElementRecord busSituation() {
        PtSituationElementRecord record = baseRecord("TST:SituationNumber:bus");
        record.setProgress("PUBLISHED");
        record.setSeverity("SEVERE");

        TranslatedStringRecord summary = new TranslatedStringRecord();
        summary.setValue("Forsinkelser");
        summary.setLanguage("no");
        record.setSummaries(List.of(summary));

        ValidityPeriodRecord period = new ValidityPeriodRecord();
        period.setStartTime(ZonedDateTime.now().minusHours(1).toString());
        period.setEndTime(ZonedDateTime.now().plusHours(1).toString());
        record.setValidityPeriods(List.of(period));

        AffectedLineRecord line = new AffectedLineRecord();
        line.setLineRef("TST:Line:1");

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setVehicleMode("BUS");
        network.setAffectedLines(List.of(line));

        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef("TST:Quay:1");
        stopPoint.setStopPointNames(List.of());
        stopPoint.setStopConditions(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        affects.setStopPoints(List.of(stopPoint));
        record.setAffects(affects);

        return record;
    }

    private PtSituationElementRecord openEndedSituation() {
        PtSituationElementRecord record = baseRecord("TST:SituationNumber:openended");
        record.setProgress("OPEN");
        record.setSeverity("NORMAL");

        ValidityPeriodRecord period = new ValidityPeriodRecord();
        period.setStartTime(ZonedDateTime.now().minusDays(60).toString());
        period.setEndTime(null);
        record.setValidityPeriods(List.of(period));

        return record;
    }

    private PtSituationElementRecord closedSituation() {
        PtSituationElementRecord record = baseRecord("TST:SituationNumber:closed");
        record.setProgress("CLOSED");
        record.setSeverity("SLIGHT");
        return record;
    }

    @Test
    public void testUnfilteredQueryExcludesClosedSituations() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(2, situations.size());
        assertTrue(situations.stream().noneMatch(s -> s.getSituationNumber().endsWith(":closed")));
    }

    @Test
    public void testIncludeClosed() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, null, null, null, true);

        assertEquals(3, situations.size());
    }

    @Test
    public void testFilterByCodespace() {
        assertEquals(2, queryService.getSituations(
                null, "TST", null, null, null, null, null, null, null, null, null, null, null, null).size());
        assertEquals(0, queryService.getSituations(
                null, "ABC", null, null, null, null, null, null, null, null, null, null, null, null).size());
    }

    @Test
    public void testFilterByLineStopAndMode() {
        assertEquals(1, queryService.getSituations(
                null, null, null, "TST:Line:1", null, null, null, null, null, null, null, null, null, null).size());
        assertEquals(1, queryService.getSituations(
                null, null, null, null, "TST:Quay:1", null, null, null, null, null, null, null, null, null).size());
        assertEquals(1, queryService.getSituations(
                null, null, null, null, null, null, null, VehicleModeEnumeration.BUS, null, null, null, null, null, null).size());
    }

    @Test
    public void testFilterBySeverity() {
        assertEquals(1, queryService.getSituations(
                null, null, null, null, null, null, null, null, SeverityEnumeration.severe, null, null, null, null, null).size());
        assertEquals(0, queryService.getSituations(
                null, null, null, null, null, null, null, null, SeverityEnumeration.verySevere, null, null, null, null, null).size());
    }

    @Test
    public void testFilterBySituationNumbers() {
        assertEquals(1, queryService.getSituations(
                Set.of("TST:SituationNumber:bus"), null, null, null, null, null, null, null, null, null,
                null, null, null, null).size());
    }

    @Test
    public void testQualityToolingQueryFindsLongLivedOpenEndedSituations() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, null, true,
                Duration.ofDays(30), null);

        assertEquals(1, situations.size());
        assertEquals("TST:SituationNumber:openended", situations.iterator().next().getSituationNumber());
        assertTrue(situations.iterator().next().getOpenEnded());
    }

    @Test
    public void testValidNow() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, true, null, null, null);

        assertEquals(2, situations.size());
    }

    /**
     * Exercises the real {@code Query.getSituations} wiring at Query.java:179 rather than
     * calling {@code SituationFilter} directly: {@code stopRef} is resolved via
     * {@code nsrService.expandWithAncestors}, which unions the queried ref with its ancestors.
     * {@code ancestorsOf} is stubbed to its true semantics here too ({@code NSR:StopPlace:451}
     * only, without the ref itself) rather than left unstubbed - Mockito's default answer for
     * an unstubbed {@code Set}-returning method is an empty set, which (via the Finding 3
     * normalisation of an empty {@code stopRefs} to "no filter") would make swapping the call
     * site to {@code ancestorsOf} fail for the wrong reason: every situation in the shared
     * repository coming back, rather than the quay-tagged one specifically going missing.
     * <p>
     * Two situations are tagged: one directly on the queried quay, one only on the stop place
     * above it. Both must come back - that union is exactly what distinguishes
     * {@code expandWithAncestors} from {@code ancestorsOf}, which omits the queried ref itself
     * and would silently drop the quay-tagged situation while still returning the other one.
     */
    @Test
    public void testFilterByStopRefResolvesThroughAncestors() {
        PtSituationElementRecord atQuay = baseRecord("TST:SituationNumber:at-quay");
        atQuay.setProgress("PUBLISHED");
        atQuay.setSeverity("SEVERE");
        atQuay.setAffects(stopPointAffects("NSR:Quay:749"));
        repository.add(atQuay);

        PtSituationElementRecord atAncestor = baseRecord("TST:SituationNumber:at-ancestor");
        atAncestor.setProgress("PUBLISHED");
        atAncestor.setSeverity("SEVERE");
        atAncestor.setAffects(stopPointAffects("NSR:StopPlace:451"));
        repository.add(atAncestor);

        NSRService ancestorAwareNsrService = Mockito.mock(NSRService.class);
        Mockito.when(ancestorAwareNsrService.ancestorsOf("NSR:Quay:749"))
                .thenReturn(Set.of("NSR:StopPlace:451"));
        Mockito.when(ancestorAwareNsrService.expandWithAncestors("NSR:Quay:749"))
                .thenReturn(Set.of("NSR:Quay:749", "NSR:StopPlace:451"));

        Query ancestorQueryService = new Query(null, null, repository, ancestorAwareNsrService, metricsService, null);

        Collection<SituationUpdate> situations = ancestorQueryService.getSituations(
                null, null, null, null, "NSR:Quay:749", null, null, null, null, null, null, null, null, null);

        assertEquals(
                Set.of("TST:SituationNumber:at-quay", "TST:SituationNumber:at-ancestor"),
                situations.stream().map(SituationUpdate::getSituationNumber).collect(Collectors.toSet()));
    }

    private AffectsRecord stopPointAffects(String stopRef) {
        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef(stopRef);
        stopPoint.setStopPointNames(List.of());
        stopPoint.setStopConditions(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setStopPoints(List.of(stopPoint));
        return affects;
    }

    /**
     * Regression test for Subscription.situations' includeClosed default (true, unlike
     * Query.situations' false): a subscriber must observe a PUBLISHED -> CLOSED
     * transition, not have it silently swallowed by the filter.
     */
    @Test
    public void testSubscriptionWithDefaultFilterReceivesClosedTransition() throws InterruptedException {
        // Mirrors the Subscription.situations schema default of includeClosed: true.
        SituationFilter subscriptionDefaultFilter = new SituationFilter(metricsService, MetricType.SUBSCRIPTION,
                null, null, null, null, null, null, null, null, null, null, null, null, null, true, 1, 50);

        List<List<SituationUpdate>> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch closedReceived = new CountDownLatch(1);

        PtSituationElementRecord published = baseRecord("TST:SituationNumber:lifecycle");
        published.setProgress("PUBLISHED");
        published.setVersion(1);
        published.setSeverity("NORMAL");
        repository.add(published);

        Disposable subscription = publisher.getPublisher(subscriptionDefaultFilter, "uuid").subscribe(batch -> {
            received.add(batch);
            if (batch.stream().anyMatch(s -> s.getProgress() == WorkflowStatusEnumeration.closed)) {
                closedReceived.countDown();
            }
        });

        try {
            PtSituationElementRecord closed = baseRecord("TST:SituationNumber:lifecycle");
            closed.setProgress("CLOSED");
            closed.setVersion(2);
            repository.add(closed);

            assertTrue(closedReceived.await(2, TimeUnit.SECONDS),
                    "Expected the closed transition to reach a subscriber using the default (includeClosed: true) filter");
        } finally {
            subscription.dispose();
        }
    }
}
