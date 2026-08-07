package org.entur.vehicles.data.model;

import org.entur.vehicles.data.VehicleModeEnumeration;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Flattened view of the objects a situation affects.
 * <p>
 * Alongside the display lists, each identifier is kept in a {@link Set} so that
 * filtering is a constant-time lookup rather than a walk of nested lists. Adding
 * an object whose identifier is already present is a no-op, which deduplicates
 * references that SIRI carries in more than one place - a line, for instance, can
 * be named both by an affected network and by an affected vehicle journey.
 */
@SchemaMapping
public class Affects {

    private final List<Line> lines = new ArrayList<>();
    private final List<StopPoint> stopPoints = new ArrayList<>();
    private final List<StopPoint> stopPlaces = new ArrayList<>();
    private final List<ServiceJourney> serviceJourneys = new ArrayList<>();
    private final List<DatedServiceJourney> datedServiceJourneys = new ArrayList<>();
    private final List<Operator> operators = new ArrayList<>();
    private final Set<VehicleModeEnumeration> vehicleModes = new LinkedHashSet<>();

    private final Set<String> lineRefs = new HashSet<>();
    private final Set<String> stopRefs = new HashSet<>();
    private final Set<String> serviceJourneyIds = new HashSet<>();
    private final Set<String> datedServiceJourneyIds = new HashSet<>();
    private final Set<String> operatorRefs = new HashSet<>();

    public void addLine(Line line) {
        if (line != null && line.getLineRef() != null && lineRefs.add(line.getLineRef())) {
            lines.add(line);
        }
    }

    public void addStopPoint(StopPoint stopPoint) {
        if (stopPoint != null && stopPoint.getId() != null && stopRefs.add(stopPoint.getId())) {
            stopPoints.add(stopPoint);
        }
    }

    public void addStopPlace(StopPoint stopPlace) {
        if (stopPlace != null && stopPlace.getId() != null && stopRefs.add(stopPlace.getId())) {
            stopPlaces.add(stopPlace);
        }
    }

    public void addServiceJourney(ServiceJourney serviceJourney) {
        if (serviceJourney != null && serviceJourney.getId() != null && serviceJourneyIds.add(serviceJourney.getId())) {
            serviceJourneys.add(serviceJourney);
        }
    }

    public void addDatedServiceJourney(DatedServiceJourney datedServiceJourney) {
        if (datedServiceJourney != null && datedServiceJourney.getId() != null
                && datedServiceJourneyIds.add(datedServiceJourney.getId())) {
            datedServiceJourneys.add(datedServiceJourney);
        }
    }

    public void addOperator(Operator operator) {
        if (operator != null && operator.getOperatorRef() != null && operatorRefs.add(operator.getOperatorRef())) {
            operators.add(operator);
        }
    }

    public void addVehicleMode(VehicleModeEnumeration mode) {
        if (mode != null) {
            vehicleModes.add(mode);
        }
    }

    public List<Line> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<StopPoint> getStopPoints() {
        return Collections.unmodifiableList(stopPoints);
    }

    public List<StopPoint> getStopPlaces() {
        return Collections.unmodifiableList(stopPlaces);
    }

    public List<ServiceJourney> getServiceJourneys() {
        return Collections.unmodifiableList(serviceJourneys);
    }

    public List<DatedServiceJourney> getDatedServiceJourneys() {
        return Collections.unmodifiableList(datedServiceJourneys);
    }

    public List<Operator> getOperators() {
        return Collections.unmodifiableList(operators);
    }

    public Set<VehicleModeEnumeration> getVehicleModes() {
        return Collections.unmodifiableSet(vehicleModes);
    }

    public Set<String> getLineRefs() {
        return lineRefs;
    }

    /** Covers both stop points and stop places - the `stopRef` filter matches either. */
    public Set<String> getStopRefs() {
        return stopRefs;
    }

    public Set<String> getServiceJourneyIds() {
        return serviceJourneyIds;
    }

    public Set<String> getDatedServiceJourneyIds() {
        return datedServiceJourneyIds;
    }

    public Set<String> getOperatorRefs() {
        return operatorRefs;
    }

    public boolean isEmpty() {
        return lines.isEmpty()
                && stopPoints.isEmpty()
                && stopPlaces.isEmpty()
                && serviceJourneys.isEmpty()
                && datedServiceJourneys.isEmpty()
                && operators.isEmpty()
                && vehicleModes.isEmpty();
    }
}
