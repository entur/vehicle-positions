package org.entur.vehicles.data.model;

import org.entur.vehicles.data.StopConditionEnumeration;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

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
}
