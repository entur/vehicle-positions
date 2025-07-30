package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;

@Component
public class AutoPurgingTimetableMap extends AutoPurgingMap<EstimatedTimetableUpdate> {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPurgingTimetableMap.class);

    public AutoPurgingTimetableMap(
            @Value("${timetable.updates.purge.interval:PT1M}") Duration purgeInterval,
            @Value("${timetable.updates.expiry.grace.period:PT10M}") Duration gracePeriod) {
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
            LOG.debug("Removed {} expired timetables in {} ms, current size: {}",
                sizeBefore-this.size(),
                duration,
                this.size()
            );
        }

        if (duration > 20) {
            LOG.warn("Removing expired timetables took {} ms", duration);
        }

    }
}
