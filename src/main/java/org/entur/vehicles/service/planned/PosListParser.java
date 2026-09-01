package org.entur.vehicles.service.planned;

/**
 * Parses a {@code gis:posList} text node into interleaved microdegrees without allocating
 * a String per token. The full export holds ~20 million coordinates, so the per-token cost
 * of {@code split()} + {@code Double.parseDouble()} is what this avoids.
 * Malformed tokens (no digits, or trailing non-numeric characters) yield 0 or the digits
 * parsed so far; the parser never throws.
 */
final class PosListParser {

    private PosListParser() {}

    static int[] parse(CharSequence text) {
        int n = text.length();
        // Count tokens first so the result array is exact.
        int tokens = 0;
        boolean inToken = false;
        for (int i = 0; i < n; i++) {
            boolean ws = Character.isWhitespace(text.charAt(i));
            if (!ws && !inToken) {
                tokens++;
            }
            inToken = !ws;
        }
        int[] out = new int[tokens];
        int k = 0;
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            boolean negative = false;
            if (text.charAt(i) == '-') {
                negative = true;
                i++;
            } else if (text.charAt(i) == '+') {
                i++;
            }
            long integerPart = 0;
            while (i < n && isDigit(text.charAt(i))) {
                integerPart = integerPart * 10 + (text.charAt(i) - '0');
                i++;
            }
            long micro = integerPart * 1_000_000L;
            if (i < n && text.charAt(i) == '.') {
                i++;
                long scale = 100_000L;
                while (i < n && isDigit(text.charAt(i))) {
                    int digit = text.charAt(i) - '0';
                    if (scale > 0) {
                        micro += digit * scale;
                        scale /= 10;
                    } else if (scale == 0) {
                        // 7th decimal: round half up, then ignore the rest
                        if (digit >= 5) {
                            micro++;
                        }
                        scale = -1;
                    }
                    i++;
                }
            }
            // Skip anything else up to the next whitespace (e.g. an exponent we do not expect)
            while (i < n && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            out[k++] = (int) (negative ? -micro : micro);
        }
        return out;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
