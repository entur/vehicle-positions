package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;
import java.util.Objects;

@SchemaMapping
public class InfoLink {

    private final String uri;
    private final List<TranslatedString> labels;

    public InfoLink(String uri, List<TranslatedString> labels) {
        this.uri = uri;
        this.labels = labels;
    }

    public String getUri() {
        return uri;
    }

    public List<TranslatedString> getLabels() {
        return labels;
    }

    /**
     * Value equality on {@code uri}/{@code labels}, needed so a {@code List<InfoLink>} can be
     * compared with {@link Object#equals(Object)} - notably by
     * {@code SituationTriggeredRepublisher} to tell whether a re-delivered situation's info
     * links actually changed. Without this, list comparison falls back to identity and every
     * redelivery would look like a change. Relies on {@link TranslatedString#equals(Object)}
     * for {@code labels}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InfoLink other)) {
            return false;
        }
        return Objects.equals(uri, other.uri) && Objects.equals(labels, other.labels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, labels);
    }
}
