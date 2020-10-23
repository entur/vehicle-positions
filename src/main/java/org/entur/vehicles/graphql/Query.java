package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLQueryResolver;
import org.entur.vehicles.data.*;
import org.entur.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class Query implements GraphQLQueryResolver {

    private final VehicleRepository repository;

    public Query(VehicleRepository repository) {
        this.repository = repository;
    }

    Set<VehicleUpdate> getAll(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, BoundingBox boundingBox) {
        return vehicles(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, boundingBox);
    }

    Set<VehicleUpdate> vehicles(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, BoundingBox boundingBox) {
        return repository.getVehicles(new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, boundingBox));
    }

    Set<Line> lines(String codespace) {
        return repository.getLines(codespace);
    }
}