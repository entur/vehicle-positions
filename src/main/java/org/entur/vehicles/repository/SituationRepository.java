package org.entur.vehicles.repository;

import com.google.common.collect.Maps;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public class SituationRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SituationRepository.class);

    private final PrometheusMetricsService metricsService;
    private final SituationMapper mapper;
    private final AutoPurgingSituationMap situationMap;
    private final SituationUpdateRxPublisher publisher;
    private final SituationTriggeredRepublisher republisher;

    public SituationRepository(@Autowired PrometheusMetricsService metricsService,
                               @Autowired SituationMapper mapper,
                               @Autowired AutoPurgingSituationMap situationMap,
                               @Autowired SituationUpdateRxPublisher publisher,
                               @Autowired SituationTriggeredRepublisher republisher) {
        this.metricsService = metricsService;
        this.mapper = mapper;
        this.situationMap = situationMap;
        this.publisher = publisher;
        this.republisher = republisher;
        this.publisher.setRepository(this);
    }

    public void addAll(List<PtSituationElementRecord> records) {
        for (PtSituationElementRecord record : records) {
            add(record);
        }
    }

    public void add(PtSituationElementRecord record) {
        try {
            SituationUpdate situation = mapper.map(record);
            if (situation == null) {
                return;
            }

            SituationKey key = new SituationKey(situation.getCodespace(), situation.getSituationNumber());

            // Pub/Sub gives no ordering guarantee, so a redelivered message can carry an
            // older version of a situation that is already stored. The check-then-act
            // must be atomic: concurrent add() calls for the same key are real (the
            // subscriber runs with several parallel executor threads), so the version
            // guard is implemented via compute() rather than a separate get()+put().
            // The mapping function must stay side-effect free - it runs while
            // ConcurrentHashMap holds a bin lock, so publishing or recording metrics in
            // here risks blocking or deadlock.
            // The previous version is captured for the republisher: when a situation closes, the
            // matcher excludes it, so matching only the new state would find no journeys to
            // republish - and a situation that narrows no longer names the stops whose journeys
            // must be told. Assigning a reference is not the kind of side effect the comment above
            // rules out: it neither blocks nor performs I/O, and compute() does not re-invoke the
            // mapping function.
            AtomicReference<SituationUpdate> previous = new AtomicReference<>();
            SituationUpdate accepted = situationMap.compute(key, (k, stored) -> {
                previous.set(stored);
                return isSupersededByStoredVersion(stored, situation) ? stored : situation;
            });

            if (accepted != situation) {
                LOG.debug("Ignoring out-of-order update for {} - version {} is older than the stored one.",
                        situation.getSituationNumber(), situation.getVersion());
                return;
            }

            // Publishing happens outside the compute() lock above (as it must - see the
            // comment there), so two threads that both pass the version guard for the
            // same key can publish out of order: e.g. thread A accepts v3 while thread B
            // accepts v2, B's compute() runs first, but A reaches this line first. The map
            // itself stays correct - only the stream can briefly regress. This is accepted
            // as eventually consistent; clients must track `version` per situationNumber
            // and discard a regression rather than relying on stream order.
            publisher.publishUpdate(situation);

            metricsService.markSituationUpdate(1, situation.getCodespace());

            republisher.onSituationChanged(previous.get(), situation);
        } catch (RuntimeException e) {
            LOG.warn("Update ignored.", e);
        }
    }

    private static boolean isSupersededByStoredVersion(SituationUpdate stored, SituationUpdate incoming) {
        if (stored == null || stored.getVersion() == null || incoming.getVersion() == null) {
            return false;
        }
        return incoming.getVersion() < stored.getVersion();
    }

    public Collection<SituationUpdate> getSituations(SituationFilter filter) {
        if (filter != null) {
            final long filteringStart = System.currentTimeMillis();

            final Map<SituationKey, SituationUpdate> situations =
                    Maps.filterValues(situationMap, filter::isMatch);

            final long filteringDone = System.currentTimeMillis();
            if (filteringDone - filteringStart > 50) {
                LOG.info("Filtering situations took {} ms", (filteringDone - filteringStart));
            }
            return situations.values();
        }

        return situationMap.values();
    }
}
