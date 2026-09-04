package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One line a situation names, with the stops it is affected at. A line has many journey
 * patterns, so its geometry is one representative pattern's, resolved lazily from the line ref
 * by {@code AffectedGeometryController} and never stored here - so equals/hashCode, and with
 * them the republisher's change detection, stay a function of the line ref and the stops alone.
 */
@SchemaMapping
public class AffectedLine {

    private final Line line;
    private final List<AffectedStop> stops;
    private final Set<String> stopRefs;

    public AffectedLine(Line line, List<AffectedStop> stops) {
        this.line = line;
        this.stops = stops == null ? List.of() : List.copyOf(stops);
        this.stopRefs = stopRefsOf(this.stops);
    }

    public Line getLine() {
        return line;
    }

    public List<AffectedStop> getStops() {
        return stops;
    }

    /**
     * The ids of {@link #getStops()}, derived once here rather than by every consumer.
     * <p>
     * {@code SituationMatcher} is rebuilt for every GraphQL batch - and a situation-triggered
     * fan-out rebuilds it once per republished journey - so deriving this set there allocated one
     * HashSet per entry per rebuild. A situation naming thousands of dated journeys made that
     * megabytes of garbage, thousands of times over. Deriving it in the constructor is safe
     * because this type is immutable, and the matcher then merely references it.
     * <p>
     * Note that {@code equals}/{@code hashCode} stay on the stops list, not on this set: the set
     * drops both the stop conditions and the ordering, and the republisher has to see a change to
     * either.
     */
    public Set<String> stopRefs() {
        return stopRefs;
    }

    private static Set<String> stopRefsOf(List<AffectedStop> stops) {
        Set<String> refs = new HashSet<>();
        for (AffectedStop stop : stops) {
            if (stop.getStop() != null && stop.getStop().getId() != null) {
                refs.add(stop.getStop().getId());
            }
        }
        return Set.copyOf(refs);
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
