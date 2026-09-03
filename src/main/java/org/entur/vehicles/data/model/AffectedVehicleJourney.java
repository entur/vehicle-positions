package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

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
}
