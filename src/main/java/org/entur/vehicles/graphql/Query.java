package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLQueryResolver;
import graphql.schema.DataFetchingEnvironment;
import jakarta.servlet.http.HttpServletRequest;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.data.model.BoundingBox;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.entur.vehicles.graphql.Constants.CLIENT_HEADER_KEY;
import static org.entur.vehicles.graphql.Constants.TRACING_HEADER_NAME;

@Component
class Query implements GraphQLQueryResolver {
    private static final Logger LOG = LoggerFactory.getLogger(Query.class);

    private final VehicleRepository repository;

    PrometheusMetricsService metricsService;

    public Query(VehicleRepository repository,
                 PrometheusMetricsService metricsService) {
        this.repository = repository;
        this.metricsService = metricsService;
    }

    Collection<VehicleUpdate> getVehicles(String serviceJourneyId, String operator,
                                          String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef,
                                          String lineName, Boolean monitored, BoundingBox boundingBox,
                                          DataFetchingEnvironment environment) {
        setClientHeader(environment);

        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());

        final VehicleUpdateFilter filter = new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox);
        LOG.debug("Requesting vehicles with filter: {}", filter);
        final long start = System.currentTimeMillis();
        final Collection<VehicleUpdate> vehicles = repository.getVehicles(filter);
        LOG.debug("Returning {} vehicles in {} ms", vehicles.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        metricsService.markVehicleQuery();
        clearClientHeader();
        return vehicles;
    }

    private void clearClientHeader() {
        MDC.remove(CLIENT_HEADER_KEY);
    }

    private void setClientHeader(DataFetchingEnvironment environment) {
        if (environment != null) {
            final HttpServletRequest httpServletRequest = environment.getGraphQlContext().get(HttpServletRequest.class);
            if (httpServletRequest != null) {
                String clientHeader = httpServletRequest.getHeader("Et-Client-Name");
                if (clientHeader != null) {
                    MDC.put(CLIENT_HEADER_KEY, clientHeader);
                }
            }
        }
    }

    List<Line> lines(String codespace) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();
        final List<Line> lines = repository.getLines(codespace);
        LOG.info("Returning {} lines in {} ms", lines.size(), System.currentTimeMillis() - start);
        MDC.remove(TRACING_HEADER_NAME);
        metricsService.markLinesQuery();
        return lines;
    }

    List<Codespace> codespaces() {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();

        final List<Codespace> codespaces = repository.getCodespaces();
        LOG.info("Returning {} codespaces in {} ms", codespaces.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        metricsService.markCodespacesQuery();
        return codespaces;
    }

    List<Operator> operators(String codespaceId) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();

        final List<Operator> operators = repository.getOperators(codespaceId);
        LOG.info("Returning {} operators in {} ms", operators.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        metricsService.markOperatorsQuery();
        return operators;
    }

    List<ServiceJourney> serviceJourneys(String lineRef) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();

        final List<ServiceJourney> serviceJourneys = repository.getServiceJourneys(lineRef);
        LOG.info("Returning {} serviceJourneys in {} ms", serviceJourneys.size(), System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        metricsService.markServiceJourneysQuery();
        return serviceJourneys;
    }

    ServiceJourney serviceJourney(String id) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        final long start = System.currentTimeMillis();

        final ServiceJourney serviceJourney = repository.getServiceJourney(id);
        LOG.info("Returning serviceJourney in {} ms", serviceJourney, System.currentTimeMillis() - start);

        MDC.remove(TRACING_HEADER_NAME);
        metricsService.markServiceJourneyQuery();
        return serviceJourney;
    }
}