package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

/**
 * One line a situation names, with the stops it is affected at. Unlike a journey entry this
 * carries no geometry: a line has many journey patterns, so there is no single polyline to cut.
 */
@SchemaMapping
public class AffectedLine {

    private final Line line;
    private final List<AffectedStop> stops;

    public AffectedLine(Line line, List<AffectedStop> stops) {
        this.line = line;
        this.stops = stops == null ? List.of() : List.copyOf(stops);
    }

    public Line getLine() {
        return line;
    }

    public List<AffectedStop> getStops() {
        return stops;
    }
}
