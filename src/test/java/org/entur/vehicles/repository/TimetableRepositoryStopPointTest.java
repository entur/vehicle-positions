package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NSR is the single source of truth for official stop names. A StopPointName reported by a
 * data producer is never used, and the shared cached StopPoint NSRService hands out is never
 * modified.
 */
public class TimetableRepositoryStopPointTest {

    private static final String NAMED_STOP = "NSR:Quay:1";
    private static final String CANONICAL_NAME = "Canonical NSR name";

    /** A ref NSR knows nothing about - the cache loader returns a bare, nameless StopPoint. */
    private static final String UNKNOWN_STOP = "NSR:Quay:999";

    private TimetableRepository repository;

    /** The single instance NSRService's cache returns for NAMED_STOP. */
    private StopPoint sharedCachedStop;

    @BeforeEach
    public void init() throws ExecutionException {
        PrometheusMetricsService metricsService =
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        sharedCachedStop = new StopPoint(NAMED_STOP, CANONICAL_NAME, new Location(10.5, 59.9));

        NSRService nsrService = Mockito.mock(NSRService.class);
        // Return the SAME instance every time, exactly as the real LoadingCache does.
        Mockito.when(nsrService.getStop(NAMED_STOP)).thenReturn(sharedCachedStop);
        Mockito.when(nsrService.getStop(UNKNOWN_STOP)).thenReturn(new StopPoint(UNKNOWN_STOP));

        ServiceJourneyService serviceJourneyService = Mockito.mock(ServiceJourneyService.class);
        Mockito.when(serviceJourneyService.getDatedServiceJourney(Mockito.anyString()))
                .thenAnswer(invocation -> new DatedServiceJourney(invocation.getArgument(0)));

        repository = new TimetableRepository(
                metricsService,
                new LineService(false),
                serviceJourneyService,
                nsrService,
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                180,
                new EstimatedTimetableUpdateRxPublisher()
        );
    }

    private EstimatedVehicleJourneyRecord journeyCallingAt(String journeyId, String stopRef, String reportedName) {
        TranslatedStringRecord name = new TranslatedStringRecord();
        name.setValue(reportedName);
        name.setLanguage("no");

        EstimatedCallRecord call = new EstimatedCallRecord();
        call.setStopPointRef(stopRef);
        call.setStopPointNames(List.of(name));
        call.setOrder(1);
        call.setAimedArrivalTime(ZonedDateTime.now().plusMinutes(5).toString());
        call.setAimedDepartureTime(ZonedDateTime.now().plusMinutes(6).toString());

        EstimatedVehicleJourneyRecord journey = new EstimatedVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef("TST:Line:1");
        journey.setDatedVehicleJourneyRef(journeyId);
        journey.setRecordedAtTime(ZonedDateTime.now().toString());
        journey.setMonitored(true);
        journey.setEstimatedCalls(List.of(call));
        return journey;
    }

    private StopPoint storedStopOf(String journeyId) {
        return repository.getTimetables(null).stream()
                .filter(t -> t.getDatedServiceJourney().getId().equals(journeyId))
                .map(t -> t.getCalls().get(0).getStopPoint())
                .findFirst()
                .orElseThrow();
    }

    @Test
    public void testStopNameAlwaysComesFromNsrNotFromTheProducer() {
        repository.add(journeyCallingAt("TST:DatedServiceJourney:1", NAMED_STOP, "Producer supplied name"));

        assertEquals(CANONICAL_NAME, storedStopOf("TST:DatedServiceJourney:1").getName(),
                "NSR is the single source of truth for official stop names - "
                        + "a producer-reported StopPointName must be ignored");
    }

    @Test
    public void testCachedStopIsNeverModified() {
        repository.add(journeyCallingAt("TST:DatedServiceJourney:1", NAMED_STOP, "Producer name A"));
        repository.add(journeyCallingAt("TST:DatedServiceJourney:2", NAMED_STOP, "Producer name B"));

        assertEquals(CANONICAL_NAME, sharedCachedStop.getName(),
                "The cached NSR StopPoint is shared by every call referencing this stop "
                        + "and must never be modified");
    }

    @Test
    public void testStopUnknownToNsrHasNoName() {
        repository.add(journeyCallingAt("TST:DatedServiceJourney:1", UNKNOWN_STOP, "Producer supplied name"));

        StopPoint stored = storedStopOf("TST:DatedServiceJourney:1");
        assertEquals(UNKNOWN_STOP, stored.getId());
        assertNull(stored.getName(),
                "A stop NSR does not know has no official name - the producer's name is not a fallback");
    }

    @Test
    public void testLocationFromNsrIsPreserved() {
        repository.add(journeyCallingAt("TST:DatedServiceJourney:1", NAMED_STOP, "Producer supplied name"));

        assertEquals(sharedCachedStop.getLocation(), storedStopOf("TST:DatedServiceJourney:1").getLocation(),
                "Coordinates from the NSR lookup must survive");
    }
}
