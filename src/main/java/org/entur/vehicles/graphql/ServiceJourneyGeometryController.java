package org.entur.vehicles.graphql;

import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * Resolves {@code ServiceJourney.pointsOnLink} lazily. Journeys enriched on the vehicle and
 * timetable paths carry their geometry already; catalogue journeys (the {@code serviceJourney}
 * and {@code serviceJourneys} queries) are created without it, so the polyline is only stitched
 * for journeys a client actually selects the field on.
 */
@Controller
public class ServiceJourneyGeometryController {

    private final PlannedDataService plannedDataService;

    public ServiceJourneyGeometryController(@Autowired PlannedDataService plannedDataService) {
        this.plannedDataService = plannedDataService;
    }

    @SchemaMapping(typeName = "ServiceJourney", field = "pointsOnLink")
    public PointsOnLink pointsOnLink(ServiceJourney serviceJourney) {
        if (serviceJourney.getPointsOnLink() != null) {
            return serviceJourney.getPointsOnLink();
        }
        PlannedDataset dataset = plannedDataService.current();
        return dataset.pointsOnLink(dataset.journeyPatternOf(serviceJourney.getId()));
    }
}
