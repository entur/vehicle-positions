package org.entur.vehicles.repository;

import org.entur.vehicles.data.VehicleUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;

@Component
public class AutoPurgingVehicleMap extends AutoPurgingMap<VehicleUpdate> {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPurgingVehicleMap.class);

    public AutoPurgingVehicleMap(
            @Value("${vehicle.updates.purge.interval:PT10S}") Duration purgeInterval,
            @Value("${vehicle.updates.expiry.grace.period:PT5M}") Duration gracePeriod) {
        super(purgeInterval, gracePeriod);
    }

    public void removeExpiredVehicles() {
        long before = System.currentTimeMillis();

        int sizeBefore = this.size();
        final boolean entriesRemoved = this.entrySet().removeIf(entry ->
                entry.getValue()
                        .getExpiration().plus(gracePeriod)
                        .isBefore(ZonedDateTime.now())
        );

        final long duration = System.currentTimeMillis() - before;

        if (entriesRemoved) {
            LOG.debug("Removed {} expired vehicles in {} ms, current size: {}",
                sizeBefore-this.size(),
                duration,
                this.size()
            );
        }


        if (duration > 20) {
            LOG.warn("Removing expired vehicles took {} ms", duration);
        }

    }
}
