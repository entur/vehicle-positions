package org.entur.vehicles.repository;

import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoPurgingSituationMapTest {

    private SituationUpdate situation(String situationNumber, ZonedDateTime expiration) {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber(situationNumber);
        situation.setCodespace(Codespace.getCodespace("TST"));
        situation.setExpiration(expiration);
        situation.setLastUpdated(ZonedDateTime.now());
        return situation;
    }

    @Test
    public void testExpiredSituationIsPurgedAfterGracePeriod() {
        AutoPurgingSituationMap map =
                new AutoPurgingSituationMap(Duration.parse("PT1S"), Duration.parse("PT1S"));

        SituationUpdate expired = situation("TST:SituationNumber:1", ZonedDateTime.now().minusHours(1));
        map.put(new SituationKey(expired.getCodespace(), expired.getSituationNumber()), expired);
        assertEquals(1, map.size());

        map.removeExpiredEntries();
        assertEquals(0, map.size());
    }

    @Test
    public void testSituationWithinGracePeriodIsRetained() {
        AutoPurgingSituationMap map =
                new AutoPurgingSituationMap(Duration.parse("PT1S"), Duration.parse("PT10M"));

        SituationUpdate justExpired = situation("TST:SituationNumber:2", ZonedDateTime.now().minusSeconds(5));
        map.put(new SituationKey(justExpired.getCodespace(), justExpired.getSituationNumber()), justExpired);

        map.removeExpiredEntries();
        assertEquals(1, map.size());
    }

    @Test
    public void testOpenEndedSituationIsNeverPurged() {
        AutoPurgingSituationMap map =
                new AutoPurgingSituationMap(Duration.parse("PT1S"), Duration.parse("PT1S"));

        SituationUpdate openEnded = situation("TST:SituationNumber:3", null);
        map.put(new SituationKey(openEnded.getCodespace(), openEnded.getSituationNumber()), openEnded);

        map.removeExpiredEntries();
        map.removeExpiredEntries();
        map.removeExpiredEntries();

        assertEquals(1, map.size());
    }

    @Test
    public void testKeyEqualityIsByCodespaceAndSituationNumber() {
        SituationKey first = new SituationKey(Codespace.getCodespace("TST"), "TST:SituationNumber:1");
        SituationKey same = new SituationKey(Codespace.getCodespace("TST"), "TST:SituationNumber:1");
        SituationKey otherCodespace = new SituationKey(Codespace.getCodespace("ABC"), "TST:SituationNumber:1");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(otherCodespace));
    }

    @Test
    public void testOpenEndedDerivedFromValidityPeriods() {
        SituationUpdate withEnd = situation("TST:SituationNumber:4", null);
        withEnd.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(1))));
        assertFalse(withEnd.getOpenEnded());

        SituationUpdate withoutEnd = situation("TST:SituationNumber:5", null);
        withoutEnd.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(1), null)));
        assertTrue(withoutEnd.getOpenEnded());

        SituationUpdate noPeriods = situation("TST:SituationNumber:6", null);
        assertTrue(noPeriods.getOpenEnded());
    }

    @Test
    public void testAgeAndEpochAccessorsAreNullSafe() {
        SituationUpdate situation = situation("TST:SituationNumber:7", null);

        assertNull(situation.getExpirationEpochSecond());
        assertNull(situation.getAge());

        situation.setCreationTime(ZonedDateTime.now().minusDays(2));
        assertTrue(situation.getAge().toDays() >= 2);
    }
}
