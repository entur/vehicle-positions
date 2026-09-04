package org.entur.vehicles.data.model;

import org.entur.vehicles.data.StopConditionEnumeration;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;
import java.util.Objects;

/** A stop named inside an affected journey or line, with the conditions the producer tagged. */
@SchemaMapping
public class AffectedStop {

    private final StopPoint stop;
    private final List<StopConditionEnumeration> stopConditions;

    public AffectedStop(StopPoint stop, List<StopConditionEnumeration> stopConditions) {
        this.stop = stop;
        this.stopConditions = stopConditions == null ? List.of() : List.copyOf(stopConditions);
    }

    public StopPoint getStop() {
        return stop;
    }

    public List<StopConditionEnumeration> getStopConditions() {
        return stopConditions;
    }

    /**
     * Value equality on the stop's <em>id</em> and the conditions, not on the {@link StopPoint}
     * itself. StopPoint inherits ObjectRef.equals, which compares the bare ref across any
     * ObjectRef subtype and ignores name and location - close enough to be misleading. Comparing
     * the id explicitly says what is actually being compared.
     * <p>
     * Exists so {@code SituationTriggeredRepublisher.affectsUnchanged} can tell a redelivery from
     * a producer edit to the stops nested inside a journey or line entry. Safe because this type
     * is immutable.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AffectedStop other)) {
            return false;
        }
        return Objects.equals(idOf(stop), idOf(other.stop))
                && stopConditions.equals(other.stopConditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idOf(stop), stopConditions);
    }

    private static String idOf(StopPoint stop) {
        return stop != null ? stop.getId() : null;
    }
}
