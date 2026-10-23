package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Call} stores its times packed, and keeps a {@code ZonedDateTime} only when one does not
 * pack losslessly. Either way the getters must return exactly what was set or parsed.
 */
class CallTimesTest {

    @Test
    void aParsedFeedTimeReadsBackAsZonedDateTimeParseWouldGiveIt() {
        Call call = new Call();
        call.parseAimedArrivalTime("2026-10-23T10:50:00Z");
        call.parseExpectedArrivalTime("2026-10-23T10:51:30.123456Z");
        call.parseAimedDepartureTime("2026-10-23T12:51:00+02:00");

        assertThat(call.getAimedArrivalTime()).isEqualTo(ZonedDateTime.parse("2026-10-23T10:50:00Z"));
        assertThat(call.getExpectedArrivalTime()).isEqualTo(ZonedDateTime.parse("2026-10-23T10:51:30.123456Z"));
        assertThat(call.getAimedDepartureTime()).isEqualTo(ZonedDateTime.parse("2026-10-23T12:51:00+02:00"));
        assertThat(call.getAimedArrivalTimeEpochSecond())
                .isEqualTo(ZonedDateTime.parse("2026-10-23T10:50:00Z").toEpochSecond());
    }

    @Test
    void aTimeThatDoesNotPackIsKeptExactly() {
        ZonedDateTime withRegionAndNanos = ZonedDateTime.parse("2026-10-23T12:50:00.123456789+02:00[Europe/Oslo]");
        Call call = new Call();
        call.setActualDepartureTime(withRegionAndNanos);
        call.parseActualArrivalTime("2026-10-23T12:49:00.123456789+02:00[Europe/Oslo]");

        assertThat(call.getActualDepartureTime()).isEqualTo(withRegionAndNanos);
        assertThat(call.getActualDepartureTime().getZone()).isEqualTo(withRegionAndNanos.getZone());
        assertThat(call.getActualArrivalTime())
                .isEqualTo(ZonedDateTime.parse("2026-10-23T12:49:00.123456789+02:00[Europe/Oslo]"));
        assertThat(call.getActualDepartureTimeEpochSecond()).isEqualTo(withRegionAndNanos.toEpochSecond());
    }

    @Test
    void overwritingAnExactTimeWithAPackedOneReplacesIt() {
        Call call = new Call();
        call.setExpectedDepartureTime(ZonedDateTime.parse("2026-10-23T12:50:00+02:00[Europe/Oslo]"));
        call.parseExpectedDepartureTime("2026-10-23T11:00:00Z");

        assertThat(call.getExpectedDepartureTime()).isEqualTo(ZonedDateTime.parse("2026-10-23T11:00:00Z"));
    }

    @Test
    void nullClearsATime() {
        Call call = new Call();
        call.parseAimedArrivalTime("2026-10-23T10:50:00Z");
        call.setExpectedArrivalTime(ZonedDateTime.parse("2026-10-23T12:50:00+02:00[Europe/Oslo]"));

        call.parseAimedArrivalTime(null);
        call.setExpectedArrivalTime(null);

        assertThat(call.getAimedArrivalTime()).isNull();
        assertThat(call.getAimedArrivalTimeEpochSecond()).isNull();
        assertThat(call.getExpectedArrivalTime()).isNull();
        assertThat(call.getExpectedArrivalTimeEpochSecond()).isNull();
    }

    @Test
    void anUnsetTimeIsNull() {
        Call call = new Call();

        assertThat(call.getActualArrivalTime()).isNull();
        assertThat(call.getActualArrivalTimeEpochSecond()).isNull();
        assertThat(call.getWindowStart()).isNull();
        assertThat(call.getWindowEnd()).isNull();
    }

    @Test
    void malformedTextThrowsAsZonedDateTimeParseDoes() {
        Call call = new Call();

        assertThatThrownBy(() -> call.parseAimedArrivalTime("2026-02-30T10:50:00Z"))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> call.parseAimedArrivalTime("not a time"))
                .isInstanceOf(DateTimeParseException.class);
    }
}
