package org.entur.vehicles.graphql;

import graphql.kickstart.tools.GraphQLSubscriptionResolver;
import org.entur.vehicles.data.BoundingBox;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class Subscription implements GraphQLSubscriptionResolver {

  private final VehicleUpdateRxPublisher vehicleUpdater;

    Subscription(@Autowired VehicleUpdateRxPublisher vehicleUpdater) {
      this.vehicleUpdater = vehicleUpdater;
    }

    Publisher<VehicleUpdate> vehicleUpdates(String serviceJourneyId, String operator,
        String codespaceId, VehicleModeEnumeration mode, String vehicleId, String lineRef, BoundingBox boundingBox) {
      return vehicleUpdater.getPublisher(new VehicleUpdateFilter(serviceJourneyId, operator, codespaceId, mode, vehicleId, lineRef, boundingBox));
    }

}