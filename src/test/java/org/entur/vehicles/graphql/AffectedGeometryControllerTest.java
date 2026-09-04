package org.entur.vehicles.graphql;

import graphql.GraphQLContext;
import org.entur.vehicles.data.model.AffectedStop;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A situation naming N journeys is the normal case, not the exceptional one: a line-wide rail
 * closure names hundreds of dated journeys, and {@code PlannedDataset.stitchedGeometry}
 * deliberately does not cache. Unmemoized that is N full pattern stitches plus a linear scan per
 * stop per journey, per request. The journeys of one situation overwhelmingly share a handful of
 * patterns, so the stitched array is memoized per pattern in the request's {@code GraphQLContext}.
 * <p>
 * The resolver is per object rather than batched because the field is nullable and a
 * {@code List}-returning {@code @BatchMapping} cannot carry a null element - see the controller's
 * Javadoc. These tests therefore drive one journey at a time, sharing one context the way a
 * single request does.
 */
class AffectedGeometryControllerTest {

    private static final String LINK = "TST:ServiceLink:affected-geometry";
    private static final String PATTERN = "TST:JourneyPattern:affected-geometry";
    private static final String SERVICE_JOURNEY_1 = "TST:ServiceJourney:affected-geometry-1";
    private static final String SERVICE_JOURNEY_2 = "TST:ServiceJourney:affected-geometry-2";
    private static final String STOP_1 = "NSR:StopPlace:affected-geometry-1";
    private static final String STOP_2 = "NSR:StopPlace:affected-geometry-2";

    private PlannedDataset dataset;
    private AffectedGeometryController controller;

    @BeforeEach
    void setUp() {
        // Six points about 111 m apart, due north, shared by both journeys' pattern.
        int[] geometry = new int[12];
        for (int i = 0; i < 6; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        dataset = Mockito.spy(new PlannedDataset.Builder()
                .addServiceLink(LINK, geometry)
                .addJourneyPattern(PATTERN, List.of(LINK))
                .addServiceJourney(SERVICE_JOURNEY_1, PATTERN)
                .addServiceJourney(SERVICE_JOURNEY_2, PATTERN)
                .build());

        PlannedDataService plannedDataService = Mockito.mock(PlannedDataService.class);
        when(plannedDataService.current()).thenReturn(dataset);

        NSRService nsrService = Mockito.mock(NSRService.class);
        when(nsrService.getStop(STOP_1)).thenReturn(new StopPoint(STOP_1, "One", new Location(10.0, 59.001)));
        when(nsrService.getStop(STOP_2)).thenReturn(new StopPoint(STOP_2, "Two", new Location(10.0, 59.004)));

        controller = new AffectedGeometryController(plannedDataService, nsrService, 500, 25);
    }

    /**
     * Two journeys on one pattern must stitch that pattern once, not once each - the whole point
     * of the memo. It is keyed by journey pattern, not by service journey, because the journeys
     * of one situation are distinct objects sharing one route.
     */
    @Test
    void stitchesAPatternOnceForEveryJourneyInTheRequestThatSharesIt() {
        GraphQLContext context = GraphQLContext.newContext().build();

        PointsOnLink first = controller.affectedPointsOnLink(
                journey(SERVICE_JOURNEY_1, STOP_1, STOP_2), context);
        PointsOnLink second = controller.affectedPointsOnLink(
                journey(SERVICE_JOURNEY_2, STOP_1, STOP_2), context);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        // Vertices 1..4 - the span between the two stops, not the pattern's full six points.
        assertThat(first.getLength()).isEqualTo(4);
        assertThat(second.getLength()).isEqualTo(4);

        verify(dataset, times(1)).stitchedGeometry(PATTERN);
    }

    /** A second request shares nothing with the first - the memo dies with its context. */
    @Test
    void aFreshRequestStitchesAgainRatherThanReadingAnotherRequestsMemo() {
        controller.affectedPointsOnLink(journey(SERVICE_JOURNEY_1, STOP_1, STOP_2),
                GraphQLContext.newContext().build());
        controller.affectedPointsOnLink(journey(SERVICE_JOURNEY_1, STOP_1, STOP_2),
                GraphQLContext.newContext().build());

        verify(dataset, times(2)).stitchedGeometry(PATTERN);
    }

    /**
     * A journey with no span to draw resolves to null, and a null journey next to a resolvable
     * one must not disturb it. Nullability is the reason this resolver is not a
     * {@code @BatchMapping}: a {@code List}-returning batch method may not contain a null, and
     * Spring GraphQL fails the whole dispatch if it does.
     */
    @Test
    void aJourneyWithNoSpanResolvesToNullWithoutDisturbingItsNeighbours() {
        GraphQLContext context = GraphQLContext.newContext().build();

        assertThat(controller.affectedPointsOnLink(journey(SERVICE_JOURNEY_1, STOP_1), context))
                .withFailMessage("a single named stop is a point, not a span")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                journey("TST:ServiceJourney:unknown", STOP_1, STOP_2), context))
                .withFailMessage("an unknown journey has no pattern to cut")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                journey(SERVICE_JOURNEY_1, STOP_1, STOP_2), context)).isNotNull();
        assertThat(controller.affectedPointsOnLink(
                journey(SERVICE_JOURNEY_2, STOP_1, STOP_2), context)).isNotNull();
    }

    /**
     * A situation can affect a journey as a whole rather than at particular stops - the producer
     * names the journey and nests no stops under it. The affected part of a wholly affected
     * journey is the whole journey, so the field returns its full route rather than nothing,
     * sparing every client the special case of falling back to serviceJourney { pointsOnLink }.
     * An empty stops list stays the unambiguous signal that this is what happened.
     */
    @Test
    void aJourneyAffectedAsAWholeResolvesToItsEntireRoute() {
        PointsOnLink resolved = controller.affectedPointsOnLink(
                journey(SERVICE_JOURNEY_1), GraphQLContext.newContext().build());

        assertThat(resolved).isNotNull();
        // The pattern's full six points, not a span cut between named stops.
        assertThat(resolved.getLength()).isEqualTo(6);
    }

    /** A journey affected as a whole, whose pattern has no geometry, still resolves to null. */
    @Test
    void aWhollyAffectedJourneyWithNoKnownPatternIsStillNull() {
        assertThat(controller.affectedPointsOnLink(
                journey("TST:ServiceJourney:unknown"), GraphQLContext.newContext().build()))
                .isNull();
    }

    /** A journey failing the cheap checks must not touch the planned data at all. */
    @Test
    void aJourneyWithTooFewStopsNeverReadsTheDataset() {
        assertThat(controller.affectedPointsOnLink(
                journey(SERVICE_JOURNEY_1, STOP_1), GraphQLContext.newContext().build())).isNull();

        Mockito.verifyNoInteractions(dataset);
    }

    private static AffectedVehicleJourney journey(String serviceJourneyId, String... stopRefs) {
        List<AffectedStop> stops = new java.util.ArrayList<>();
        for (String stopRef : stopRefs) {
            stops.add(new AffectedStop(new StopPoint(stopRef), List.of()));
        }
        return new AffectedVehicleJourney(new ServiceJourney(serviceJourneyId), null, null, null, stops);
    }
}
