package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PolylineSlicerTest {

    private static final double MAX_SNAP_METERS = 500;

    /**
     * A due-north line of points one thousandth of a degree apart - about 111 m per step, so
     * every vertex is well outside the snap radius of its neighbours and a stop placed on one
     * can only project onto that one.
     */
    private static int[] straightLine(int points) {
        int[] geometry = new int[points * 2];
        for (int i = 0; i < points; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        return geometry;
    }

    private static Location at(int[] geometry, int index) {
        return new Location(geometry[index * 2 + 1] / 1e6, geometry[index * 2] / 1e6);
    }

    @Test
    public void testSpansTheTwoNamedStops() {
        int[] geometry = straightLine(6);

        PointsOnLink result = PolylineSlicer.slice(
                geometry, List.of(at(geometry, 1), at(geometry, 4)), MAX_SNAP_METERS);

        assertThat(result).isNotNull();
        // Vertices 1..4 inclusive.
        assertThat(result.getLength()).isEqualTo(4);
        assertThat(result.getPoints()).isNotEmpty();
    }

    /**
     * The producer named the ends and skipped a middle stop. The span is still continuous -
     * first to last - rather than two disconnected pieces.
     */
    @Test
    public void testAGapBetweenNamedStopsStillYieldsOneContinuousSpan() {
        int[] geometry = straightLine(8);

        PointsOnLink result = PolylineSlicer.slice(
                geometry, List.of(at(geometry, 1), at(geometry, 6)), MAX_SNAP_METERS);

        assertThat(result).isNotNull();
        assertThat(result.getLength()).isEqualTo(6);
    }

    /**
     * The case projection is weakest at. The route runs north then doubles straight back, so
     * every stop has two candidate vertices. Nearest-per-stop would be free to pick one from
     * the outbound leg and one from the return and span nearly the whole route; the tightest
     * window must stay on one leg.
     */
    @Test
    public void testAnOutAndBackRouteChoosesTheTightestWindow() {
        int[] out = straightLine(6);
        int[] geometry = new int[out.length * 2];
        System.arraycopy(out, 0, geometry, 0, out.length);
        for (int i = 0; i < 6; i++) {
            geometry[out.length + i * 2] = out[(5 - i) * 2];
            geometry[out.length + i * 2 + 1] = out[(5 - i) * 2 + 1];
        }

        PointsOnLink result = PolylineSlicer.slice(
                geometry, List.of(at(out, 1), at(out, 3)), MAX_SNAP_METERS);

        assertThat(result).isNotNull();
        // Vertices 1..3 of the outbound leg - three points. Picking one stop from the
        // outbound leg and the other from the return would give eight or more.
        assertThat(result.getLength()).isEqualTo(3);
    }

    @Test
    public void testAStopBeyondTheSnapRadiusSuppressesTheWholeSpan() {
        int[] geometry = straightLine(6);
        Location farAway = new Location(11.0, 59.0);

        assertThat(PolylineSlicer.slice(geometry, List.of(at(geometry, 1), farAway), MAX_SNAP_METERS))
                .isNull();
    }

    @Test
    public void testAStopWithoutCoordinatesSuppressesTheWholeSpan() {
        int[] geometry = straightLine(6);

        assertThat(PolylineSlicer.slice(geometry, Arrays.asList(at(geometry, 1), null), MAX_SNAP_METERS))
                .isNull();
    }

    @Test
    public void testASingleStopIsAPointNotASegment() {
        int[] geometry = straightLine(6);

        assertThat(PolylineSlicer.slice(geometry, List.of(at(geometry, 2)), MAX_SNAP_METERS)).isNull();
    }

    @Test
    public void testTwoStopsOnTheSameVertexYieldNothing() {
        int[] geometry = straightLine(6);

        assertThat(PolylineSlicer.slice(geometry, List.of(at(geometry, 2), at(geometry, 2)), MAX_SNAP_METERS))
                .isNull();
    }

    @Test
    public void testEmptyOrTooShortGeometryYieldsNothing() {
        List<Location> stops = new ArrayList<>(List.of(new Location(10.0, 59.0), new Location(10.0, 59.001)));

        assertThat(PolylineSlicer.slice(new int[0], stops, MAX_SNAP_METERS)).isNull();
        assertThat(PolylineSlicer.slice(new int[]{59_000_000, 10_000_000}, stops, MAX_SNAP_METERS)).isNull();
        assertThat(PolylineSlicer.slice(null, stops, MAX_SNAP_METERS)).isNull();
    }

    /**
     * A duplicated consecutive vertex on the approach to a stop makes the distance profile
     * flatten for one step while still descending. That shelf is not a local minimum, and
     * accepting it as a candidate would let a span anchor short of the true nearest vertex.
     */
    @Test
    public void testAShelfOnADescendingProfileIsNotACandidate() {
        // Vertices 0,1,2,3,4 where 1 and 2 are the same point, so the distance to a stop
        // sitting on vertex 3 flattens across them before continuing to fall.
        int[] geometry = {
                59_000_000, 10_000_000,
                59_001_000, 10_000_000,
                59_001_000, 10_000_000,
                59_002_000, 10_000_000,
                59_003_000, 10_000_000,
        };
        Location onVertexThree = new Location(10.0, 59.002);
        Location onVertexZero = new Location(10.0, 59.000);

        PointsOnLink result = PolylineSlicer.slice(
                geometry, List.of(onVertexZero, onVertexThree), MAX_SNAP_METERS);

        assertThat(result).isNotNull();
        // Vertices 0..3 inclusive - four points. Anchoring on the shelf at index 1 or 2
        // instead of the true minimum at index 0 would give three or fewer.
        assertThat(result.getLength()).isEqualTo(4);
    }
}
