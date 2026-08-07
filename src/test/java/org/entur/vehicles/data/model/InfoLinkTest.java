package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SituationTriggeredRepublisher compares {@code List<InfoLink>} with {@code Objects.equals} to
 * tell a redelivered situation from one whose info links actually changed. Without value
 * equality here, that comparison falls back to identity and would report every redelivery as
 * changed.
 */
public class InfoLinkTest {

    @Test
    public void testEqualUriAndLabelsAreEqual() {
        InfoLink a = new InfoLink("https://example.org/a", List.of(new TranslatedString("More info", "en")));
        InfoLink b = new InfoLink("https://example.org/a", List.of(new TranslatedString("More info", "en")));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testDifferentUriIsNotEqual() {
        InfoLink a = new InfoLink("https://example.org/a", List.of());
        InfoLink b = new InfoLink("https://example.org/b", List.of());

        assertNotEquals(a, b);
    }

    @Test
    public void testDifferentLabelsIsNotEqual() {
        InfoLink a = new InfoLink("https://example.org/a", List.of(new TranslatedString("More info", "en")));
        InfoLink b = new InfoLink("https://example.org/a", List.of(new TranslatedString("Mer info", "no")));

        assertNotEquals(a, b);
    }
}
