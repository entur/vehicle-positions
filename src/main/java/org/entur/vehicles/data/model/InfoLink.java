package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

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
}
