package org.entur.vehicles.repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Every ET and SX update the repositories throw away is counted by reason, so silent loss shows
 * up in Prometheus instead of only as a log line.
 */
class TimetableSituationDropMetricsTest {

    private PrometheusMeterRegistry registry;
    private TimetableRepository timetables;
    private SituationRepository situations;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMetricsService metrics = new PrometheusMetricsService(registry);
        NSRService nsrService = Mockito.mock(NSRService.class);
        timetables = new TimetableRepository(
                metrics,
                new LineService(PlannedDataService.disabled()),
                Mockito.mock(ServiceJourneyService.class),
                nsrService,
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                180,
                new EstimatedTimetableUpdateRxPublisher(metrics)
        );
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(
                metrics,
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new EstimatedTimetableUpdateRxPublisher(metrics),
                100,
                Duration.ofMillis(50),
                2000,
                nsrService);
        situations = new SituationRepository(
                metrics,
                new SituationMapper(new LineService(PlannedDataService.disabled()), nsrService,
                        new ServiceJourneyService(PlannedDataService.disabled())),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new SituationUpdateRxPublisher(metrics),
                republisher
        );
    }

    private double dropped(String type, String reason) {
        Counter counter = registry.find("app.vehicles.dropped").tags("dataType", type, "reason", reason).counter();
        assertNotNull(counter, type + "/" + reason + " counter not registered");
        return counter.count();
    }

    private static EstimatedVehicleJourneyRecord journey() {
        EstimatedCallRecord call = new EstimatedCallRecord();
        call.setStopPointRef("NSR:Quay:1");
        call.setOrder(1);
        call.setAimedArrivalTime(ZonedDateTime.now().plusMinutes(5).toString());

        EstimatedVehicleJourneyRecord journey = new EstimatedVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef("TST:Line:1");
        journey.setVehicleRef("TST:Vehicle:1");
        journey.setRecordedAtTime(ZonedDateTime.now().toString());
        journey.setEstimatedCalls(List.of(call));
        return journey;
    }

    private static PtSituationElementRecord situation(String situationNumber, String participantRef, Integer version) {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef(participantRef);
        record.setCreationTime(ZonedDateTime.now().toString());
        record.setReportType("general");
        record.setVersion(version);
        record.setProgress("PUBLISHED");
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        return record;
    }

    @Test
    void timetableThatFailsMappingIsCountedAsDropped() {
        EstimatedVehicleJourneyRecord journey = journey();
        journey.setDataSource(null);

        assertNull(timetables.add(journey));

        assertEquals(1, dropped("timetable", "mapping_error"));
    }

    @Test
    void situationWithoutCodespaceIsCountedAsNotMapped() {
        assertNull(situations.add(situation("no-prefix", null, 1)));

        assertEquals(1, dropped("situation", "not_mapped"));
    }

    @Test
    void olderSituationVersionIsCountedAsOutdated() {
        situations.add(situation("TST:SituationNumber:1", "TST", 2));

        assertNull(situations.add(situation("TST:SituationNumber:1", "TST", 1)));

        assertEquals(1, dropped("situation", "outdated"));
    }
}
