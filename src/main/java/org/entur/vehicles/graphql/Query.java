package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLQueryResolver;
import org.entur.vehicles.data.BoundingBox;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
class Query implements GraphQLQueryResolver {

    private final VehicleRepository repository;

    public Query(VehicleRepository repository) {
        this.repository = repository;
    }

    Set<VehicleUpdate> getAll(String serviceJourneyId, String operator,
        String codespaceId, String mode, String vehicleId, String lineRef, BoundingBox boundingBox) {
        return repository.getVehicles(new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, boundingBox));
    }

}