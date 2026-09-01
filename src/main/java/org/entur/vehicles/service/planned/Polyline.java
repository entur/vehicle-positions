package org.entur.vehicles.service.planned;

import java.util.List;

/**
 * Pure geometry helpers. Coordinates are interleaved lat/lon in microdegrees (1e-6).
 */
final class Polyline {

    private Polyline() {}

    /**
     * Concatenates link geometries in order. When a link starts exactly where the previous
     * geometry ended, that shared join point is emitted once. Empty links are skipped, so a
     * link without geometry leaves a gap rather than breaking the sequence.
     */
    static int[] stitch(List<int[]> links) {
        int total = 0;
        for (int[] link : links) {
            total += link.length;
        }
        int[] out = new int[total];
        int n = 0;
        for (int[] link : links) {
            if (link.length == 0) {
                continue;
            }
            int from = 0;
            if (n >= 2 && link.length >= 2 && link[0] == out[n - 2] && link[1] == out[n - 1]) {
                from = 2;
            }
            System.arraycopy(link, from, out, n, link.length - from);
            n += link.length - from;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * Google encoded polyline at 5-decimal precision - the format OTP/JourneyPlanner
     * returns in {@code pointsOnLink.points}.
     */
    static String encode(int[] microDegrees) {
        StringBuilder sb = new StringBuilder(microDegrees.length * 3);
        int prevLat = 0;
        int prevLon = 0;
        for (int i = 0; i + 1 < microDegrees.length; i += 2) {
            int lat = toFiveDecimals(microDegrees[i]);
            int lon = toFiveDecimals(microDegrees[i + 1]);
            encodeValue(lat - prevLat, sb);
            encodeValue(lon - prevLon, sb);
            prevLat = lat;
            prevLon = lon;
        }
        return sb.toString();
    }

    private static int toFiveDecimals(int microDegrees) {
        return (int) Math.round(microDegrees / 10.0);
    }

    private static void encodeValue(int value, StringBuilder sb) {
        int v = value << 1;
        if (value < 0) {
            v = ~v;
        }
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }
}
