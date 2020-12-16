package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLSubscriptionResolver;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.data.model.BoundingBox;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

import static org.entur.vehicles.graphql.Constants.TRACING_HEADER_NAME;

@Component
class Subscription implements GraphQLSubscriptionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(Subscription.class);

    private final VehicleUpdateRxPublisher vehicleUpdater;

    Subscription(@Autowired VehicleUpdateRxPublisher vehicleUpdater) {
        this.vehicleUpdater = vehicleUpdater;
    }

    Publisher<List<VehicleUpdate>> vehicleUpdates(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, Boolean monitored, BoundingBox boundingBox, Integer bufferSize, Integer bufferTime) {
        final String uuid = UUID.randomUUID().toString();
        MDC.put(TRACING_HEADER_NAME, uuid);
        final VehicleUpdateFilter filter = new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, monitored, boundingBox, bufferSize, bufferTime);
        LOG.info("Creating new subscription with filter: {}", filter);
        MDC.remove(TRACING_HEADER_NAME);
        return vehicleUpdater.getPublisher(filter, uuid);
    }

}