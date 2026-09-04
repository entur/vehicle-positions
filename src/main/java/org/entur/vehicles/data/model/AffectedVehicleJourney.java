package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One journey a situation names, with the stops it is affected at.
 * <p>
 * One entry per named journey: a SIRI {@code AffectedVehicleJourney} that names three dated
 * journeys becomes three entries sharing the same stop list, which is what lets the matcher
 * index an entry by a single journey id.
 * <p>
 * {@code line} and {@code operator} are display context only. An entry is never indexed by
 * its line - it is scoped to the journey it names, and indexing it by line would widen
 * matching back to every journey on that line.
 */
@SchemaMapping
public class AffectedVehicleJourney {

    private final ServiceJourney serviceJourney;
    private final DatedServiceJourney datedServiceJourney;
    private final Line line;
    private final Operator operator;
    private final List<AffectedStop> stops;
    private final Set<String> stopRefs;

    public AffectedVehicleJourney(ServiceJourney serviceJourney,
                                  DatedServiceJourney datedServiceJourney,
                                  Line line,
                                  Operator operator,
                                  List<AffectedStop> stops) {
        this.serviceJourney = serviceJourney;
        this.datedServiceJourney = datedServiceJourney;
        this.line = line;
        this.operator = operator;
        this.stops = stops == null ? List.of() : List.copyOf(stops);
        this.stopRefs = stopRefsOf(this.stops);
    }

    public ServiceJourney getServiceJourney() {
        return serviceJourney;
    }

    public DatedServiceJourney getDatedServiceJourney() {
        return datedServiceJourney;
    }

    public Line getLine() {
        return line;
    }

    public Operator getOperator() {
        return operator;
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
     * Value equality on identifiers - the service journey id, the dated service journey id, the
     * line ref and the stops - never on the nested model objects. StopPoint, ServiceJourney and
     * DatedServiceJourney all inherit ObjectRef.equals, which compares the bare ref across
     * unrelated subtypes and ignores everything else, so delegating to them would compare
     * something other than what it appears to. Operator is ignored outright: it is display
     * context on an entry that is identified by its journey.
     * <p>
     * Exists so {@code SituationTriggeredRepublisher.affectsUnchanged} can see an edit to the
     * stops nested inside an entry. Getting this wrong in the other direction - identity
     * equality - would make every redelivery look changed and turn the republisher into a storm,
     * so it is deliberately defined here rather than left to the default. Safe because this type
     * is immutable.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AffectedVehicleJourney other)) {
            return false;
        }
        return Objects.equals(serviceJourney != null ? serviceJourney.getId() : null,
                        other.serviceJourney != null ? other.serviceJourney.getId() : null)
                && Objects.equals(datedServiceJourney != null ? datedServiceJourney.getId() : null,
                        other.datedServiceJourney != null ? other.datedServiceJourney.getId() : null)
                && Objects.equals(line != null ? line.getLineRef() : null,
                        other.line != null ? other.line.getLineRef() : null)
                && stops.equals(other.stops);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                serviceJourney != null ? serviceJourney.getId() : null,
                datedServiceJourney != null ? datedServiceJourney.getId() : null,
                line != null ? line.getLineRef() : null,
                stops);
    }
}
