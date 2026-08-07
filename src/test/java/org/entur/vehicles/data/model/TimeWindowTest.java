package org.entur.vehicles.data.model;

import org.entur.vehicles.data.SituationUpdate;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TimeWindowTest {

    private final ZonedDateTime noon = ZonedDateTime.parse("2026-08-06T12:00:00Z");

    private ValidityPeriod period(ZonedDateTime start, ZonedDateTime end) {
        return new ValidityPeriod(start, end);
    }

    @Test
    public void testOverlapWhenPeriodCoversTheWindow() {
        assertThat(period(noon.minusHours(2), noon.plusHours(2))
                .overlaps(noon.minusMinutes(10), noon.plusMinutes(10))).isTrue();
    }

    @Test
    public void testNoOverlapWhenPeriodEndsBeforeTheWindow() {
        assertThat(period(noon.minusHours(2), noon)
                .overlaps(noon.plusMinutes(30), noon.plusMinutes(40))).isFalse();
    }

    @Test
    public void testNoOverlapWhenPeriodStartsAfterTheWindow() {
        assertThat(period(noon.plusHours(1), noon.plusHours(2))
                .overlaps(noon.minusMinutes(10), noon)).isFalse();
    }

    @Test
    public void testOverlapIsInclusiveAtBothEnds() {
        // A situation ending exactly when the vehicle arrives still applies.
        assertThat(period(noon.minusHours(1), noon).overlaps(noon, noon.plusMinutes(5))).isTrue();
        // ...and one starting exactly as it departs.
        assertThat(period(noon, noon.plusHours(1)).overlaps(noon.minusMinutes(5), noon)).isTrue();
    }

    @Test
    public void testOpenEndedPeriodOverlapsAnything() {
        assertThat(period(noon.minusYears(3), null)
                .overlaps(noon.plusYears(5), noon.plusYears(5))).isTrue();
    }

    @Test
    public void testUnboundedWindowOverlapsAnyPeriod() {
        assertThat(period(noon.minusHours(2), noon.minusHours(1)).overlaps(null, null)).isTrue();
    }

    @Test
    public void testSituationWithNoValidityPeriodsAlwaysApplies() {
        SituationUpdate situation = new SituationUpdate();
        assertThat(situation.isValidDuring(noon, noon)).isTrue();

        situation.setValidityPeriods(List.of());
        assertThat(situation.isValidDuring(noon, noon)).isTrue();
    }

    @Test
    public void testSituationAppliesWhenAnyPeriodOverlaps() {
        SituationUpdate situation = new SituationUpdate();
        situation.setValidityPeriods(List.of(
                period(noon.minusDays(5), noon.minusDays(4)),
                period(noon.minusMinutes(5), noon.plusMinutes(5))));

        assertThat(situation.isValidDuring(noon, noon)).isTrue();
    }

    @Test
    public void testSituationDoesNotApplyWhenNoPeriodOverlaps() {
        SituationUpdate situation = new SituationUpdate();
        situation.setValidityPeriods(List.of(
                period(noon.minusDays(5), noon.minusDays(4)),
                period(noon.plusDays(4), noon.plusDays(5))));

        assertThat(situation.isValidDuring(noon, noon)).isFalse();
    }

    @Test
    public void testCallWindowPrefersActualOverExpectedOverAimed() {
        Call call = new Call();
        call.setAimedArrivalTime(noon);
        call.setAimedDepartureTime(noon.plusMinutes(2));
        assertThat(call.getWindowStart()).isEqualTo(noon);
        assertThat(call.getWindowEnd()).isEqualTo(noon.plusMinutes(2));

        call.setExpectedArrivalTime(noon.plusMinutes(5));
        call.setExpectedDepartureTime(noon.plusMinutes(7));
        assertThat(call.getWindowStart()).isEqualTo(noon.plusMinutes(5));
        assertThat(call.getWindowEnd()).isEqualTo(noon.plusMinutes(7));

        call.setActualArrivalTime(noon.plusMinutes(9));
        call.setActualDepartureTime(noon.plusMinutes(11));
        assertThat(call.getWindowStart()).isEqualTo(noon.plusMinutes(9));
        assertThat(call.getWindowEnd()).isEqualTo(noon.plusMinutes(11));
    }

    @Test
    public void testCallWithOnlyOneTimeIsAnInstant() {
        Call arrivalOnly = new Call();
        arrivalOnly.setAimedArrivalTime(noon);
        assertThat(arrivalOnly.getWindowStart()).isEqualTo(noon);
        assertThat(arrivalOnly.getWindowEnd()).isEqualTo(noon);

        Call departureOnly = new Call();
        departureOnly.setAimedDepartureTime(noon);
        assertThat(departureOnly.getWindowStart()).isEqualTo(noon);
        assertThat(departureOnly.getWindowEnd()).isEqualTo(noon);
    }

    @Test
    public void testCallWithNoTimesIsUnbounded() {
        Call call = new Call();
        assertThat(call.getWindowStart()).isNull();
        assertThat(call.getWindowEnd()).isNull();
    }
}
