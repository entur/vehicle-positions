package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Cuts the part of a route geometry that a set of stops spans.
 * <p>
 * Stops are located by projection rather than by an extracted stop sequence - see the design
 * spec's Approach section for why, and for the trade-off. Projection alone has no concept of
 * route order, so two rules guard it: candidates are every local minimum within a snap radius
 * rather than the single nearest vertex, and the window chosen is the shortest run of the
 * route touching all of them. A stop that cannot be located suppresses the result entirely
 * rather than silently shrinking the span.
 * <p>
 * Coordinates are interleaved lat/lon microdegrees, as everywhere else in this package.
 */
public final class PolylineSlicer {

    /** Guards step 5 against a pathological geometry: the nearest candidates are kept. */
    private static final int MAX_CANDIDATES_PER_STOP = 64;

    private static final double METERS_PER_DEGREE = 111_320.0;

    private PolylineSlicer() {}

    /**
     * @param geometry      interleaved lat/lon microdegrees
     * @param stops         the affected stops' locations, in any order; a null entry means the
     *                      stop has no known location
     * @param maxSnapMeters how far a stop may sit from the geometry and still count as on it
     * @return the span between the first and last stop, or null when it cannot be computed
     */
    public static PointsOnLink slice(int[] geometry, List<Location> stops, double maxSnapMeters) {
        if (geometry == null || geometry.length < 4 || stops == null || stops.size() < 2) {
            return null;
        }
        List<int[]> candidates = new ArrayList<>(stops.size());
        for (Location stop : stops) {
            if (stop == null || stop.getLatitude() == null || stop.getLongitude() == null) {
                return null;
            }
            int[] forStop = candidatesFor(geometry, stop, maxSnapMeters);
            if (forStop.length == 0) {
                return null;
            }
            candidates.add(forStop);
        }

        int[] window = tightestWindow(candidates);
        if (window[0] >= window[1]) {
            return null;
        }

        int[] cut = Arrays.copyOfRange(geometry, window[0] * 2, window[1] * 2 + 2);
        PointsOnLink pointsOnLink = new PointsOnLink();
        pointsOnLink.setLength(cut.length / 2);
        pointsOnLink.setPoints(Polyline.encode(cut));
        return pointsOnLink;
    }

    /**
     * Every local minimum of the distance to this stop that lies within the snap radius, in
     * ascending index order. A plateau of equal distances contributes its first index only.
     */
    private static int[] candidatesFor(int[] geometry, Location stop, double maxSnapMeters) {
        int points = geometry.length / 2;
        double latitude = stop.getLatitude();
        double longitude = stop.getLongitude();
        // Hoisted out of the loop, so the scan itself needs neither trigonometry nor a square
        // root: distances are compared squared.
        double lonScale = METERS_PER_DEGREE * Math.cos(Math.toRadians(latitude));
        double limitSquared = maxSnapMeters * maxSnapMeters;

        double[] distances = new double[points];
        for (int i = 0; i < points; i++) {
            double dLat = (geometry[i * 2] / 1e6 - latitude) * METERS_PER_DEGREE;
            double dLon = (geometry[i * 2 + 1] / 1e6 - longitude) * lonScale;
            distances[i] = dLat * dLat + dLon * dLon;
        }

        List<Integer> minima = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            boolean risingFromTheLeft = i == 0 || distances[i] < distances[i - 1];
            boolean notRisingToTheRight = i == points - 1 || distances[i] <= distances[i + 1];
            if (risingFromTheLeft && notRisingToTheRight && distances[i] <= limitSquared) {
                minima.add(i);
            }
        }
        if (minima.size() > MAX_CANDIDATES_PER_STOP) {
            minima.sort(Comparator.comparingDouble(index -> distances[index]));
            minima = new ArrayList<>(minima.subList(0, MAX_CANDIDATES_PER_STOP));
            minima.sort(Comparator.naturalOrder());
        }

        int[] result = new int[minima.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = minima.get(i);
        }
        return result;
    }

    /**
     * The shortest index window containing one candidate from every stop - the classic
     * smallest-range-covering-k-lists problem, by k-way merge. This is what keeps an
     * out-and-back route from spanning both legs.
     */
    private static int[] tightestWindow(List<int[]> candidates) {
        int lists = candidates.size();
        int[] pointer = new int[lists];
        // Safe despite the mutable pointer array: a list's key changes only while that list is
        // outside the heap, between the poll that removed it and the add that returns it.
        PriorityQueue<Integer> heap =
                new PriorityQueue<>(Comparator.comparingInt(i -> candidates.get(i)[pointer[i]]));

        int high = Integer.MIN_VALUE;
        for (int i = 0; i < lists; i++) {
            heap.add(i);
            high = Math.max(high, candidates.get(i)[0]);
        }

        int bestLow = candidates.get(heap.peek())[0];
        int bestHigh = high;

        while (true) {
            int list = heap.poll();
            int low = candidates.get(list)[pointer[list]];
            if (high - low < bestHigh - bestLow) {
                bestLow = low;
                bestHigh = high;
            }
            pointer[list]++;
            if (pointer[list] == candidates.get(list).length) {
                break;
            }
            high = Math.max(high, candidates.get(list)[pointer[list]]);
            heap.add(list);
        }
        return new int[]{bestLow, bestHigh};
    }
}
