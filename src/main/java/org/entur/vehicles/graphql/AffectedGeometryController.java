package org.entur.vehicles.graphql;

import graphql.GraphQLContext;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@code AffectedVehicleJourney.affectedPointsOnLink} lazily, mirroring
 * {@link ServiceJourneyGeometryController}: a client that does not select the field pays
 * nothing, and situations are never enriched with geometry at ingest.
 * <p>
 * Per object rather than batched, and that is not an oversight. This field is nullable - a
 * journey the planned data does not know, or one whose stops do not project onto its route,
 * has no span to return - and a {@code @BatchMapping} returning {@code List<PointsOnLink>}
 * cannot express that. Spring GraphQL adapts such a list through Reactor, and
 * {@code Flux.fromIterable} rejects a null element with "The iterator returned a null value",
 * which fails the whole DataLoader dispatch rather than just that one field. The batch
 * shape and a nullable field are incompatible, so the resolver stays per object. (The
 * batch loaders that do work here - {@code SituationJoinController}'s - return empty lists,
 * never nulls.)
 * <p>
 * The cost that batching was meant to address is handled by the memo instead: a situation
 * naming N journeys yields N entries, {@code PlannedDataset.stitchedGeometry} deliberately
 * does not cache, and {@code PolylineSlicer} scans the whole vertex array once per stop, so
 * a line-wide rail closure over a long pattern would otherwise pay N full stitches in one
 * request. The journeys of one situation overwhelmingly share a handful of journey patterns,
 * so the stitched array is memoized per pattern in the request's {@link GraphQLContext} -
 * request-scoped, thrown away with the request, and therefore never something to invalidate
 * when the planned data reloads.
 */
@Controller
public class AffectedGeometryController {

    /** Where this request's journey-pattern-to-vertices memo lives in the {@link GraphQLContext}. */
    private static final String GEOMETRY_MEMO_KEY = AffectedGeometryController.class.getName() + ".stitched";

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
     * Null when this journey has no span to draw: fewer than two affected stops, no service
     * journey the planned data knows, a pattern without usable geometry, or stops that do not
     * locate on the route. Every one of those is an ordinary shape in production, not an error.
     */
    @SchemaMapping(typeName = "AffectedVehicleJourney", field = "affectedPointsOnLink")
    public PointsOnLink affectedPointsOnLink(AffectedVehicleJourney journey, GraphQLContext context) {
        List<AffectedStop> stops = journey.getStops();
        String serviceJourneyId = serviceJourneyIdOf(journey);
        if (stops.size() < 2 || serviceJourneyId == null) {
            return null;
        }
        PlannedDataset dataset = plannedDataService.current();
        String journeyPatternId = dataset.journeyPatternOf(serviceJourneyId);
        if (journeyPatternId == null) {
            return null;
        }
        // An absent key is a pattern not yet stitched; a present one may map to an empty array,
        // which is a pattern known to carry no usable geometry - so the memo is keyed on presence,
        // never on "is the value empty".
        int[] geometry = memo(context).computeIfAbsent(journeyPatternId, dataset::stitchedGeometry);
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
     * Concurrent because one request's data fetchers are not guaranteed a single thread, and
     * two journeys sharing a pattern are exactly the case this exists for.
     */
    private static Map<String, int[]> memo(GraphQLContext context) {
        return context.computeIfAbsent(GEOMETRY_MEMO_KEY, key -> new ConcurrentHashMap<String, int[]>());
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
