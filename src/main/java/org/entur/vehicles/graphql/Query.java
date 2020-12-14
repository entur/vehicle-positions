package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLQueryResolver;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.data.model.*;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
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

        final VehicleUpdateFilter filter = new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox);
        LOG.info("Requesting vehicles with filter: {}", filter);
        final Collection<VehicleUpdate> vehicles = repository.getVehicles(filter);
        LOG.info("Returning {} vehicles", vehicles.size());

        MDC.remove("breadcrumbId");
        return vehicles;
    }

    List<Line> lines(String codespace) {
        MDC.put("breadcrumbId", UUID.randomUUID().toString());
        final List<Line> lines = repository.getLines(codespace);
        LOG.info("Returning {} lines", lines.size());
        MDC.remove("breadcrumbId");
        return lines;
    }

    List<Codespace> codespaces() {
        MDC.put("breadcrumbId", UUID.randomUUID().toString());

        final List<Codespace> codespaces = repository.getCodespaces();
        LOG.info("Returning {} codespaces", codespaces.size());

        MDC.remove("breadcrumbId");
        return codespaces;
    }

    List<Operator> operators(String codespaceId) {
        MDC.put("breadcrumbId", UUID.randomUUID().toString());

        final List<Operator> operators = repository.getOperators(codespaceId);
        LOG.info("Returning {} operators", operators.size());

        MDC.remove("breadcrumbId");
        return operators;
    }

    List<ServiceJourney> serviceJourneys(String lineRef) {
        MDC.put("breadcrumbId", UUID.randomUUID().toString());

        final List<ServiceJourney> serviceJourneys = repository.getServiceJourneys(lineRef);
        LOG.info("Returning {} serviceJourneys", serviceJourneys.size());

        MDC.remove("breadcrumbId");
        return serviceJourneys;
    }
}