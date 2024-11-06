package org.entur.vehicles.graphql;

import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.data.model.BoundingBox;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

import static org.entur.vehicles.graphql.Constants.TRACING_HEADER_NAME;

@Controller
class Subscription {

    private static final Logger LOG = LoggerFactory.getLogger(Subscription.class);

    private final VehicleUpdateRxPublisher vehicleUpdater;

    PrometheusMetricsService metricsService;

    Subscription(VehicleUpdateRxPublisher vehicleUpdater,
                 PrometheusMetricsService metricsService) {
        this.vehicleUpdater = vehicleUpdater;
        this.metricsService = metricsService;
    }

    @SubscriptionMapping
    Publisher<List<VehicleUpdate>> vehicles(@Argument String serviceJourneyId,
                                       @Argument String datedServiceJourneyId,
                                       @Argument String operator,
                                       @Argument String codespaceId,
                                       @Argument VehicleModeEnumeration mode,
                                       @Argument String vehicleId,
                                       @Argument String lineRef,
                                       @Argument String lineName,
                                       @Argument Boolean monitored,
                                       @Argument BoundingBox boundingBox,
                                       @Argument Integer bufferSize,
                                       @Argument Integer bufferTime) {
        final String uuid = UUID.randomUUID().toString();
        MDC.put(TRACING_HEADER_NAME, uuid);
        final VehicleUpdateFilter filter = new VehicleUpdateFilter(serviceJourneyId, datedServiceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox, bufferSize, bufferTime);
        LOG.debug("Creating new subscription with filter: {}", filter);
        MDC.remove(TRACING_HEADER_NAME);
        metricsService.markSubscription();
        return vehicleUpdater.getPublisher(filter, uuid);
    }

    @SubscriptionMapping
    Publisher<List<VehicleUpdate>> vehicleUpdates(String serviceJourneyId, String datedServiceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleRef, String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox, Integer bufferSize, Integer bufferTime) {

        metricsService.markSubscription();
        return vehicles(serviceJourneyId, datedServiceJourneyId, operator, codespaceId, mode, vehicleRef, lineRef, lineName, monitored, boundingBox, bufferSize, bufferTime);
    }

}