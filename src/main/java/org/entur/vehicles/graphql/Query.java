package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLQueryResolver;
import org.entur.vehicles.data.*;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Component
class Query implements GraphQLQueryResolver {
    private static final Logger LOG = LoggerFactory.getLogger(Query.class);

    private final VehicleRepository repository;

    public Query(VehicleRepository repository) {
        this.repository = repository;
    }

    Collection<VehicleUpdate> getVehicles(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox) {
        MDC.put("breadcrumbId", UUID.randomUUID().toString());

        LOG.info("Finding vehicles");
        final Collection<VehicleUpdate> vehicles = repository.getVehicles(new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox));
        LOG.info("Returning {} vehicles", vehicles.size());

        MDC.remove("breadcrumbId");
        return vehicles;
    }

    Set<Line> lines(String codespace) {
        MDC.put("breadcrumbId", UUID.randomUUID().toString());
        final Set<Line> lines = repository.getLines(codespace);
        LOG.info("Returning {} lines", lines.size());
        MDC.remove("breadcrumbId");
        return lines;
    }

    Set<Codespace> codespaces() {
        MDC.put("breadcrumbId", UUID.randomUUID().toString());

        final Set<Codespace> codespaces = repository.getCodespaces();
        LOG.info("Returning {} codespaces", codespaces.size());

        MDC.remove("breadcrumbId");
        return codespaces;
    }
}