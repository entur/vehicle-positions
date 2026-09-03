package org.entur.vehicles.graphql;

import org.entur.vehicles.data.model.AffectedStop;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.entur.vehicles.service.planned.PolylineSlicer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code AffectedVehicleJourney.affectedPointsOnLink} lazily, mirroring
 * {@link ServiceJourneyGeometryController}: a client that does not select the field pays
 * nothing, and situations are never enriched with geometry at ingest.
 */
@Controller
public class AffectedGeometryController {

    private final PlannedDataService plannedDataService;
    private final NSRService nsrService;
    private final double maxSnapMeters;

    public AffectedGeometryController(@Autowired PlannedDataService plannedDataService,
                                      @Autowired NSRService nsrService,
                                      @Value("${vehicle.situations.affected-geometry.max-snap-meters:500}")
                                      double maxSnapMeters) {
        this.plannedDataService = plannedDataService;
        this.nsrService = nsrService;
        this.maxSnapMeters = maxSnapMeters;
    }

    @SchemaMapping(typeName = "AffectedVehicleJourney", field = "affectedPointsOnLink")
    public PointsOnLink affectedPointsOnLink(AffectedVehicleJourney journey) {
        List<AffectedStop> stops = journey.getStops();
        if (stops.size() < 2) {
            return null;
        }
        String serviceJourneyId = serviceJourneyIdOf(journey);
        if (serviceJourneyId == null) {
            return null;
        }
        PlannedDataset dataset = plannedDataService.current();
        int[] geometry = dataset.stitchedGeometry(dataset.journeyPatternOf(serviceJourneyId));
        if (geometry.length < 4) {
            return null;
        }
        List<Location> locations = new ArrayList<>(stops.size());
        for (AffectedStop stop : stops) {
            locations.add(locationOf(stop));
        }
        return PolylineSlicer.slice(geometry, locations, maxSnapMeters);
    }

    /**
     * A dated journey the planned data knows already carries its service journey - the mapper
     * resolved it at ingest - so this needs no further lookup. A journey named by a bare
     * service journey ref is used directly.
     */
    private String serviceJourneyIdOf(AffectedVehicleJourney journey) {
        if (journey.getDatedServiceJourney() != null
                && journey.getDatedServiceJourney().getServiceJourney() != null) {
            return journey.getDatedServiceJourney().getServiceJourney().getId();
        }
        return journey.getServiceJourney() != null ? journey.getServiceJourney().getId() : null;
    }

    /** Null when the stop is unknown to NSR or NSR lookup is disabled - the slicer then yields null. */
    private Location locationOf(AffectedStop stop) {
        if (stop.getStop() == null || stop.getStop().getId() == null) {
            return null;
        }
        StopPoint resolved = nsrService.getStop(stop.getStop().getId());
        return resolved != null ? resolved.getLocation() : null;
    }
}
