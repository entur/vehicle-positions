package org.entur.vehicles.repository;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class AutoPurgingMap<K, V> extends ConcurrentHashMap<K, V> {

    final Duration gracePeriod;

    public AutoPurgingMap(Duration purgeInterval, Duration gracePeriod) {
        super();
        this.gracePeriod = gracePeriod;
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        long purgeIntervalSeconds = purgeInterval.getSeconds();
        service.scheduleWithFixedDelay(this::removeExpiredEntries, purgeIntervalSeconds, purgeIntervalSeconds, TimeUnit.SECONDS);
    }

    public abstract void removeExpiredEntries();
}
