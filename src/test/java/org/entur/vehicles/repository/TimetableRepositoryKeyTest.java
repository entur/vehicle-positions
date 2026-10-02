package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * An estimated timetable describes a journey, not a vehicle: producers send it without a
 * VehicleRef until a vehicle is assigned, and may swap vehicles along the way. Every update for
 * the journey must replace the stored one, or a new subscription's initial snapshot also carries
 * the outdated entries.
 */
public class TimetableRepositoryKeyTest {

    private static final String DATED_SERVICE_JOURNEY = "TST:DatedServiceJourney:1";
    private static final String SERVICE_JOURNEY = "TST:ServiceJourney:1";

    private TimetableRepository repository;

    @BeforeEach
    public void init() {
        NSRService nsrService = Mockito.mock(NSRService.class);
        when(nsrService.getStop(anyString())).thenAnswer(i -> new StopPoint(i.getArgument(0)));

        ServiceJourneyService serviceJourneyService = Mockito.mock(ServiceJourneyService.class);
        when(serviceJourneyService.getDatedServiceJourney(DATED_SERVICE_JOURNEY))
                .thenAnswer(i -> new DatedServiceJourney(DATED_SERVICE_JOURNEY, new ServiceJourney(SERVICE_JOURNEY)));
        when(serviceJourneyService.getServiceJourney(SERVICE_JOURNEY))
                .thenAnswer(i -> new ServiceJourney(SERVICE_JOURNEY));

        repository = new TimetableRepository(
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)),
                new LineService(PlannedDataService.disabled()),
                serviceJourneyService,
                nsrService,
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                180,
                new EstimatedTimetableUpdateRxPublisher()
        );
    }

    private static EstimatedVehicleJourneyRecord datedJourney(String vehicleRef, String expectedArrival) {
        EstimatedVehicleJourneyRecord journey = journey(vehicleRef, expectedArrival);
        journey.setDatedVehicleJourneyRef(DATED_SERVICE_JOURNEY);
        return journey;
    }

    private static EstimatedVehicleJourneyRecord journey(String vehicleRef, String expectedArrival) {
        EstimatedCallRecord call = new EstimatedCallRecord();
        call.setStopPointRef("NSR:Quay:1");
        call.setOrder(1);
        call.setAimedArrivalTime(ZonedDateTime.now().plusMinutes(5).toString());
        call.setExpectedArrivalTime(expectedArrival);

        EstimatedVehicleJourneyRecord journey = new EstimatedVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef("TST:Line:1");
        journey.setVehicleRef(vehicleRef);
        journey.setRecordedAtTime(ZonedDateTime.now().toString());
        journey.setMonitored(true);
        journey.setEstimatedCalls(List.of(call));
        return journey;
    }

    private static String expectedArrival(EstimatedTimetableUpdate update) {
        return update.getCalls().get(0).getExpectedArrivalTime().toString();
    }

    @Test
    public void anAssignedVehicleReplacesTheUnassignedEntry() {
        String stale = ZonedDateTime.now().plusMinutes(5).toString();
        String fresh = ZonedDateTime.now().plusMinutes(7).toString();

        repository.add(datedJourney(null, stale));
        repository.add(datedJourney("1234", fresh));

        Collection<EstimatedTimetableUpdate> stored = repository.getTimetables(null);
        assertEquals(1, stored.size());
        EstimatedTimetableUpdate update = stored.iterator().next();
        assertEquals("1234", update.getVehicleId());
        assertEquals(ZonedDateTime.parse(fresh).toString(), expectedArrival(update));
    }

    @Test
    public void aVehicleSwapReplacesTheEntry() {
        repository.add(datedJourney("1234", ZonedDateTime.now().plusMinutes(5).toString()));
        repository.add(datedJourney("5678", ZonedDateTime.now().plusMinutes(7).toString()));

        Collection<EstimatedTimetableUpdate> stored = repository.getTimetables(null);
        assertEquals(1, stored.size());
        assertEquals("5678", stored.iterator().next().getVehicleId());
    }

    private static EstimatedVehicleJourneyRecord framedJourney(String vehicleRef, String operatingDate, String expectedArrival) {
        EstimatedVehicleJourneyRecord journey = journey(vehicleRef, expectedArrival);
        FramedVehicleJourneyRefRecord ref = new FramedVehicleJourneyRefRecord();
        ref.setDatedVehicleJourneyRef(SERVICE_JOURNEY);
        ref.setDataFrameRef(operatingDate);
        journey.setFramedVehicleJourneyRef(ref);
        return journey;
    }

    @Test
    public void aFramedJourneyIsKeyedWithoutItsVehicle() {
        repository.add(framedJourney(null, "2026-10-01", ZonedDateTime.now().plusMinutes(5).toString()));
        repository.add(framedJourney("1234", "2026-10-01", ZonedDateTime.now().plusMinutes(7).toString()));

        Collection<EstimatedTimetableUpdate> stored = repository.getTimetables(null);
        assertEquals(1, stored.size());
        assertEquals("1234", stored.iterator().next().getVehicleId());
    }

    @Test
    public void aServiceJourneyIsKeptApartPerOperatingDate() {
        String today = ZonedDateTime.now().plusMinutes(5).toString();
        String tomorrow = ZonedDateTime.now().plusDays(1).toString();

        repository.add(framedJourney(null, "2026-10-01", today));
        repository.add(framedJourney(null, "2026-10-02", tomorrow));

        Map<String, EstimatedTimetableUpdate> byDate = repository.getTimetables(null).stream()
                .collect(Collectors.toMap(u -> u.getServiceJourney().getDate(), u -> u));
        assertEquals(Set.of("2026-10-01", "2026-10-02"), byDate.keySet());
        assertEquals(ZonedDateTime.parse(today).toString(), expectedArrival(byDate.get("2026-10-01")));
        assertEquals(ZonedDateTime.parse(tomorrow).toString(), expectedArrival(byDate.get("2026-10-02")));
    }
}
