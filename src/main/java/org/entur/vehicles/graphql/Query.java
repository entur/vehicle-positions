package org.entur.vehicles.graphql;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.BoundingBox;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.ServiceJourneyIdAndDate;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.TimetableRepository;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Controller
class Query {
    private static final Logger LOG = LoggerFactory.getLogger(Query.class);

    private final VehicleRepository vehicleRepository;
    private final TimetableRepository timetableRepository;

    PrometheusMetricsService metricsService;

    public Query(VehicleRepository vehicleRepository,
                 TimetableRepository timetableRepository,
                 PrometheusMetricsService metricsService) {
        this.vehicleRepository = vehicleRepository;
        this.timetableRepository = timetableRepository;
        this.metricsService = metricsService;
    }


    @QueryMapping(name = "timetables")
    Collection<EstimatedTimetableUpdate> getTimetables(@Argument Set<ServiceJourneyIdAndDate> serviceJourneyIdAndDates,
                                                       @Argument Set<String> datedServiceJourneyIds,
                                                       @Argument String codespaceId,
                                                       @Argument VehicleModeEnumeration mode,
                                                       @Argument String lineRef,
                                                       @Argument Boolean monitored,
                                                       @Argument Boolean cancellation) {

        final QueryFilter filter = new QueryFilter(
                metricsService,
                MetricType.QUERY,
                serviceJourneyIdAndDates,
                datedServiceJourneyIds,
                null,
                codespaceId,
                mode,
                null,
                lineRef,
                null,
                monitored,
                cancellation,
                null,
                null
        );
        LOG.debug("Requesting timetables with filter: {}", filter);
        final long start = System.currentTimeMillis();
        final Collection<EstimatedTimetableUpdate> timetableUpdates = timetableRepository.getTimetables(filter);
        LOG.debug("Returning {} timetables in {} ms", timetableUpdates.size(), System.currentTimeMillis() - start);

//        metricsService.markTimetableQuery();
        return timetableUpdates;
    }

    @QueryMapping(name = "vehicles")
    Collection<VehicleUpdate> getVehicles(@Argument String serviceJourneyId,
                                          @Argument String date,
                                          @Argument Set<ServiceJourneyIdAndDate> serviceJourneyIdAndDates,
                                          @Argument String datedServiceJourneyId,
                                          @Argument Set<String> datedServiceJourneyIds,
                                          @Argument String operatorRef,
                                          @Argument String codespaceId,
                                          @Argument VehicleModeEnumeration mode,
                                          @Argument String vehicleId,
                                          @Argument Set<String> vehicleIds,
                                          @Argument String lineRef,
                                          @Argument String lineName,
                                          @Argument Boolean monitored,
                                          @Argument BoundingBox boundingBox,
                                          @Argument Duration maxDataAge) {

        if (vehicleId != null) {
            if (vehicleIds == null) {
                vehicleIds = Set.of(vehicleId);
            } else {
                vehicleIds.add(vehicleId);
            }
        }

        if (serviceJourneyId != null) {
            if (serviceJourneyIdAndDates == null) {
                serviceJourneyIdAndDates = Set.of(new ServiceJourneyIdAndDate(serviceJourneyId, date));
            }
        }

        if (datedServiceJourneyId != null) {
            if (datedServiceJourneyIds == null) {
                datedServiceJourneyIds = Set.of(datedServiceJourneyId);
            } else {
                datedServiceJourneyIds.add(datedServiceJourneyId);
            }
        }


        final QueryFilter filter = new QueryFilter(
                metricsService,
                MetricType.QUERY,
                serviceJourneyIdAndDates,
                datedServiceJourneyIds,
                operatorRef,
                codespaceId,
                mode,
                vehicleIds,
                lineRef,
                lineName,
                monitored,
                false, // cancellation is not used in vehicle queries
                boundingBox,
                maxDataAge
        );
        LOG.debug("Requesting vehicles with filter: {}", filter);
        final long start = System.currentTimeMillis();
        final Collection<VehicleUpdate> vehicles = vehicleRepository.getVehicles(filter);
        LOG.debug("Returning {} vehicles in {} ms", vehicles.size(), System.currentTimeMillis() - start);

        metricsService.markVehicleQuery();
        return vehicles;
    }

    @QueryMapping
    List<Line> lines(@Argument String codespaceId) {
        final long start = System.currentTimeMillis();
        final List<Line> lines = vehicleRepository.getLines(codespaceId);
        LOG.info("Returning {} lines in {} ms", lines.size(), System.currentTimeMillis() - start);
        metricsService.markLinesQuery();
        return lines;
    }

    @QueryMapping
    List<Codespace> codespaces() {
        final long start = System.currentTimeMillis();

        final List<Codespace> codespaces = vehicleRepository.getCodespaces();
        LOG.info("Returning {} codespaces in {} ms", codespaces.size(), System.currentTimeMillis() - start);

        metricsService.markCodespacesQuery();
        return codespaces;
    }

    @QueryMapping
    List<Operator> operators(@Argument String codespaceId) {
        final long start = System.currentTimeMillis();

        final List<Operator> operators = vehicleRepository.getOperators(codespaceId);
        LOG.info("Returning {} operators in {} ms", operators.size(), System.currentTimeMillis() - start);

        metricsService.markOperatorsQuery();
        return operators;
    }

    @QueryMapping
    List<ServiceJourney> serviceJourneys(@Argument String lineRef, @Argument String codespaceId) {
        final long start = System.currentTimeMillis();

        final List<ServiceJourney> serviceJourneys = vehicleRepository.getServiceJourneys(lineRef, codespaceId);
        LOG.info("Returning {} serviceJourneys in {} ms", serviceJourneys.size(), System.currentTimeMillis() - start);

        metricsService.markServiceJourneysQuery();
        return serviceJourneys;
    }

    @QueryMapping
    ServiceJourney serviceJourney(@Argument String id) {
        final long start = System.currentTimeMillis();

        final ServiceJourney serviceJourney = vehicleRepository.getServiceJourney(id);
        LOG.info("Returning serviceJourney {} in {} ms", serviceJourney, System.currentTimeMillis() - start);

        metricsService.markServiceJourneyQuery();
        return serviceJourney;
    }
}