package org.entur.vehicles.graphql;

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
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Collection;
import java.util.List;

@Controller
class Query {
    private static final Logger LOG = LoggerFactory.getLogger(Query.class);

    private final VehicleRepository repository;

    PrometheusMetricsService metricsService;

    public Query(VehicleRepository repository,
                 PrometheusMetricsService metricsService) {
        this.repository = repository;
        this.metricsService = metricsService;
    }

    @QueryMapping(name = "vehicles")
    Collection<VehicleUpdate> getVehicles(@Argument String serviceJourneyId,
                                          @Argument String datedServiceJourneyId,
                                          @Argument String operator,
                                          @Argument String codespaceId,
                                          @Argument VehicleModeEnumeration mode,
                                          @Argument String vehicleId,
                                          @Argument String lineRef,
                                          @Argument String lineName,
                                          @Argument Boolean monitored,
                                          @Argument BoundingBox boundingBox) {

        final VehicleUpdateFilter filter = new VehicleUpdateFilter(serviceJourneyId, datedServiceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox);
        LOG.debug("Requesting vehicles with filter: {}", filter);
        final long start = System.currentTimeMillis();
        final Collection<VehicleUpdate> vehicles = repository.getVehicles(filter);
        LOG.debug("Returning {} vehicles in {} ms", vehicles.size(), System.currentTimeMillis() - start);

        metricsService.markVehicleQuery();
        return vehicles;
    }

    @QueryMapping
    List<Line> lines(@Argument String codespaceId) {
        final long start = System.currentTimeMillis();
        final List<Line> lines = repository.getLines(codespaceId);
        LOG.info("Returning {} lines in {} ms", lines.size(), System.currentTimeMillis() - start);
        metricsService.markLinesQuery();
        return lines;
    }

    @QueryMapping
    List<Codespace> codespaces() {
        final long start = System.currentTimeMillis();

        final List<Codespace> codespaces = repository.getCodespaces();
        LOG.info("Returning {} codespaces in {} ms", codespaces.size(), System.currentTimeMillis() - start);

        metricsService.markCodespacesQuery();
        return codespaces;
    }

    @QueryMapping
    List<Operator> operators(String codespaceId) {
        final long start = System.currentTimeMillis();

        final List<Operator> operators = repository.getOperators(codespaceId);
        LOG.info("Returning {} operators in {} ms", operators.size(), System.currentTimeMillis() - start);

        metricsService.markOperatorsQuery();
        return operators;
    }

    @QueryMapping
    List<ServiceJourney> serviceJourneys(String lineRef) {
        final long start = System.currentTimeMillis();

        final List<ServiceJourney> serviceJourneys = repository.getServiceJourneys(lineRef);
        LOG.info("Returning {} serviceJourneys in {} ms", serviceJourneys.size(), System.currentTimeMillis() - start);

        metricsService.markServiceJourneysQuery();
        return serviceJourneys;
    }

    @QueryMapping
    ServiceJourney serviceJourney(String id) {
        final long start = System.currentTimeMillis();

        final ServiceJourney serviceJourney = repository.getServiceJourney(id);
        LOG.info("Returning serviceJourney in {} ms", serviceJourney, System.currentTimeMillis() - start);

        metricsService.markServiceJourneyQuery();
        return serviceJourney;
    }
}