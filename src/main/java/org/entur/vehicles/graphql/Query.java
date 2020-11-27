package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLQueryResolver;
import org.entur.vehicles.data.*;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class Query implements GraphQLQueryResolver {
    private static final Logger LOG = LoggerFactory.getLogger(Query.class);

    private final VehicleRepository repository;

    public Query(VehicleRepository repository) {
        this.repository = repository;
    }

    Set<VehicleUpdate> getAll(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, BoundingBox boundingBox) {
        return getVehicles(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, Boolean.TRUE, boundingBox);
    }

    Set<VehicleUpdate> getVehicles(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox) {
        final Set<VehicleUpdate> vehicles = repository.getVehicles(new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox));
        LOG.info("Returning {} vehicles", vehicles.size());
        return vehicles;
    }

    Set<Line> lines(String codespace) {
        final Set<Line> lines = repository.getLines(codespace);
        LOG.info("Returning {} lines", lines.size());
        return lines;
    }

    Set<Codespace> codespaces() {
        final Set<Codespace> codespaces = repository.getCodespaces();
        LOG.info("Returning {} codespaces", codespaces.size());
        return codespaces;
    }
}