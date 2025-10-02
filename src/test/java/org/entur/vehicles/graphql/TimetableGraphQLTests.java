package org.entur.vehicles.graphql;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.ServiceJourneyIdAndDate;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingTimetableMap;
import org.entur.vehicles.repository.TimetableRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class TimetableGraphQLTests {

    TimetableRepository repository;

    Query queryService;
    private EstimatedTimetableUpdateRxPublisher publisher = new EstimatedTimetableUpdateRxPublisher();

    private ServiceJourneyService serviceJourneyService = Mockito.mock(ServiceJourneyService.class);

    @BeforeEach
    public void initData() throws ExecutionException {
        PrometheusMetricsService metricsService = new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        repository = new TimetableRepository(
                metricsService,
                new LineService(false),
                serviceJourneyService,
                new NSRService(false, null),
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                        180,
                publisher
        );
        publisher = new EstimatedTimetableUpdateRxPublisher();
        queryService = new Query(null, repository, metricsService);

        EstimatedVehicleJourneyRecord journeyRecord = new EstimatedVehicleJourneyRecord();
        journeyRecord.setLineRef("TST:Line:123");

        FramedVehicleJourneyRefRecord framedVehicleJourneyRef = new FramedVehicleJourneyRefRecord();
        framedVehicleJourneyRef.setDataFrameRef("2020-12-15");
        framedVehicleJourneyRef.setDatedVehicleJourneyRef("TST:ServiceJourney:1234567890");
        journeyRecord.setFramedVehicleJourneyRef(framedVehicleJourneyRef);

        journeyRecord.setRecordedAtTime(ZonedDateTime.now().toString());

        journeyRecord.setMonitored(true);
        journeyRecord.setDataSource("TST");


        EstimatedVehicleJourneyRecord dsj_journeyRecord = new EstimatedVehicleJourneyRecord();

        dsj_journeyRecord.setRecordedAtTime(ZonedDateTime.now().toString());

        dsj_journeyRecord.setLineRef("DSJ:Line:321");

        dsj_journeyRecord.setDatedVehicleJourneyRef("DSJ:DatedServiceJourney:1234567890");

        dsj_journeyRecord.setMonitored(true);
        dsj_journeyRecord.setDataSource("DSJ");

        Mockito.when(serviceJourneyService.getDatedServiceJourney(
                Mockito.anyString())).thenReturn(new DatedServiceJourney("DSJ:DatedServiceJourney:1234567890",
                new ServiceJourney("DSJ:ServiceJourney:1234567890")));

        Mockito.when(serviceJourneyService.getServiceJourney(
                "TST:ServiceJourney:1234567890")).thenReturn(new ServiceJourney("TST:ServiceJourney:1234567890"));

        Mockito.when(serviceJourneyService.getServiceJourney(
                "DSJ:DatedServiceJourney:1234567890")).thenReturn(new ServiceJourney("DSJ:ServiceJourney:1234567890"));


        repository.addAll(List.of(journeyRecord, dsj_journeyRecord));
    }

    @Test
    public void testQueries() {

        ServiceJourneyIdAndDate serviceJourneyIdAndDate = new ServiceJourneyIdAndDate("TST:ServiceJourney:1234567890", null);
        Collection<EstimatedTimetableUpdate> timetables =
                queryService.getTimetables(Set.of(serviceJourneyIdAndDate), null,
                        null, null, null,
                        null, null);

        assertFalse(timetables.isEmpty());
        assertEquals(1, timetables.size());
        assertEquals("TST:ServiceJourney:1234567890", timetables.iterator().next().getServiceJourney().getServiceJourneyId());


        timetables =
                queryService.getTimetables(null, Set.of("DSJ:DatedServiceJourney:1234567890"), null,
                        null, null,
                        null, null);
        assertFalse(timetables.isEmpty());
        assertEquals(1, timetables.size());
        assertEquals("DSJ:DatedServiceJourney:1234567890", timetables.iterator().next().getDatedServiceJourney().getId());

    }
}
