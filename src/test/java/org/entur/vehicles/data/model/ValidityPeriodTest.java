package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ValidityPeriodTest {

    private final ZonedDateTime now = ZonedDateTime.now();

    @Test
    public void testCurrentPeriodIsValid() {
        ValidityPeriod period = new ValidityPeriod(now.minusHours(1), now.plusHours(1));
        assertTrue(period.isValidAt(now));
        assertFalse(period.isOpenEnded());
    }

    @Test
    public void testFuturePeriodIsNotYetValid() {
        ValidityPeriod period = new ValidityPeriod(now.plusHours(1), now.plusHours(2));
        assertFalse(period.isValidAt(now));
    }

    @Test
    public void testEndedPeriodIsNoLongerValid() {
        ValidityPeriod period = new ValidityPeriod(now.minusHours(2), now.minusHours(1));
        assertFalse(period.isValidAt(now));
    }

    @Test
    public void testMissingEndTimeMeansOpenEndedAndStillValid() {
        ValidityPeriod period = new ValidityPeriod(now.minusDays(400), null);
        assertTrue(period.isOpenEnded());
        assertTrue(period.isValidAt(now));
    }

    @Test
    public void testMissingStartTimeMeansAlwaysStarted() {
        ValidityPeriod period = new ValidityPeriod(null, now.plusHours(1));
        assertTrue(period.isValidAt(now));
        assertFalse(period.isOpenEnded());
    }
}
