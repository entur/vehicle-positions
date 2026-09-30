package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.RecordedCallRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DestinationName is optional in SIRI ET; without it the journey shows the NeTEx destination
 * display in effect at its next stop - the first estimated call, or the last recorded one when
 * the journey has no stops left.
 */
public class TimetableRepositoryDestinationTest {

    private static final String DATED_SERVICE_JOURNEY = "TST:DatedServiceJourney:1";
    private static final String SERVICE_JOURNEY = "TST:ServiceJourney:1";

    private ServiceJourneyService serviceJourneyService;
    private TimetableRepository repository;

    @BeforeEach
    public void init() {
        NSRService nsrService = Mockito.mock(NSRService.class);
        when(nsrService.getStop(anyString())).thenAnswer(i -> new StopPoint(i.getArgument(0)));

        serviceJourneyService = Mockito.mock(ServiceJourneyService.class);
        when(serviceJourneyService.getDatedServiceJourney(DATED_SERVICE_JOURNEY))
                .thenReturn(new DatedServiceJourney(DATED_SERVICE_JOURNEY, new ServiceJourney(SERVICE_JOURNEY)));

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

    private static EstimatedVehicleJourneyRecord journey(String destinationName, List<Integer> recordedOrders, List<Integer> estimatedOrders) {
        ZonedDateTime now = ZonedDateTime.now();

        List<RecordedCallRecord> recorded = new ArrayList<>();
        for (Integer order : recordedOrders) {
            RecordedCallRecord call = new RecordedCallRecord();
            call.setStopPointRef("NSR:Quay:" + order);
            call.setOrder(order);
            call.setAimedDepartureTime(now.minusMinutes(10).toString());
            recorded.add(call);
        }
        List<EstimatedCallRecord> estimated = new ArrayList<>();
        for (Integer order : estimatedOrders) {
            EstimatedCallRecord call = new EstimatedCallRecord();
            call.setStopPointRef("NSR:Quay:" + order);
            call.setOrder(order);
            call.setAimedArrivalTime(now.plusMinutes(5).toString());
            estimated.add(call);
        }

        EstimatedVehicleJourneyRecord journey = new EstimatedVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef("TST:Line:1");
        journey.setDatedVehicleJourneyRef(DATED_SERVICE_JOURNEY);
        journey.setRecordedAtTime(now.toString());
        journey.setMonitored(true);
        if (destinationName != null) {
            TranslatedStringRecord name = new TranslatedStringRecord();
            name.setValue(destinationName);
            journey.setDestinationNames(List.of(name));
        }
        journey.setRecordedCalls(recorded);
        journey.setEstimatedCalls(estimated);
        return journey;
    }

    private EstimatedTimetableUpdate stored() {
        return repository.getTimetables(null).iterator().next();
    }

    @Test
    public void reportedDestinationNameIsKept() {
        repository.add(journey("Sandvika", List.of(1), List.of(2, 3)));

        assertEquals("Sandvika", stored().getDestinationName());
        verify(serviceJourneyService, never()).getDestinationDisplay(any(), any());
    }

    @Test
    public void missingDestinationNameIsTakenFromNetexAtTheNextStop() {
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, 3)).thenReturn("Kolsås");

        repository.add(journey(null, List.of(1, 2), List.of(3, 4)));

        assertEquals("Kolsås", stored().getDestinationName());
    }

    @Test
    public void aFinishedJourneyUsesItsLastRecordedStop() {
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, 4)).thenReturn("Sandvika");

        repository.add(journey(null, List.of(1, 2, 3, 4), List.of()));

        assertEquals("Sandvika", stored().getDestinationName());
    }

    @Test
    public void withoutCallsTheJourneysDestinationIsUsed() {
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, null)).thenReturn("Kolsås");

        repository.add(journey(null, List.of(), List.of()));

        assertEquals("Kolsås", stored().getDestinationName());
    }
}
