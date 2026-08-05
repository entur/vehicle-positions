package org.entur.vehicles.repository;

import org.entur.vehicles.data.SituationUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;

@Component
public class AutoPurgingSituationMap extends AutoPurgingMap<SituationKey, SituationUpdate> {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPurgingSituationMap.class);

    public AutoPurgingSituationMap(
            @Value("${situation.updates.purge.interval:PT1M}") Duration purgeInterval,
            @Value("${situation.updates.expiry.grace.period:PT10M}") Duration gracePeriod) {
        super(purgeInterval, gracePeriod);
    }

    public void removeExpiredEntries() {
        long before = System.currentTimeMillis();

        int sizeBefore = this.size();

        // A null expiration means the situation never expires - open-ended situations
        // are retained indefinitely so that producers who never close them can be found.
        final boolean entriesRemoved = this.entrySet().removeIf(entry -> {
            ZonedDateTime expiration = entry.getValue().getExpiration();
            return expiration != null
                    && expiration.plus(gracePeriod).isBefore(ZonedDateTime.now());
        });

        final long duration = System.currentTimeMillis() - before;

        if (entriesRemoved) {
            LOG.debug("Removed {} expired situations in {} ms, current size: {}",
                sizeBefore - this.size(),
                duration,
                this.size()
            );
        }

        if (duration > 20) {
            LOG.warn("Removing expired situations took {} ms", duration);
        }
    }
}
