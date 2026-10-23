package org.entur.vehicles.data.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * A call time packed into one {@code long}: epoch microseconds shifted left by 8 bits, with the
 * UTC offset in quarter-hours in the low byte.
 * <p>
 * {@link Call} holds up to six timestamps for every stop of every journey kept for a day. As
 * {@code ZonedDateTime}s each one was four objects - {@code ZonedDateTime}, {@code LocalDateTime},
 * {@code LocalDate}, {@code LocalTime}, 96 bytes - and they made up a large part of the live heap.
 * <p>
 * Packing is lossless for what the feeds send: offsets on a quarter-hour and at most microsecond
 * precision. Anything else - nanoseconds, a region zone such as {@code [Europe/Oslo]}, an odd
 * offset - is reported as {@link #EXACT}, and the caller keeps the {@code ZonedDateTime} itself,
 * so what clients see never changes.
 */
public final class PackedTime {

    /** No timestamp. */
    public static final long NONE = Long.MIN_VALUE;

    /** A timestamp that cannot be packed losslessly; the caller holds it as a ZonedDateTime. */
    public static final long EXACT = Long.MIN_VALUE + 1;

    private static final long MICROS_PER_SECOND = 1_000_000;
    private static final int SECONDS_PER_QUARTER_HOUR = 900;
    // Both markers decode to -2^55 micros, so valid packed values stay strictly inside this range
    private static final long MAX_MICROS = (1L << 55) - 1;
    private static final long MAX_EPOCH_SECOND = MAX_MICROS / MICROS_PER_SECOND - 1;

    private PackedTime() {
    }

    public static long pack(ZonedDateTime time) {
        if (time == null) {
            return NONE;
        }
        if (!(time.getZone() instanceof ZoneOffset offset)) {
            return EXACT;
        }
        long epochSecond = time.toEpochSecond();
        int nano = time.getNano();
        if (nano % 1000 != 0) {
            return EXACT;
        }
        return pack(epochSecond, nano / 1000, offset.getTotalSeconds());
    }

    private static long pack(long epochSecond, int micro, int offsetSeconds) {
        if (offsetSeconds % SECONDS_PER_QUARTER_HOUR != 0
                || epochSecond > MAX_EPOCH_SECOND || epochSecond < -MAX_EPOCH_SECOND) {
            return EXACT;
        }
        long micros = epochSecond * MICROS_PER_SECOND + micro;
        return (micros << 8) | ((offsetSeconds / SECONDS_PER_QUARTER_HOUR) & 0xFF);
    }

    /**
     * The packed form of a timestamp equal to {@code ZonedDateTime.parse(text)}, or {@link #EXACT}
     * when the text is outside the one strict format handled here:
     * {@code uuuu-MM-ddTHH:mm:ss[.S{1,6}](Z|+HH:MM|-HH:MM)}. The caller then parses it the usual
     * way, which also rejects malformed text exactly as before.
     */
    public static long parse(CharSequence text) {
        int length = text.length();
        if (length < 20
                || text.charAt(4) != '-' || text.charAt(7) != '-' || text.charAt(10) != 'T'
                || text.charAt(13) != ':' || text.charAt(16) != ':') {
            return EXACT;
        }
        int year = digits(text, 0, 4);
        int month = digits(text, 5, 2);
        int day = digits(text, 8, 2);
        int hour = digits(text, 11, 2);
        int minute = digits(text, 14, 2);
        int second = digits(text, 17, 2);
        if (year < 0 || month < 1 || month > 12 || day < 1
                || day > Month.of(month).length(Year.isLeap(year))
                || hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
            return EXACT;
        }

        int position = 19;
        int micro = 0;
        if (text.charAt(position) == '.') {
            int start = ++position;
            while (position < length && isDigit(text.charAt(position))) {
                position++;
            }
            int fractionDigits = position - start;
            if (fractionDigits < 1 || fractionDigits > 6) {
                return EXACT;
            }
            micro = digits(text, start, fractionDigits);
            for (int i = fractionDigits; i < 6; i++) {
                micro *= 10;
            }
        }

        int offsetSeconds;
        if (position == length - 1 && text.charAt(position) == 'Z') {
            offsetSeconds = 0;
        } else if (position == length - 6 && text.charAt(position + 3) == ':'
                && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
            int offsetHours = digits(text, position + 1, 2);
            int offsetMinutes = digits(text, position + 4, 2);
            if (offsetHours < 0 || offsetHours > 18 || offsetMinutes < 0 || offsetMinutes > 59
                    || (offsetHours == 18 && offsetMinutes > 0)) {
                return EXACT;
            }
            offsetSeconds = offsetHours * 3600 + offsetMinutes * 60;
            if (text.charAt(position) == '-') {
                offsetSeconds = -offsetSeconds;
            }
        } else {
            return EXACT;
        }

        long epochSecond = LocalDate.of(year, month, day).toEpochDay() * 86_400
                + hour * 3600L + minute * 60L + second - offsetSeconds;
        return pack(epochSecond, micro, offsetSeconds);
    }

    /** The timestamp a packed value holds; null for {@link #NONE}. Not defined for {@link #EXACT}. */
    public static ZonedDateTime unpack(long packed) {
        if (packed == NONE) {
            return null;
        }
        long micros = packed >> 8;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds((byte) packed * SECONDS_PER_QUARTER_HOUR);
        Instant instant = Instant.ofEpochSecond(
                Math.floorDiv(micros, MICROS_PER_SECOND), Math.floorMod(micros, MICROS_PER_SECOND) * 1000);
        return ZonedDateTime.ofInstant(instant, offset);
    }

    /** Seconds since the epoch, as {@link ZonedDateTime#toEpochSecond()}. Not defined for the markers. */
    public static long epochSecond(long packed) {
        return Math.floorDiv(packed >> 8, MICROS_PER_SECOND);
    }

    private static int digits(CharSequence text, int start, int count) {
        int value = 0;
        for (int i = start; i < start + count; i++) {
            char c = text.charAt(i);
            if (!isDigit(c)) {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
