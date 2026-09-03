package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;
import java.util.Objects;

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

    /**
     * Value equality on the line <em>ref</em> and the stops. Line does override equals - on ref
     * plus lineName - but the sibling entry types cannot rely on their nested objects doing so,
     * and comparing the ref here keeps the three entry types answering the same question.
     * See {@link AffectedVehicleJourney#equals(Object)} for why this matters to the republisher.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AffectedLine other)) {
            return false;
        }
        return Objects.equals(line != null ? line.getLineRef() : null,
                        other.line != null ? other.line.getLineRef() : null)
                && stops.equals(other.stops);
    }

    @Override
    public int hashCode() {
        return Objects.hash(line != null ? line.getLineRef() : null, stops);
    }
}
