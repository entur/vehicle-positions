package org.entur.vehicles.graphql;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.BoundingBox;
import org.entur.vehicles.data.model.ServiceJourneyIdAndDate;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Controller
class Subscription {

    private static final Logger LOG = LoggerFactory.getLogger(Subscription.class);

    private final VehicleUpdateRxPublisher vehicleUpdater;
    private final EstimatedTimetableUpdateRxPublisher timetableUpdater;

    PrometheusMetricsService metricsService;

    Subscription(VehicleUpdateRxPublisher vehicleUpdater,
                 EstimatedTimetableUpdateRxPublisher timetableUpdater,
                 PrometheusMetricsService metricsService) {
        this.vehicleUpdater = vehicleUpdater;
        this.timetableUpdater = timetableUpdater;
        this.metricsService = metricsService;
    }

    @SubscriptionMapping
    Publisher<List<VehicleUpdate>> vehicles(@Argument String serviceJourneyId,
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
                                            @Argument Duration maxDataAge,
                                            @Argument Integer bufferSize,
                                            @Argument Integer bufferTime) {
        final String uuid = UUID.randomUUID().toString();

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
                MetricType.SUBSCRIPTION,
                serviceJourneyIdAndDates,
                datedServiceJourneyIds,
                operatorRef,
                codespaceId,
                mode,
                vehicleIds,
                lineRef,
                lineName,
                monitored,
                null, // cancellation is not used in vehicle updates
                boundingBox,
                maxDataAge,
                bufferSize,
                bufferTime
        );
        LOG.debug("Creating new subscription with filter: {}", filter);
        return vehicleUpdater.getPublisher(filter, uuid);
    }

    @SubscriptionMapping
    Publisher<List<EstimatedTimetableUpdate>> timetables(@Argument Set<ServiceJourneyIdAndDate> serviceJourneyIdAndDates,
                                                         @Argument Set<String> datedServiceJourneyIds,
                                                         @Argument String codespaceId,
                                                         @Argument VehicleModeEnumeration mode,
                                                         @Argument String lineRef,
                                                         @Argument Boolean monitored,
                                                         @Argument Boolean cancellation,
                                                         @Argument Integer bufferSize,
                                                         @Argument Integer bufferTime) {
        final String uuid = UUID.randomUUID().toString();


        final QueryFilter filter = new QueryFilter(
                metricsService,
                MetricType.SUBSCRIPTION,
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
                null,
                bufferSize,
                bufferTime
        );
        LOG.debug("Creating new subscription with filter: {}", filter);
        return timetableUpdater.getPublisher(filter, uuid);
    }
}