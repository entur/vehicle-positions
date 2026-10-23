package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PackedTime} must be indistinguishable from {@code ZonedDateTime.parse} for everything it
 * accepts - same instant, same offset, same text through the GraphQL {@code DateTime} scalar - and
 * must hand back {@link PackedTime#EXACT} for everything else, so the caller falls back to
 * {@code ZonedDateTime.parse} and its exceptions.
 */
class PackedTimeTest {

    private static final DateTimeFormatter SCALAR_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static void assertPacksLikeZonedDateTime(String text) {
        ZonedDateTime expected = ZonedDateTime.parse(text);
        long packed = PackedTime.parse(text);

        assertThat(packed).as(text).isNotEqualTo(PackedTime.EXACT).isNotEqualTo(PackedTime.NONE);
        assertThat(PackedTime.unpack(packed)).as(text).isEqualTo(expected);
        assertThat(PackedTime.unpack(packed).format(SCALAR_FORMAT)).as(text).isEqualTo(expected.format(SCALAR_FORMAT));
        assertThat(PackedTime.epochSecond(packed)).as(text).isEqualTo(expected.toEpochSecond());
        assertThat(PackedTime.pack(expected)).as(text).isEqualTo(packed);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-10-23T10:50:00Z",
            "2026-10-23T10:50:00.1Z",
            "2026-10-23T10:50:00.12Z",
            "2026-10-23T10:50:00.123Z",
            "2026-10-23T10:50:00.1234Z",
            "2026-10-23T10:50:00.12345Z",
            "2026-10-23T10:50:00.123456Z",
            "2026-10-23T10:50:00.100Z",
            "2026-10-23T12:50:00+02:00",
            "2026-10-23T12:50:00.5+02:00",
            "2026-10-23T05:20:00-05:30",
            "2026-10-23T16:35:00+05:45",
            "2026-10-23T10:50:00+00:00",
            "2026-10-23T10:50:00-00:00",
            "2026-10-24T04:50:00+18:00",
            "2026-10-22T16:50:00-18:00",
            "2024-02-29T23:59:59.999999Z",
            "1960-01-01T00:00:00.5Z",
            "1969-12-31T23:59:59.999999Z",
            "0830-01-01T00:00:00Z",
            "3110-12-31T23:59:59Z",
    })
    void aTimestampInTheFeedFormatPacksLosslessly(String text) {
        assertPacksLikeZonedDateTime(text);
    }

    /** Valid timestamps outside the fast path: the caller keeps the ZonedDateTime itself. */
    @ParameterizedTest
    @ValueSource(strings = {
            "2026-10-23T10:50:00.1234567Z",
            "2026-10-23T10:50:00.123456789Z",
            "2026-10-23T12:50:00+02:00[Europe/Oslo]",
            "2026-10-23T12:50:00.123456789+02:00[Europe/Oslo]",
            "2026-10-23T10:50Z",
            "2026-10-23T12:50:00+02:00:30",
            "2026-10-23T12:50:00+02:10",
            // Beyond the ~1141 years either side of 1970 that 55 bits of microseconds cover
            "0000-01-01T00:00:00Z",
            "9999-12-31T23:59:59Z",
    })
    void aValidTimestampThatDoesNotPackIsReportedExact(String text) {
        ZonedDateTime.parse(text);

        assertThat(PackedTime.parse(text)).as(text).isEqualTo(PackedTime.EXACT);
    }

    /** Malformed text is never accepted by the fast path; the fallback parse then throws as before. */
    @ParameterizedTest
    @ValueSource(strings = {
            "2026-13-23T10:50:00Z",
            "2026-00-23T10:50:00Z",
            "2026-02-29T10:50:00Z",
            "2026-04-31T10:50:00Z",
            "2026-10-00T10:50:00Z",
            "2026-10-23T24:00:00Z",
            "2026-10-23T10:60:00Z",
            "2026-10-23T10:50:60Z",
            "2026-10-23T10:50:00",
            "2026-10-23T10:50:00.Z",
            "2026-10-23T10:50:00+19:00",
            "2026-10-23T10:50:00+02:60",
            "2026-10-23T10:50:00+0200",
            "2026-10-23 10:50:00Z",
            "2026-1O-23T10:50:00Z",
            "2026-10-23T10:50:00Zx",
            "",
    })
    void malformedTextIsNeverPacked(String text) {
        assertThat(PackedTime.parse(text)).as(text).isEqualTo(PackedTime.EXACT);
    }

    @Test
    void randomFeedTimestampsPackLikeZonedDateTime() {
        Random random = new Random(20261023);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder(String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                    1900 + random.nextInt(300), 1 + random.nextInt(12), 1 + random.nextInt(28),
                    random.nextInt(24), random.nextInt(60), random.nextInt(60)));
            int fractionDigits = random.nextInt(7);
            if (fractionDigits > 0) {
                text.append('.');
                for (int d = 0; d < fractionDigits; d++) {
                    text.append((char) ('0' + random.nextInt(10)));
                }
            }
            int quarterHours = random.nextInt(145) - 72;
            if (quarterHours == 0 && random.nextBoolean()) {
                text.append('Z');
            } else {
                int minutes = Math.abs(quarterHours) * 15;
                text.append(quarterHours < 0 ? '-' : '+').append(String.format("%02d:%02d", minutes / 60, minutes % 60));
            }
            assertPacksLikeZonedDateTime(text.toString());
        }
    }

    @Test
    void noneUnpacksToNull() {
        assertThat(PackedTime.pack(null)).isEqualTo(PackedTime.NONE);
        assertThat(PackedTime.unpack(PackedTime.NONE)).isNull();
    }
}
