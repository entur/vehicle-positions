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

@Repository
public class SituationRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SituationRepository.class);

    private final PrometheusMetricsService metricsService;
    private final SituationMapper mapper;
    private final AutoPurgingSituationMap situationMap;
    private final SituationUpdateRxPublisher publisher;

    public SituationRepository(@Autowired PrometheusMetricsService metricsService,
                               @Autowired SituationMapper mapper,
                               @Autowired AutoPurgingSituationMap situationMap,
                               @Autowired SituationUpdateRxPublisher publisher) {
        this.metricsService = metricsService;
        this.mapper = mapper;
        this.situationMap = situationMap;
        this.publisher = publisher;
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

            if (isSupersededByStoredVersion(key, situation)) {
                LOG.debug("Ignoring out-of-order update for {} - version {} is older than the stored one.",
                        situation.getSituationNumber(), situation.getVersion());
                return;
            }

            situationMap.put(key, situation);
            publisher.publishUpdate(situation);

            metricsService.markSituationUpdate(1, situation.getCodespace());
        } catch (RuntimeException e) {
            LOG.warn("Update ignored.", e);
        }
    }

    /**
     * Pub/Sub gives no ordering guarantee, so a redelivered message can carry an older
     * version of a situation that is already stored.
     */
    private boolean isSupersededByStoredVersion(SituationKey key, SituationUpdate incoming) {
        SituationUpdate stored = situationMap.get(key);
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
