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
import org.entur.vehicles.data.SeverityEnumeration;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingSituationMap;
import org.entur.vehicles.repository.SituationMapper;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationGraphQLTests {

    private Query queryService;

    @BeforeEach
    public void initData() {
        PrometheusMetricsService metricsService =
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));

        SituationRepository repository = new SituationRepository(
                metricsService,
                new SituationMapper(new LineService(false), nsrService),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new SituationUpdateRxPublisher()
        );

        repository.addAll(List.of(
                busSituation(),
                openEndedSituation(),
                closedSituation()
        ));

        queryService = new Query(null, null, repository, metricsService);
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
}
