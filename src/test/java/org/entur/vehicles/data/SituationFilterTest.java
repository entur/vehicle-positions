package org.entur.vehicles.data;

import org.entur.vehicles.data.model.AffectedStop;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationFilterTest {

    private SituationUpdate situation() {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber("TST:SituationNumber:1");
        situation.setCodespace(Codespace.getCodespace("TST"));
        situation.setSeverity(SeverityEnumeration.severe);
        situation.setProgress(WorkflowStatusEnumeration.published);
        situation.setReportType("general");
        situation.setCreationTime(ZonedDateTime.now().minusDays(40));
        situation.setLastUpdated(ZonedDateTime.now());
        situation.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(1))));

        Affects affects = new Affects();
        affects.addLine(new Line("TST:Line:1"));
        affects.addStopPoint(new StopPoint("TST:Quay:1"));
        affects.addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));
        affects.addOperator(new Operator("TST:Operator:1"));
        affects.addVehicleMode(VehicleModeEnumeration.BUS);
        situation.setAffects(affects);

        return situation;
    }

    /** Base fixture plus a dated service journey, for the datedServiceJourneyId criterion. */
    private SituationUpdate situationWithDatedServiceJourney() {
        SituationUpdate situation = situation();
        situation.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:1"));
        return situation;
    }

    private SituationFilter filter(String codespaceId, String operatorRef, String lineRef, String stopRef,
                                   String serviceJourneyId, VehicleModeEnumeration mode,
                                   SeverityEnumeration severity, Boolean validNow, Boolean openEnded,
                                   Duration minAge, Boolean includeClosed) {
        return new SituationFilter(null, MetricType.QUERY, null, codespaceId, operatorRef, lineRef,
                stopRef == null ? null : Set.of(stopRef),
                serviceJourneyId, null, mode, severity, null, validNow, openEnded, minAge, includeClosed,
                null, null);
    }

    @Test
    public void testEmptyFilterMatchesEverything() {
        assertTrue(filter(null, null, null, null, null, null, null, null, null, null, null)
                .isMatch(situation()));
    }

    @Test
    public void testMatchesOnAffectedObjects() {
        SituationUpdate situation = situation();
        assertTrue(filter("TST", null, null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, "TST:Operator:1", null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, "TST:Line:1", null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, "TST:Quay:1", null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, "TST:ServiceJourney:1", null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, null, VehicleModeEnumeration.BUS, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, null, null, SeverityEnumeration.severe, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testRejectsNonMatchingAffectedObjects() {
        SituationUpdate situation = situation();
        assertFalse(filter("ABC", null, null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, "TST:Operator:999", null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, "TST:Line:999", null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, "TST:Quay:999", null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, null, "TST:ServiceJourney:999", null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, null, null, VehicleModeEnumeration.RAIL, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, null, null, null, SeverityEnumeration.slight, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testCombinedCriteriaMustAllMatch() {
        SituationUpdate situation = situation();
        assertTrue(filter("TST", null, "TST:Line:1", null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter("TST", null, "TST:Line:999", null, null, null, null, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testValidNow() {
        SituationUpdate current = situation();
        assertTrue(filter(null, null, null, null, null, null, null, true, null, null, null).isMatch(current));

        SituationUpdate future = situation();
        future.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().plusDays(1), ZonedDateTime.now().plusDays(2))));
        assertFalse(filter(null, null, null, null, null, null, null, true, null, null, null).isMatch(future));
    }

    @Test
    public void testOpenEnded() {
        SituationUpdate bounded = situation();
        assertFalse(filter(null, null, null, null, null, null, null, null, true, null, null).isMatch(bounded));

        SituationUpdate openEnded = situation();
        openEnded.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(400), null)));
        assertTrue(filter(null, null, null, null, null, null, null, null, true, null, null).isMatch(openEnded));
    }

    @Test
    public void testMinAge() {
        SituationUpdate old = situation();
        assertTrue(filter(null, null, null, null, null, null, null, null, null, Duration.ofDays(30), null).isMatch(old));

        SituationUpdate fresh = situation();
        fresh.setCreationTime(ZonedDateTime.now().minusDays(1));
        assertFalse(filter(null, null, null, null, null, null, null, null, null, Duration.ofDays(30), null).isMatch(fresh));
    }

    /**
     * Regression test: the minAge cutoff must be evaluated per isMatch() call against the
     * current time, not frozen once in the constructor - otherwise a long-lived subscription's
     * rolling window becomes a fixed absolute timestamp that never re-admits a situation once
     * rejected as "too recent".
     */
    @Test
    public void testMinAgeWindowRollsForwardOnALongLivedFilterInstance() throws InterruptedException {
        SituationFilter longLived = filter(null, null, null, null, null, null, null, null, null,
                Duration.ofMillis(300), null);

        SituationUpdate justCreated = situation();
        justCreated.setCreationTime(ZonedDateTime.now());

        // Too recent the moment it is created.
        assertFalse(longLived.isMatch(justCreated));

        Thread.sleep(500);

        // The SAME filter instance, unchanged, must now admit it: minAge has actually
        // elapsed in wall-clock time. A filter that froze its cutoff at construction time
        // would incorrectly still reject this.
        assertTrue(longLived.isMatch(justCreated));
    }

    @Test
    public void testClosedSituationsAreExcludedByDefault() {
        SituationUpdate closed = situation();
        closed.setProgress(WorkflowStatusEnumeration.closed);

        assertFalse(filter(null, null, null, null, null, null, null, null, null, null, null).isMatch(closed));
        assertFalse(filter(null, null, null, null, null, null, null, null, null, null, false).isMatch(closed));
        assertTrue(filter(null, null, null, null, null, null, null, null, null, null, true).isMatch(closed));
    }

    @Test
    public void testReportType() {
        SituationUpdate situation = situation();

        SituationFilter matching = new SituationFilter(null, MetricType.QUERY, null, null, null, null, null,
                null, null, null, null, "general", null, null, null, null, null, null);
        assertTrue(matching.isMatch(situation));

        SituationFilter nonMatching = new SituationFilter(null, MetricType.QUERY, null, null, null, null, null,
                null, null, null, null, "incident", null, null, null, null, null, null);
        assertFalse(nonMatching.isMatch(situation));
    }

    @Test
    public void testDatedServiceJourneyId() {
        SituationUpdate situation = situationWithDatedServiceJourney();

        SituationFilter matching = new SituationFilter(null, MetricType.QUERY, null, null, null, null, null,
                null, "TST:DatedServiceJourney:1", null, null, null, null, null, null, null, null, null);
        assertTrue(matching.isMatch(situation));

        SituationFilter nonMatching = new SituationFilter(null, MetricType.QUERY, null, null, null, null, null,
                null, "TST:DatedServiceJourney:999", null, null, null, null, null, null, null, null, null);
        assertFalse(nonMatching.isMatch(situation));
    }

    @Test
    public void testSituationNumbers() {
        SituationFilter byNumber = new SituationFilter(null, MetricType.QUERY,
                Set.of("TST:SituationNumber:1"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertTrue(byNumber.isMatch(situation()));

        SituationFilter byOtherNumber = new SituationFilter(null, MetricType.QUERY,
                Set.of("TST:SituationNumber:999"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertFalse(byOtherNumber.isMatch(situation()));
    }

    @Test
    public void testBufferDefaults() {
        SituationFilter defaults = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(defaults.getBufferSize() > 0);
        assertTrue(defaults.getBufferTimeMillis() > 0);

        SituationFilter explicit = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 5, 100);
        assertTrue(explicit.getBufferSize() == 5);
        assertTrue(explicit.getBufferTimeMillis() == 100);
    }

    @Test
    void testMatchesASituationTaggedOnAnAncestorOfTheQueriedRef() {
        SituationUpdate atStopPlace = new SituationUpdate();
        atStopPlace.setSituationNumber("BNR:SituationNumber:1234-1234");
        atStopPlace.setAffects(new Affects());
        atStopPlace.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:451"));

        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:Quay:749", "NSR:StopPlace:451"),
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(atStopPlace))
                .withFailMessage("asking for a quay must also find situations on the stop place above it")
                .isTrue();
    }

    @Test
    void testDoesNotDescendFromAStopPlaceToItsQuays() {
        SituationUpdate atQuay = new SituationUpdate();
        atQuay.setSituationNumber("TST:SituationNumber:quay-only");
        atQuay.setAffects(new Affects());
        atQuay.getAffects().addStopPoint(new StopPoint("NSR:Quay:749"));

        // Expanding a stop place yields the stop place and anything above it - never its quays.
        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:451"),
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(atQuay))
                .withFailMessage("resolution climbs, never descends - asserting the non-goal so a "
                        + "later change cannot widen the contract silently")
                .isFalse();
    }

    @Test
    void testAStopPlaceQueryStillClimbsToItsMultimodalParent() {
        SituationUpdate atParent = new SituationUpdate();
        atParent.setSituationNumber("TST:SituationNumber:multimodal");
        atParent.setAffects(new Affects());
        atParent.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:999"));

        // Expanding NSR:StopPlace:451 yields itself plus the multimodal parent above it.
        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:451", "NSR:StopPlace:999"),
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(atParent))
                .withFailMessage("climbing is uniform - a stop place resolves to its own ancestors "
                        + "too, not just quays. A rule with an exception is what a later change "
                        + "gets quietly wrong.")
                .isTrue();
    }

    @Test
    void testANullStopRefSetStillMeansNoStopFilter() {
        SituationUpdate anywhere = new SituationUpdate();
        anywhere.setSituationNumber("TST:SituationNumber:anywhere");
        anywhere.setAffects(new Affects());
        anywhere.getAffects().addLine(new Line("TST:Line:1"));

        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(anywhere))
                .withFailMessage("a query with no stopRef must not be filtered by stop at all - "
                        + "treating an absent filter as 'match nothing' would empty every "
                        + "unfiltered situations query")
                .isTrue();
    }

    /**
     * An empty (as opposed to null) stopRefs set can only arise from a caller expanding a
     * null ref without the ternary both current call sites use - e.g. a future
     * {@code nsrService.expandWithAncestors(stopRef)} written directly. The constructor
     * normalises that to null, so it must mean "no stop filter", never "match nothing".
     */
    @Test
    void testAnEmptyStopRefSetAlsoMeansNoStopFilter() {
        SituationUpdate anywhere = new SituationUpdate();
        anywhere.setSituationNumber("TST:SituationNumber:anywhere-empty");
        anywhere.setAffects(new Affects());
        anywhere.getAffects().addLine(new Line("TST:Line:1"));

        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                Set.of(),
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(anywhere))
                .withFailMessage("an empty stopRefs set must be normalised the same as null - "
                        + "reading it as 'match nothing' would silently empty every unfiltered "
                        + "situations query a future caller writes without the null ternary")
                .isTrue();
    }

    /**
     * Filtering is discovery, matching is attachment. A client asking "what is going on at
     * Oslo S" must find a situation that names the stop only inside an affected journey,
     * even though that scoped stop deliberately never widens what the journey matcher attaches.
     */
    @Test
    public void testStopRefFindsAStopNamedOnlyInsideAnAffectedJourney() {
        SituationUpdate situation = situation();
        situation.getAffects().addVehicleJourney(new AffectedVehicleJourney(
                null,
                new DatedServiceJourney("TST:DatedServiceJourney:1"),
                null,
                null,
                List.of(new AffectedStop(new StopPoint("NSR:StopPlace:157"), List.of()))));

        SituationFilter matching = new SituationFilter(null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:157"), null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(matching.isMatch(situation));

        SituationFilter nonMatching = new SituationFilter(null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:999"), null, null, null, null, null, null, null, null, null, null, null);
        assertFalse(nonMatching.isMatch(situation));
    }
}
