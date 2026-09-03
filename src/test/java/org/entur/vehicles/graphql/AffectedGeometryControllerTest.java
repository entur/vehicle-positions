package org.entur.vehicles.graphql;

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
 * The resolver is batched rather than per-object because a situation naming N journeys is the
 * normal case, not the exceptional one: a line-wide rail closure names hundreds of dated
 * journeys, and {@code PlannedDataset.stitchedGeometry} deliberately does not cache. Per object
 * that is N full pattern stitches plus a linear scan per stop per journey, per request. The
 * journeys of one situation overwhelmingly share a handful of patterns, so memoizing the
 * stitched array per pattern within the batch collapses the fan-out.
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

        controller = new AffectedGeometryController(plannedDataService, nsrService, 500);
    }

    /**
     * Two journeys on one pattern must stitch that pattern once, not once each - the whole point
     * of the batch. The memo is keyed by journey pattern, not by service journey, because the
     * journeys of one situation are distinct objects sharing one route.
     */
    @Test
    void stitchesAPatternOnceForEveryJourneyInTheBatchThatSharesIt() {
        List<PointsOnLink> resolved = controller.affectedPointsOnLink(List.of(
                journey(SERVICE_JOURNEY_1, STOP_1, STOP_2),
                journey(SERVICE_JOURNEY_2, STOP_1, STOP_2)));

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0)).isNotNull();
        assertThat(resolved.get(1)).isNotNull();
        // Vertices 1..4 - the span between the two stops, not the pattern's full six points.
        assertThat(resolved.get(0).getLength()).isEqualTo(4);
        assertThat(resolved.get(1).getLength()).isEqualTo(4);

        verify(dataset, times(1)).stitchedGeometry(PATTERN);
    }

    /**
     * The batch returns a list aligned by position with its input, exactly as
     * {@link SituationJoinController} does and for the same reason - see its Javadoc. Entries
     * that resolve to no polyline hold their slot as null rather than being dropped, or every
     * journey after them would receive another journey's geometry.
     */
    @Test
    void returnsAListPositionallyAlignedWithItsInputIncludingTheNulls() {
        List<PointsOnLink> resolved = controller.affectedPointsOnLink(List.of(
                journey(SERVICE_JOURNEY_1, STOP_1),
                journey(SERVICE_JOURNEY_1, STOP_1, STOP_2),
                journey("TST:ServiceJourney:unknown", STOP_1, STOP_2),
                journey(SERVICE_JOURNEY_2, STOP_1, STOP_2)));

        assertThat(resolved).hasSize(4);
        assertThat(resolved.get(0)).withFailMessage("a single named stop is a point, not a span").isNull();
        assertThat(resolved.get(1)).isNotNull();
        assertThat(resolved.get(2)).withFailMessage("an unknown journey has no pattern to cut").isNull();
        assertThat(resolved.get(3)).isNotNull();
    }

    /** An empty batch must not touch the planned data at all. */
    @Test
    void anEmptyBatchResolvesToAnEmptyList() {
        assertThat(controller.affectedPointsOnLink(List.of())).isEmpty();
    }

    private static AffectedVehicleJourney journey(String serviceJourneyId, String... stopRefs) {
        List<AffectedStop> stops = new java.util.ArrayList<>();
        for (String stopRef : stopRefs) {
            stops.add(new AffectedStop(new StopPoint(stopRef), List.of()));
        }
        return new AffectedVehicleJourney(new ServiceJourney(serviceJourneyId), null, null, null, stops);
    }
}
