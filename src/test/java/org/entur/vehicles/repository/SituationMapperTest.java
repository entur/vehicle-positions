package org.entur.vehicles.repository;

import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedOperatorRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPlaceRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.avro.realtime.siri.model.ValidityPeriodRecord;
import org.entur.vehicles.data.SeverityEnumeration;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.entur.vehicles.service.NSRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationMapperTest {

    private SituationMapper mapper;

    @BeforeEach
    public void init() {
        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));
        mapper = new SituationMapper(new LineService(PlannedDataService.disabled()), nsrService,
                new ServiceJourneyService(PlannedDataService.disabled()));
    }

    private static PtSituationElementRecord recordAffectingDatedServiceJourney(String datedServiceJourneyId) {
        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setVehicleJourneyRefs(List.of());
        journey.setDatedVehicleJourneyRefs(List.of(datedServiceJourneyId));
        journey.setRoutes(List.of());
        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of());
        affects.setStopPoints(List.of());
        affects.setStopPlaces(List.of());
        affects.setVehicleJourneys(List.of(journey));
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber("TST:SituationNumber:1");
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusHours(3).toString());
        record.setReportType("general");
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        record.setAffects(affects);
        return record;
    }

    /**
     * A DatedServiceJourney the planned data knows resolves to its ServiceJourney + operating
     * date - the mapping VehicleRepository and TimetableRepository already apply. The
     * producer-tagged ServiceJourney list and its id index are deliberately left alone.
     */
    @Test
    public void testResolvedDatedServiceJourneyCarriesItsServiceJourney() {
        ServiceJourneyService serviceJourneyService = Mockito.mock(ServiceJourneyService.class);
        Mockito.when(serviceJourneyService.findDatedServiceJourney("TST:DatedServiceJourney:1"))
                .thenReturn(datedServiceJourney("TST:DatedServiceJourney:1", "TST:ServiceJourney:1", "2026-08-25"));
        NSRService nsrService = Mockito.mock(NSRService.class);
        SituationMapper mapper = new SituationMapper(new LineService(PlannedDataService.disabled()), nsrService, serviceJourneyService);

        SituationUpdate situation = mapper.map(recordAffectingDatedServiceJourney("TST:DatedServiceJourney:1"));

        List<DatedServiceJourney> dated = situation.getAffects().getDatedServiceJourneys();
        assertEquals(1, dated.size());
        assertEquals("TST:DatedServiceJourney:1", dated.get(0).getId());
        assertNotNull(dated.get(0).getServiceJourney());
        assertEquals("TST:ServiceJourney:1", dated.get(0).getServiceJourney().getId());
        assertEquals("2026-08-25", dated.get(0).getServiceJourney().getDate());
        assertTrue(situation.getAffects().getServiceJourneyIds().isEmpty(),
                "resolving must not expand the producer-tagged service journeys");
        assertTrue(situation.getAffects().getServiceJourneys().isEmpty());
    }

    @Test
    public void testUnresolvedDatedServiceJourneyKeepsServiceJourneyNull() {
        SituationUpdate situation = mapper.map(recordAffectingDatedServiceJourney("TST:DatedServiceJourney:unknown"));

        List<DatedServiceJourney> dated = situation.getAffects().getDatedServiceJourneys();
        assertEquals(1, dated.size());
        assertEquals("TST:DatedServiceJourney:unknown", dated.get(0).getId());
        assertNull(dated.get(0).getServiceJourney());
    }

    private static DatedServiceJourney datedServiceJourney(String id, String serviceJourneyId, String date) {
        DatedServiceJourney dsj = new DatedServiceJourney(id, new ServiceJourney(serviceJourneyId, date));
        dsj.setOperatingDay(date);
        return dsj;
    }

    private PtSituationElementRecord minimalRecord() {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber("TST:SituationNumber:1");
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusHours(3).toString());
        record.setReportType("general");
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
    public void testMapsCoreFields() {
        PtSituationElementRecord record = minimalRecord();
        record.setVersion(3);
        record.setSeverity("VERY_SEVERE");
        record.setProgress("PUBLISHED");
        record.setPriority(1);
        record.setPlanned(false);
        record.setKeywords(List.of("snow"));

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation);
        assertEquals("TST:SituationNumber:1", situation.getSituationNumber());
        assertEquals("TST", situation.getCodespace().getCodespaceId());
        assertEquals(3, situation.getVersion());
        assertEquals(SeverityEnumeration.verySevere, situation.getSeverity());
        assertEquals(WorkflowStatusEnumeration.published, situation.getProgress());
        assertEquals("general", situation.getReportType());
        assertEquals(List.of("snow"), situation.getKeywords());
        assertEquals(false, situation.getPlanned());
    }

    @Test
    public void testDerivesCodespaceFromSituationNumberWhenParticipantRefIsMissing() {
        PtSituationElementRecord record = minimalRecord();
        record.setParticipantRef(null);

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation);
        assertEquals("TST", situation.getCodespace().getCodespaceId());
    }

    @Test
    public void testReturnsNullWhenNoCodespaceCanBeResolved() {
        PtSituationElementRecord record = minimalRecord();
        record.setParticipantRef(null);
        record.setSituationNumber("no-codespace-here");

        assertNull(mapper.map(record));
    }

    @Test
    public void testMapsTranslatedText() {
        PtSituationElementRecord record = minimalRecord();
        TranslatedStringRecord summary = new TranslatedStringRecord();
        summary.setValue("Buss for tog");
        summary.setLanguage("no");
        record.setSummaries(List.of(summary));

        SituationUpdate situation = mapper.map(record);

        assertEquals(1, situation.getSummary().size());
        assertEquals("Buss for tog", situation.getSummary().get(0).getValue());
        assertEquals("no", situation.getSummary().get(0).getLanguage());
    }

    @Test
    public void testFlattensAffects() {
        PtSituationElementRecord record = minimalRecord();

        AffectedLineRecord affectedLine = new AffectedLineRecord();
        affectedLine.setLineRef("TST:Line:1");

        AffectedOperatorRecord affectedOperator = new AffectedOperatorRecord();
        affectedOperator.setOperatorRef("TST:Operator:1");

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setVehicleMode("BUS");
        network.setAffectedLines(List.of(affectedLine));
        network.setAffectedOperators(List.of(affectedOperator));

        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef("TST:Quay:1");
        stopPoint.setStopPointNames(List.of());
        stopPoint.setStopConditions(List.of());

        AffectedStopPlaceRecord stopPlace = new AffectedStopPlaceRecord();
        stopPlace.setStopPlaceRef("TST:StopPlace:9");
        stopPlace.setPlaceNames(List.of());

        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setLineRef("TST:Line:1");
        journey.setVehicleJourneyRefs(List.of("TST:ServiceJourney:1"));
        journey.setDatedVehicleJourneyRefs(List.of("TST:DatedServiceJourney:1"));
        journey.setRoutes(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        affects.setStopPoints(List.of(stopPoint));
        affects.setStopPlaces(List.of(stopPlace));
        affects.setVehicleJourneys(List.of(journey));
        record.setAffects(affects);

        SituationUpdate situation = mapper.map(record);

        // The line is named by both the network and the vehicle journey - it must appear once.
        assertEquals(1, situation.getAffects().getLines().size());
        assertTrue(situation.getAffects().getLineRefs().contains("TST:Line:1"));
        assertTrue(situation.getAffects().getStopRefs().contains("TST:Quay:1"));
        assertTrue(situation.getAffects().getStopRefs().contains("TST:StopPlace:9"));
        assertTrue(situation.getAffects().getServiceJourneyIds().contains("TST:ServiceJourney:1"));
        assertTrue(situation.getAffects().getDatedServiceJourneyIds().contains("TST:DatedServiceJourney:1"));
        assertTrue(situation.getAffects().getOperatorRefs().contains("TST:Operator:1"));
        assertTrue(situation.getAffects().getVehicleModes().contains(VehicleModeEnumeration.BUS));
    }

    @Test
    public void testExpirationIsLatestValidityEndTime() {
        PtSituationElementRecord record = minimalRecord();
        ZonedDateTime latest = ZonedDateTime.now().plusDays(5);

        ValidityPeriodRecord first = new ValidityPeriodRecord();
        first.setStartTime(ZonedDateTime.now().minusDays(1).toString());
        first.setEndTime(ZonedDateTime.now().plusDays(1).toString());

        ValidityPeriodRecord second = new ValidityPeriodRecord();
        second.setStartTime(ZonedDateTime.now().minusDays(1).toString());
        second.setEndTime(latest.toString());

        record.setValidityPeriods(List.of(first, second));

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation.getExpiration());
        assertEquals(latest.toEpochSecond(), situation.getExpiration().toEpochSecond());
    }

    @Test
    public void testOpenEndedSituationHasNullExpiration() {
        PtSituationElementRecord record = minimalRecord();

        ValidityPeriodRecord openEnded = new ValidityPeriodRecord();
        openEnded.setStartTime(ZonedDateTime.now().minusDays(400).toString());
        openEnded.setEndTime(null);
        record.setValidityPeriods(List.of(openEnded));

        SituationUpdate situation = mapper.map(record);

        assertNull(situation.getExpiration());
        assertTrue(situation.getOpenEnded());
    }

    @Test
    public void testClosedSituationExpiresImmediately() {
        PtSituationElementRecord record = minimalRecord();
        record.setProgress("CLOSED");

        ValidityPeriodRecord openEnded = new ValidityPeriodRecord();
        openEnded.setStartTime(ZonedDateTime.now().minusDays(1).toString());
        openEnded.setEndTime(null);
        record.setValidityPeriods(List.of(openEnded));

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation.getExpiration());
        assertTrue(situation.getExpiration().isBefore(ZonedDateTime.now().plusSeconds(5)));
    }

    /**
     * NSR is the single source of truth for official stop names. A StopPointName or
     * PlaceName reported inside a situation is never used, and the shared cached
     * StopPoint that NSRService hands out - the same instance TimetableRepository puts
     * into Call.stopPoint - is never modified. A stub handing out the SAME instance on
     * every call (unlike the other tests in this file, which return a fresh StopPoint
     * per call) is required to catch a mutation.
     */
    @Test
    public void testStopNamesAlwaysComeFromNsrAndTheCacheIsNeverModified() {
        StopPoint cachedQuay = new StopPoint("TST:Quay:1", "Official quay name", null);
        StopPoint cachedStopPlace = new StopPoint("TST:StopPlace:9", "Official stop place name", null);

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop("TST:Quay:1")).thenReturn(cachedQuay);
        Mockito.when(nsrService.getStop("TST:StopPlace:9")).thenReturn(cachedStopPlace);
        SituationMapper mapper = new SituationMapper(new LineService(PlannedDataService.disabled()), nsrService,
                new ServiceJourneyService(PlannedDataService.disabled()));

        SituationUpdate first = mapper.map(situationNamingStop("TST:SituationNumber:first", "Name from situation A"));
        SituationUpdate second = mapper.map(situationNamingStop("TST:SituationNumber:second", "Name from situation B"));

        assertEquals("Official quay name", first.getAffects().getStopPoints().get(0).getName(),
                "The situation's own StopPointName must be ignored - NSR is the source of truth");
        assertEquals("Official stop place name", first.getAffects().getStopPlaces().get(0).getName(),
                "The situation's own PlaceName must be ignored - NSR is the source of truth");
        assertEquals("Official quay name", second.getAffects().getStopPoints().get(0).getName());
        assertEquals("Official stop place name", second.getAffects().getStopPlaces().get(0).getName());

        assertEquals("Official quay name", cachedQuay.getName(),
                "The shared NSRService stop-point cache instance must never be mutated");
        assertEquals("Official stop place name", cachedStopPlace.getName(),
                "The shared NSRService stop-place cache instance must never be mutated");
    }

    @Test
    public void testStopUnknownToNsrHasNoName() {
        // What the real cache loader returns for a ref NSR knows nothing about.
        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop("TST:Quay:1")).thenReturn(new StopPoint("TST:Quay:1"));
        Mockito.when(nsrService.getStop("TST:StopPlace:9")).thenReturn(new StopPoint("TST:StopPlace:9"));
        SituationMapper mapper = new SituationMapper(new LineService(PlannedDataService.disabled()), nsrService,
                new ServiceJourneyService(PlannedDataService.disabled()));

        SituationUpdate situation =
                mapper.map(situationNamingStop("TST:SituationNumber:unknown", "Name from situation"));

        assertNull(situation.getAffects().getStopPoints().get(0).getName(),
                "A stop NSR does not know has no official name - the situation's name is not a fallback");
        assertNull(situation.getAffects().getStopPlaces().get(0).getName(),
                "A stop place NSR does not know has no official name");
    }

    private PtSituationElementRecord situationNamingStop(String situationNumber, String name) {
        PtSituationElementRecord record = minimalRecord();
        record.setSituationNumber(situationNumber);

        TranslatedStringRecord stopPointName = new TranslatedStringRecord();
        stopPointName.setValue(name);
        stopPointName.setLanguage("no");

        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef("TST:Quay:1");
        stopPoint.setStopPointNames(List.of(stopPointName));
        stopPoint.setStopConditions(List.of());

        TranslatedStringRecord placeName = new TranslatedStringRecord();
        placeName.setValue(name);
        placeName.setLanguage("no");

        AffectedStopPlaceRecord stopPlace = new AffectedStopPlaceRecord();
        stopPlace.setStopPlaceRef("TST:StopPlace:9");
        stopPlace.setPlaceNames(List.of(placeName));

        AffectsRecord affects = new AffectsRecord();
        affects.setStopPoints(List.of(stopPoint));
        affects.setStopPlaces(List.of(stopPlace));
        record.setAffects(affects);

        return record;
    }

    /** Regression test for the framed-vs-bare vehicle-journey ref ordering (see mapAffects). */
    @Test
    public void testFramedVehicleJourneyRefDateSurvivesWhenAlsoListedAsBareRef() {
        PtSituationElementRecord record = minimalRecord();

        org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord framedRef =
                new org.entur.avro.realtime.siri.model.FramedVehicleJourneyRefRecord();
        framedRef.setDatedVehicleJourneyRef("TST:ServiceJourney:1");
        framedRef.setDataFrameRef("2026-08-05");

        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setLineRef("TST:Line:1");
        // The same id is also carried as a bare ref - the framed ref must still win.
        journey.setVehicleJourneyRefs(List.of("TST:ServiceJourney:1"));
        journey.setFramedVehicleJourneyRef(framedRef);
        journey.setRoutes(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setVehicleJourneys(List.of(journey));
        record.setAffects(affects);

        SituationUpdate situation = mapper.map(record);

        assertEquals(1, situation.getAffects().getServiceJourneys().size());
        assertEquals("2026-08-05", situation.getAffects().getServiceJourneys().get(0).getDate(),
                "The framed ref's dataFrameRef date must not be discarded by the later bare ref");
    }

    @Test
    public void testResolveCodespaceRejectsSituationNumberStartingWithColon() {
        PtSituationElementRecord record = minimalRecord();
        record.setParticipantRef(null);
        record.setSituationNumber(":Foo:1");

        assertNull(mapper.map(record));
    }
}
