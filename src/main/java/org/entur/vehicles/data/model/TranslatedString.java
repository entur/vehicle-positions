package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.Objects;

@SchemaMapping
public class TranslatedString {

    private final String value;
    private final String language;

    public TranslatedString(String value, String language) {
        this.value = value;
        this.language = language;
    }

    public String getValue() {
        return value;
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Value equality on {@code value}/{@code language}, needed so a {@code List<TranslatedString>}
     * (a situation's {@code summary}, {@code description}, {@code advice} or {@code detail}) can
     * be compared with {@link Object#equals(Object)} - notably by
     * {@code SituationTriggeredRepublisher} to tell whether a re-delivered situation's text
     * actually changed. Without this, list comparison falls back to identity and every
     * redelivery would look like a change.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranslatedString other)) {
            return false;
        }
        return Objects.equals(value, other.value) && Objects.equals(language, other.language);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, language);
    }
}
