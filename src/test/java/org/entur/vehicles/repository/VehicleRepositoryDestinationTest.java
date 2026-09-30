package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.CallRecord;
import org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DestinationName is optional in SIRI VM; without it the vehicle shows the NeTEx destination
 * display in effect at its monitored stop.
 */
public class VehicleRepositoryDestinationTest {

    private static final String SERVICE_JOURNEY = "TST:ServiceJourney:1";
    private static final ZonedDateTime T0 = ZonedDateTime.parse("2026-09-30T10:00:00+02:00");

    private ServiceJourneyService serviceJourneyService;
    private VehicleRepository repository;

    @BeforeEach
    public void init() {
        serviceJourneyService = Mockito.mock(ServiceJourneyService.class);
        when(serviceJourneyService.getServiceJourney(anyString())).thenAnswer(i -> new ServiceJourney(i.getArgument(0)));
        repository = new VehicleRepository(
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)),
                new LineService(PlannedDataService.disabled()),
                serviceJourneyService,
                new AutoPurgingVehicleMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                1440,
                false,
                new VehicleUpdateRxPublisher()
        );
    }

    private static VehicleActivityRecord position(String destinationName, Integer monitoredOrder) {
        LocationRecord location = new LocationRecord();
        location.setLatitude(59.91);
        location.setLongitude(10.75);

        FramedVehicleJourneyRefRecord framed = new FramedVehicleJourneyRefRecord();
        framed.setDatedVehicleJourneyRef(SERVICE_JOURNEY);
        framed.setDataFrameRef("2026-09-30");

        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setVehicleRef("TST:Vehicle:1");
        journey.setVehicleLocation(location);
        journey.setLocationRecordedAtTime(T0.toString());
        journey.setFramedVehicleJourneyRef(framed);
        if (destinationName != null) {
            TranslatedStringRecord name = new TranslatedStringRecord();
            name.setValue(destinationName);
            journey.setDestinationNames(List.of(name));
        }
        if (monitoredOrder != null) {
            CallRecord call = new CallRecord();
            call.setOrder(monitoredOrder);
            journey.setMonitoredCall(call);
        }

        VehicleActivityRecord activity = new VehicleActivityRecord();
        activity.setMonitoredVehicleJourney(journey);
        activity.setRecordedAtTime(T0.toString());
        activity.setValidUntilTime(T0.plusMinutes(10).toString());
        return activity;
    }

    private VehicleUpdate stored() {
        return repository.getVehicles(null).iterator().next();
    }

    @Test
    public void reportedDestinationNameIsKept() {
        repository.add(position("Sandvika", 3));

        assertEquals("Sandvika", stored().getDestinationName());
        verify(serviceJourneyService, never()).getDestinationDisplay(any(), any());
    }

    @Test
    public void missingDestinationNameIsTakenFromNetexAtTheMonitoredStop() {
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, 3)).thenReturn("Kolsås");

        repository.add(position(null, 3));

        assertEquals("Kolsås", stored().getDestinationName());
    }

    @Test
    public void withoutAMonitoredCallTheJourneysDestinationIsUsed() {
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, null)).thenReturn("Kolsås");

        repository.add(position(null, null));

        assertEquals("Kolsås", stored().getDestinationName());
    }

    @Test
    public void aDatedServiceJourneyIsResolvedToItsServiceJourney() {
        VehicleActivityRecord activity = position(null, 2);
        activity.getMonitoredVehicleJourney().setFramedVehicleJourneyRef(null);
        activity.getMonitoredVehicleJourney().setVehicleJourneyRef("TST:DatedServiceJourney:1");
        when(serviceJourneyService.getDatedServiceJourney("TST:DatedServiceJourney:1"))
                .thenReturn(new DatedServiceJourney("TST:DatedServiceJourney:1", new ServiceJourney(SERVICE_JOURNEY)));
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, 2)).thenReturn("Kolsås");

        repository.add(activity);

        assertEquals("Kolsås", stored().getDestinationName());
    }

    @Test
    public void netexDestinationFollowsTheVehicleAlongTheRoute() {
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, 1)).thenReturn("Kolsås");
        when(serviceJourneyService.getDestinationDisplay(SERVICE_JOURNEY, 3)).thenReturn("Sandvika");

        repository.add(position(null, 1));
        repository.add(position(null, 3));

        assertEquals("Sandvika", stored().getDestinationName());
    }
}
