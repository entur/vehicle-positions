package org.entur.vehicles.repository;

import org.entur.vehicles.data.VehicleUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AutoPurgingMap extends ConcurrentHashMap<VehicleRepository.VehicleKey, VehicleUpdate> {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPurgingMap.class);

    public AutoPurgingMap(int purgeIntervalSeconds) {
        super();
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleWithFixedDelay(this::removeExpiredVehicles, purgeIntervalSeconds, purgeIntervalSeconds, TimeUnit.SECONDS);

    }

    private void removeExpiredVehicles() {
        long before = System.currentTimeMillis();

        int sizeBefore = this.size();
        final boolean vehicleRemoved = this.entrySet().removeIf(vehicleUpdate -> vehicleUpdate.getValue()
            .getExpiration()
            .isBefore(ZonedDateTime.now()));

        final long duration = System.currentTimeMillis() - before;

        if (vehicleRemoved) {
            LOG.info("Removed {} expired vehicles in {} ms, current size: {}",
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
