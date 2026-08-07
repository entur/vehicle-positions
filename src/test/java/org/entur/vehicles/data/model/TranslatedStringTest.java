package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SituationTriggeredRepublisher compares {@code List<TranslatedString>} (a situation's
 * {@code summary}/{@code description}/{@code advice}/{@code detail}) with {@code Objects.equals}
 * to tell a redelivered situation from one whose text actually changed. Without value equality
 * here, that comparison falls back to identity and would report every redelivery as changed.
 */
public class TranslatedStringTest {

    @Test
    public void testEqualValueAndLanguageAreEqual() {
        TranslatedString a = new TranslatedString("Delays expected", "en");
        TranslatedString b = new TranslatedString("Delays expected", "en");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testDifferentValueIsNotEqual() {
        TranslatedString a = new TranslatedString("Delays expected", "en");
        TranslatedString b = new TranslatedString("Severe delays expected", "en");

        assertNotEquals(a, b);
    }

    @Test
    public void testDifferentLanguageIsNotEqual() {
        TranslatedString a = new TranslatedString("Delays expected", "en");
        TranslatedString b = new TranslatedString("Delays expected", "no");

        assertNotEquals(a, b);
    }
}
