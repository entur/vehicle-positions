package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLQueryResolver;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.data.model.BoundingBox;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.entur.vehicles.graphql.Constants.TRACING_HEADER_NAME;

@Component
class Query implements GraphQLQueryResolver {
    private static final Logger LOG = LoggerFactory.getLogger(Query.class);

    private final VehicleRepository repository;
    ;

    public Query(VehicleRepository repository) {
        this.repository = repository;
    }

    Collection<VehicleUpdate> getVehicles(String serviceJourneyId, String operator,
                                          String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox) {

        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());

        final VehicleUpdateFilter filter = new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox);
        LOG.info("Requesting vehicles with filter: {}", filter);
        final long start = System.currentTimeMillis();
        final Collection<VehicleUpdate> vehicles = repository.getVehicles(filter);
        LOG.info("Returning {} vehicles in {} ms", vehicles.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        return vehicles;
    }

    List<Line> lines(String codespace) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();
        final List<Line> lines = repository.getLines(codespace);
        LOG.info("Returning {} lines in {} ms", lines.size(), System.currentTimeMillis() - start);
        MDC.remove(TRACING_HEADER_NAME);
        return lines;
    }

    List<Codespace> codespaces() {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();

        final List<Codespace> codespaces = repository.getCodespaces();
        LOG.info("Returning {} codespaces in {} ms", codespaces.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        return codespaces;
    }

    List<Operator> operators(String codespaceId) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();

        final List<Operator> operators = repository.getOperators(codespaceId);
        LOG.info("Returning {} operators in {} ms", operators.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        return operators;
    }

    List<ServiceJourney> serviceJourneys(String lineRef) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();

        final List<ServiceJourney> serviceJourneys = repository.getServiceJourneys(lineRef);
        LOG.info("Returning {} serviceJourneys in {} ms", serviceJourneys.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        return serviceJourneys;
    }
}