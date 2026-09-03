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
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves {@code AffectedVehicleJourney.affectedPointsOnLink} lazily and in batches.
 * <p>
 * Lazy, mirroring {@link ServiceJourneyGeometryController}: a client that does not select the
 * field pays nothing, and situations are never enriched with geometry at ingest.
 * <p>
 * Batched, mirroring {@link SituationJoinController}: a situation naming N journeys yields N
 * entries, and the cut is not cheap - {@code PlannedDataset.stitchedGeometry} deliberately does
 * not cache, and {@code PolylineSlicer} scans the whole vertex array once per stop. A per-object
 * resolver therefore paid N full stitches plus N x stops scans for a single request, which for a
 * line-wide rail closure over a long pattern is millions of distance computations. The journeys
 * of one situation overwhelmingly share a handful of journey patterns, so memoizing the stitched
 * array per pattern for the life of the batch collapses that fan-out to one stitch per pattern.
 * The memo is deliberately batch-scoped and thrown away with the batch: it is a request-time
 * amortisation, not a cache that has to be invalidated when the planned data reloads.
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

    /**
     * Returns a list positionally aligned with {@code journeys} - NOT a Map keyed by them. Two
     * entries of one situation can compare equal (the same journey named on two lines of the same
     * ref, say), and a Map would collapse their results onto one key; a List cannot. This is the
     * same rule, and the same reasoning, as {@link SituationJoinController#timetableSituations};
     * see its Javadoc for the other half - why {@link GraphQlBatchLoaderConfiguration} disabling
     * DataLoader's per-key cache is what keeps the keys themselves from collapsing upstream of
     * this method.
     * <p>
     * A journey that resolves to no polyline holds its slot as null. The field is nullable in the
     * schema, and dropping the element instead would shift every later journey onto another
     * journey's geometry.
     */
    @BatchMapping(typeName = "AffectedVehicleJourney", field = "affectedPointsOnLink")
    public List<PointsOnLink> affectedPointsOnLink(List<AffectedVehicleJourney> journeys) {
        // Journey pattern id -> its stitched vertices, for the life of this batch only. An absent
        // key is a pattern not yet stitched; a present one may map to an empty array, which is a
        // pattern known to have no usable geometry - so this must not be a computeIfAbsent over
        // "is the value empty".
        Map<String, int[]> stitched = new HashMap<>();
        PlannedDataset dataset = null;
        List<PointsOnLink> result = new ArrayList<>(journeys.size());

        for (AffectedVehicleJourney journey : journeys) {
            List<AffectedStop> stops = journey.getStops();
            String serviceJourneyId = serviceJourneyIdOf(journey);
            if (stops.size() < 2 || serviceJourneyId == null) {
                result.add(null);
                continue;
            }
            if (dataset == null) {
                // Resolved on first need rather than up front, so a batch of journeys that all
                // fail the cheap checks above still touches nothing.
                dataset = plannedDataService.current();
            }
            String journeyPatternId = dataset.journeyPatternOf(serviceJourneyId);
            if (journeyPatternId == null) {
                result.add(null);
                continue;
            }
            int[] geometry = stitched.computeIfAbsent(journeyPatternId, dataset::stitchedGeometry);
            if (geometry.length < 4) {
                result.add(null);
                continue;
            }
            List<Location> locations = new ArrayList<>(stops.size());
            for (AffectedStop stop : stops) {
                locations.add(locationOf(stop));
            }
            result.add(PolylineSlicer.slice(geometry, locations, maxSnapMeters));
        }
        return result;
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
