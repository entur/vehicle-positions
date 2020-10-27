package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLSubscriptionResolver;
import org.entur.vehicles.data.BoundingBox;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class Subscription implements GraphQLSubscriptionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(Subscription.class);

    private final VehicleUpdateRxPublisher vehicleUpdater;

    Subscription(@Autowired VehicleUpdateRxPublisher vehicleUpdater) {
        this.vehicleUpdater = vehicleUpdater;
    }

    Publisher<VehicleUpdate> vehicleUpdates(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, String lineName, BoundingBox boundingBox) {
        final VehicleUpdateFilter filter = new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, lineName, boundingBox);
        LOG.info("Creating new subscription with filter: {}", filter);
        return vehicleUpdater.getPublisher(filter);
    }

}