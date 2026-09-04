package org.entur.vehicles.graphql;

import graphql.GraphQLContext;
import org.entur.vehicles.data.model.AffectedLine;
import org.entur.vehicles.data.model.AffectedStop;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Line;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A line has many journey patterns, so a line-level situation is drawn on a representative: the
 * first pattern its affected stops actually locate on, tried longest-first. The fixture is a line
 * with two disjoint patterns - a six-point one along lon 10 and a four-point one along lon 11,
 * about 57 km apart - so "the stops fit the second pattern, not the first" is a real geometric
 * fact here rather than something the test asserts by construction.
 */
class AffectedGeometryLineTest {

    private static final String LINE = "TST:Line:affected-line";
    private static final String LONG_PATTERN = "TST:JourneyPattern:affected-line-long";
    private static final String SHORT_PATTERN = "TST:JourneyPattern:affected-line-short";
    private static final String LONG_LINK = "TST:ServiceLink:affected-line-long";
    private static final String SHORT_LINK = "TST:ServiceLink:affected-line-short";
    private static final String JOURNEY_ON_LONG = "TST:ServiceJourney:affected-line-long";
    private static final String JOURNEY_ON_SHORT = "TST:ServiceJourney:affected-line-short";
    private static final String STOP_ON_LONG_1 = "NSR:StopPlace:affected-line-long-1";
    private static final String STOP_ON_LONG_2 = "NSR:StopPlace:affected-line-long-2";
    private static final String STOP_ON_SHORT_1 = "NSR:StopPlace:affected-line-short-1";
    private static final String STOP_ON_SHORT_2 = "NSR:StopPlace:affected-line-short-2";
    private static final String STOP_NOWHERE = "NSR:StopPlace:affected-line-nowhere";
    private static final String STOP_UNKNOWN_TO_NSR = "NSR:StopPlace:affected-line-unknown";

    private PlannedDataset dataset;
    private PlannedDataService plannedDataService;
    private NSRService nsrService;
    private AffectedGeometryController controller;

    @BeforeEach
    void setUp() {
        int[] longGeometry = new int[12];
        for (int i = 0; i < 6; i++) {
            longGeometry[i * 2] = 59_000_000 + i * 1_000;
            longGeometry[i * 2 + 1] = 10_000_000;
        }
        int[] shortGeometry = new int[8];
        for (int i = 0; i < 4; i++) {
            shortGeometry[i * 2] = 59_000_000 + i * 1_000;
            shortGeometry[i * 2 + 1] = 11_000_000;
        }
        dataset = Mockito.spy(new PlannedDataset.Builder()
                .addServiceLink(LONG_LINK, longGeometry)
                .addServiceLink(SHORT_LINK, shortGeometry)
                .addJourneyPattern(LONG_PATTERN, List.of(LONG_LINK))
                .addJourneyPattern(SHORT_PATTERN, List.of(SHORT_LINK))
                .addLine(LINE, "Affected line", "31")
                .addServiceJourney(JOURNEY_ON_LONG, LONG_PATTERN, LINE)
                .addServiceJourney(JOURNEY_ON_SHORT, SHORT_PATTERN, LINE)
                .build());

        plannedDataService = Mockito.mock(PlannedDataService.class);
        when(plannedDataService.current()).thenReturn(dataset);

        nsrService = Mockito.mock(NSRService.class);
        when(nsrService.getStop(STOP_ON_LONG_1))
                .thenReturn(new StopPoint(STOP_ON_LONG_1, "Long one", new Location(10.0, 59.001)));
        when(nsrService.getStop(STOP_ON_LONG_2))
                .thenReturn(new StopPoint(STOP_ON_LONG_2, "Long two", new Location(10.0, 59.004)));
        when(nsrService.getStop(STOP_ON_SHORT_1))
                .thenReturn(new StopPoint(STOP_ON_SHORT_1, "Short one", new Location(11.0, 59.000)));
        when(nsrService.getStop(STOP_ON_SHORT_2))
                .thenReturn(new StopPoint(STOP_ON_SHORT_2, "Short two", new Location(11.0, 59.002)));
        when(nsrService.getStop(STOP_NOWHERE))
                .thenReturn(new StopPoint(STOP_NOWHERE, "Nowhere", new Location(5.0, 62.0)));
        // Unknown to NSR - also what every stop looks like when NSR lookup is disabled.
        when(nsrService.getStop(STOP_UNKNOWN_TO_NSR)).thenReturn(null);

        controller = new AffectedGeometryController(plannedDataService, nsrService, 500, 25);
    }

    /**
     * A line affected as a whole gets its longest pattern's entire route - and exactly the value
     * ServiceJourney.pointsOnLink serves for a journey on that pattern, so a client drawing both
     * never sees two encodings of one shape.
     */
    @Test
    void aLineAffectedAsAWholeResolvesToItsLongestPatternsEntireRoute() {
        PointsOnLink resolved = controller.affectedPointsOnLink(
                affectedLine(LINE), GraphQLContext.newContext().build());

        assertThat(resolved).isNotNull();
        assertThat(resolved.getLength()).isEqualTo(6);
        assertThat(resolved).isSameAs(dataset.pointsOnLink(LONG_PATTERN));
    }

    /** The case the whole design exists for: the stops fit the shorter, second-tried pattern. */
    @Test
    void picksTheFirstPatternTheAffectedStopsActuallyLocateOn() {
        PointsOnLink resolved = controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_SHORT_1, STOP_ON_SHORT_2),
                GraphQLContext.newContext().build());

        assertThat(resolved).isNotNull();
        // Vertices 0..2 of the four-point pattern, not any part of the six-point one.
        assertThat(resolved.getLength()).isEqualTo(3);
        verify(dataset).stitchedGeometry(LONG_PATTERN);
        verify(dataset).stitchedGeometry(SHORT_PATTERN);
    }

    /** Stops on the longest pattern are answered by the first attempt - the later one is never stitched. */
    @Test
    void stopsOnTheLongestPatternStopTheSearchAtTheFirstPattern() {
        PointsOnLink resolved = controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_ON_LONG_2),
                GraphQLContext.newContext().build());

        assertThat(resolved).isNotNull();
        assertThat(resolved.getLength()).isEqualTo(4);
        verify(dataset, Mockito.never()).stitchedGeometry(SHORT_PATTERN);
    }

    @Test
    void aLineWithNoSpanToDrawResolvesToNull() {
        GraphQLContext context = GraphQLContext.newContext().build();

        assertThat(controller.affectedPointsOnLink(affectedLine(LINE, STOP_ON_LONG_1), context))
                .withFailMessage("a single named stop is a point, not a span")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                affectedLine("TST:Line:unknown", STOP_ON_LONG_1, STOP_ON_LONG_2), context))
                .withFailMessage("a line the planned data does not know has no pattern to cut")
                .isNull();
        assertThat(controller.affectedPointsOnLink(affectedLine("TST:Line:unknown"), context))
                .withFailMessage("nor when it is affected as a whole")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_NOWHERE), context))
                .withFailMessage("a stop off every pattern suppresses the span entirely")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_UNKNOWN_TO_NSR), context))
                .withFailMessage("a stop NSR cannot locate suppresses the span, as on the journey field")
                .isNull();
    }

    /**
     * The one place the line field is more useful than the journey field: a line affected as a
     * whole needs no stop coordinates, so it still draws when NSR lookup is disabled.
     */
    @Test
    void aLineAffectedAsAWholeNeedsNoStopCoordinates() {
        NSRService withoutLookup = Mockito.mock(NSRService.class);
        when(withoutLookup.getStop(Mockito.anyString())).thenReturn(null);
        AffectedGeometryController withoutNsr = new AffectedGeometryController(
                plannedDataService, withoutLookup, 500, 25);

        assertThat(withoutNsr.affectedPointsOnLink(affectedLine(LINE), GraphQLContext.newContext().build()))
                .isNotNull();
    }

    /** A line entry failing the cheap checks must not touch the planned data at all. */
    @Test
    void aLineWithTooFewStopsNeverReadsTheDataset() {
        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1), GraphQLContext.newContext().build())).isNull();

        Mockito.verifyNoInteractions(dataset);
    }

    /**
     * A line with many variants and stops that fit a late one must not stitch its way through all
     * of them on every request. The cap is a cost bound, not a correctness rule: longest-first
     * ordering means what it drops is the least representative shapes.
     */
    @Test
    void stopsFittingOnlyAPatternBeyondTheCapResolveToNull() {
        AffectedGeometryController capped = new AffectedGeometryController(
                plannedDataService, nsrService, 500, 1);

        assertThat(capped.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_SHORT_1, STOP_ON_SHORT_2),
                GraphQLContext.newContext().build()))
                .withFailMessage("only the longest pattern may be tried when the cap is 1")
                .isNull();
        verify(dataset).stitchedGeometry(LONG_PATTERN);
        verify(dataset, Mockito.never()).stitchedGeometry(SHORT_PATTERN);
    }

    /** The cap bounds the search, never the whole-line case - that reads one pattern by index. */
    @Test
    void theCapDoesNotAffectALineAffectedAsAWhole() {
        AffectedGeometryController capped = new AffectedGeometryController(
                plannedDataService, nsrService, 500, 1);

        assertThat(capped.affectedPointsOnLink(affectedLine(LINE), GraphQLContext.newContext().build()))
                .isNotNull();
    }

    /**
     * A situation naming a line and journeys on that line is the common shape - a line-wide
     * closure lists the line and its cancelled journeys. The line resolver shares the journey
     * resolver's per-request memo, so their shared pattern is stitched once for the request.
     */
    @Test
    void aLineAndAJourneyOnTheSamePatternStitchItOnceForTheRequest() {
        GraphQLContext context = GraphQLContext.newContext().build();

        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_ON_LONG_2), context)).isNotNull();
        assertThat(controller.affectedPointsOnLink(
                journeyOn(JOURNEY_ON_LONG, STOP_ON_LONG_1, STOP_ON_LONG_2), context)).isNotNull();

        verify(dataset, times(1)).stitchedGeometry(LONG_PATTERN);
    }

    private static AffectedLine affectedLine(String lineRef, String... stopRefs) {
        return new AffectedLine(new Line(lineRef), stops(stopRefs));
    }

    private static AffectedVehicleJourney journeyOn(String serviceJourneyId, String... stopRefs) {
        return new AffectedVehicleJourney(
                new ServiceJourney(serviceJourneyId), null, null, null, stops(stopRefs));
    }

    private static List<AffectedStop> stops(String... stopRefs) {
        List<AffectedStop> stops = new ArrayList<>();
        for (String stopRef : stopRefs) {
            stops.add(new AffectedStop(new StopPoint(stopRef), List.of()));
        }
        return stops;
    }
}
