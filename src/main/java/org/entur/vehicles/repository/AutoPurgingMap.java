package org.entur.vehicles.repository;

import org.entur.vehicles.data.VehicleUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class AutoPurgingMap extends ConcurrentHashMap<VehicleRepository.VehicleKey, VehicleUpdate> {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPurgingMap.class);

    private final Duration gracePeriod;

    public AutoPurgingMap(
            @Value("${vehicle.updates.purge.interval:PT10S}") Duration purgeInterval,
            @Value("${vehicle.updates.expiry.grace.period:PT5M}") Duration gracePeriod) {
        super();
        this.gracePeriod = gracePeriod;
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        long purgeIntervalSeconds = purgeInterval.getSeconds();
        service.scheduleWithFixedDelay(this::removeExpiredVehicles, purgeIntervalSeconds, purgeIntervalSeconds, TimeUnit.SECONDS);

    }

    private void removeExpiredVehicles() {
        long before = System.currentTimeMillis();

        int sizeBefore = this.size();
        final boolean vehicleRemoved = this.entrySet().removeIf(vehicleUpdate -> vehicleUpdate.getValue()
            .getExpiration().plus(gracePeriod)
            .isBefore(ZonedDateTime.now()));

        final long duration = System.currentTimeMillis() - before;

        if (vehicleRemoved) {
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
