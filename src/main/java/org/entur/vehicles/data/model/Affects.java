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
 * What a situation affects: the stops, operators and modes it names outright, and the
 * journey and line entries that pair a journey or line with the stops it is affected at.
 * <p>
 * Lines and journeys have no flat list of their own - {@link #getVehicleJourneys()} and
 * {@link #getAffectedLines()} carry them, and the mapper guarantees every line and journey the
 * situation names reaches one of the two. Their identifiers are still kept in a {@link Set}
 * each, so filtering and matching are constant-time lookups rather than a walk of the entries.
 * Adding an identifier already present is a no-op, which deduplicates the references SIRI
 * carries in more than one place - a line, for instance, can be named both by an affected
 * network and by an affected vehicle journey.
 */
@SchemaMapping
public class Affects {

    private final List<StopPoint> stopPoints = new ArrayList<>();
    private final List<StopPoint> stopPlaces = new ArrayList<>();
    private final List<Operator> operators = new ArrayList<>();
    private final Set<VehicleModeEnumeration> vehicleModes = new LinkedHashSet<>();

    private final Set<String> lineRefs = new HashSet<>();
    private final Set<String> stopRefs = new HashSet<>();
    private final Set<String> serviceJourneyIds = new HashSet<>();
    private final Set<String> datedServiceJourneyIds = new HashSet<>();
    private final Set<String> operatorRefs = new HashSet<>();

    private final List<AffectedVehicleJourney> vehicleJourneys = new ArrayList<>();
    private final List<AffectedLine> affectedLines = new ArrayList<>();

    /**
     * Every stop this situation mentions, top-level and scoped alike. Backs the {@code stopRef}
     * query filter - discovery. {@link #getStopRefs()} stays top-level-only and backs matching -
     * attachment. The two are deliberately different sets; see the spec.
     */
    private final Set<String> allStopRefs = new HashSet<>();

    /** @return true when this is the first time the situation names this line. */
    public boolean addLine(Line line) {
        return line != null && line.getLineRef() != null && lineRefs.add(line.getLineRef());
    }

    public void addStopPoint(StopPoint stopPoint) {
        if (stopPoint != null && stopPoint.getId() != null && stopRefs.add(stopPoint.getId())) {
            stopPoints.add(stopPoint);
            allStopRefs.add(stopPoint.getId());
        }
    }

    public void addStopPlace(StopPoint stopPlace) {
        if (stopPlace != null && stopPlace.getId() != null && stopRefs.add(stopPlace.getId())) {
            stopPlaces.add(stopPlace);
            allStopRefs.add(stopPlace.getId());
        }
    }

    /** @return true when this is the first time the situation names this journey. */
    public boolean addServiceJourney(ServiceJourney serviceJourney) {
        return serviceJourney != null && serviceJourney.getId() != null
                && serviceJourneyIds.add(serviceJourney.getId());
    }

    /** @return true when this is the first time the situation names this dated journey. */
    public boolean addDatedServiceJourney(DatedServiceJourney datedServiceJourney) {
        return datedServiceJourney != null && datedServiceJourney.getId() != null
                && datedServiceJourneyIds.add(datedServiceJourney.getId());
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

    public List<StopPoint> getStopPoints() {
        return Collections.unmodifiableList(stopPoints);
    }

    public List<StopPoint> getStopPlaces() {
        return Collections.unmodifiableList(stopPlaces);
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

    public void addVehicleJourney(AffectedVehicleJourney journey) {
        if (journey == null) {
            return;
        }
        vehicleJourneys.add(journey);
        indexScopedStops(journey.getStops());
    }

    public void addAffectedLine(AffectedLine affectedLine) {
        if (affectedLine == null) {
            return;
        }
        affectedLines.add(affectedLine);
        indexScopedStops(affectedLine.getStops());
    }

    private void indexScopedStops(List<AffectedStop> stops) {
        for (AffectedStop stop : stops) {
            if (stop.getStop() != null && stop.getStop().getId() != null) {
                allStopRefs.add(stop.getStop().getId());
            }
        }
    }

    public List<AffectedVehicleJourney> getVehicleJourneys() {
        return Collections.unmodifiableList(vehicleJourneys);
    }

    public List<AffectedLine> getAffectedLines() {
        return Collections.unmodifiableList(affectedLines);
    }

    /** Top-level and scoped stops together. See {@link #allStopRefs}. */
    public Set<String> getAllStopRefs() {
        return allStopRefs;
    }

    public boolean isEmpty() {
        return lineRefs.isEmpty()
                && stopPoints.isEmpty()
                && stopPlaces.isEmpty()
                && serviceJourneyIds.isEmpty()
                && datedServiceJourneyIds.isEmpty()
                && operators.isEmpty()
                && vehicleModes.isEmpty();
    }
}
