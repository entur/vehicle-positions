/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package org.entur.vehicles.metrics;

import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.entur.vehicles.graphql.interceptors.Constants.CLIENT_HEADER_KEY;

@Service
public class PrometheusMetricsService {
    private static final Logger LOG = LoggerFactory.getLogger(PrometheusMetricsService.class);

    private static final String METRICS_PREFIX = "app.vehicles.";
    private static final String VEHICLE_DATA_COUNTER_NAME = METRICS_PREFIX + "data";
    private static final String TIMETABLE_DATA_COUNTER_NAME = METRICS_PREFIX + "timetable.data";
    private static final String SITUATION_DATA_COUNTER_NAME = METRICS_PREFIX + "situation.data";

    private static final String SITUATION_REPUBLISH_SCAN_COUNTER_NAME = METRICS_PREFIX + "situation.republish.scan";
    private static final String SITUATION_REPUBLISH_JOURNEY_COUNTER_NAME = METRICS_PREFIX + "situation.republish.journey";
    private static final String SITUATION_REPUBLISH_CHUNK_COUNTER_NAME = METRICS_PREFIX + "situation.republish.chunk";
    private static final String SITUATION_REPUBLISH_SKIPPED_COUNTER_NAME = METRICS_PREFIX + "situation.republish.skipped";

    private static final String QUERY_TYPE_LABEL = "query";
    private static final String SUBSCRIPTION_TYPE_LABEL = "subscription";
    private static final String QUERY_COUNTER_NAME = METRICS_PREFIX + QUERY_TYPE_LABEL;
    private static final String SUBSCRIPTION_COUNTER_NAME = METRICS_PREFIX + SUBSCRIPTION_TYPE_LABEL;
    private static final String SUBSCRIPTION_STARTED_NAME = METRICS_PREFIX + "subscription.started";
    private static final String SUBSCRIPTION_ENDED_NAME = METRICS_PREFIX + "subscription.ended";

    private static final String RETURNED_VEHICLE_UPDATE_COUNTER_NAME = METRICS_PREFIX + "client.response";
    private static final String CODESPACE_TAG_NAME = "codespaceId";

    private static final String PLANNED_DATA_LOAD_DURATION_NAME = METRICS_PREFIX + "planned.data.load.duration.millis";
    private static final String PLANNED_DATA_LAST_SUCCESS_NAME = METRICS_PREFIX + "planned.data.last.success.epoch.seconds";
    private static final String PLANNED_DATA_ENTITIES_NAME = METRICS_PREFIX + "planned.data.entities";
    private static final String PLANNED_DATA_UNRESOLVED_NAME = METRICS_PREFIX + "planned.data.unresolved.refs";
    private static final String PLANNED_DATA_LOAD_FAILURE_COUNTER_NAME = METRICS_PREFIX + "planned.data.load.failure";
    private static final String PLANNED_DATA_LOOKUP_MISS_COUNTER_NAME = METRICS_PREFIX + "planned.data.lookup.miss";

    private static final String SNAPSHOT_SOURCE_NAME = METRICS_PREFIX + "snapshot.source";
    private static final String SNAPSHOT_UPLOAD_COUNTER_NAME = METRICS_PREFIX + "snapshot.upload";
    private static final String NSR_LOAD_DURATION_NAME = METRICS_PREFIX + "nsr.load.duration.millis";

    private final PrometheusMeterRegistry prometheusMeterRegistry;

    private final AtomicInteger vehicleCounter = new AtomicInteger(0);
    private final AtomicInteger lastLoggedVehicleCount = new AtomicInteger(0);
    private final AtomicLong lastLoggedVehicleCountTimeMillis = new AtomicLong(System.currentTimeMillis());

    private final AtomicLong plannedDataLoadDurationMillis = new AtomicLong(0);
    private final AtomicLong plannedDataLastSuccessEpochSeconds = new AtomicLong(0);
    private final java.util.Map<String, AtomicLong> plannedDataGauges = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong nsrLoadDurationMillis = new AtomicLong(0);

    private final AtomicInteger timetableCounter = new AtomicInteger(0);
    private final AtomicInteger lastLoggedTimetableCount = new AtomicInteger(0);
    private final AtomicLong lastLoggedTimetableCountTimeMillis = new AtomicLong(System.currentTimeMillis());

    private final AtomicInteger situationCounter = new AtomicInteger(0);
    private final AtomicInteger lastLoggedSituationCount = new AtomicInteger(0);
    private final AtomicLong lastLoggedSituationCountTimeMillis = new AtomicLong(System.currentTimeMillis());


    private static final String QUERY_TYPE = "queryType";
    private static final String VEHICLES = "vehicles";
    private static final String LINES = "lines";
    private static final String SERVICE_JOURNEYS = "serviceJourneys";
    private static final String SERVICE_JOURNEY = "serviceJourney";
    private static final String DATED_SERVICE_JOURNEYS = "datedServiceJourneys";
    private static final String DATED_SERVICE_JOURNEY = "datedServiceJourney";
    private static final String OPERATORS = "operators";
    private static final String CODESPACES = "codespaces";
    private static final String SITUATIONS = "situations";

    public PrometheusMetricsService(@Autowired PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
        prometheusMeterRegistry.gauge(PLANNED_DATA_LOAD_DURATION_NAME, plannedDataLoadDurationMillis);
        prometheusMeterRegistry.gauge(PLANNED_DATA_LAST_SUCCESS_NAME, plannedDataLastSuccessEpochSeconds);
        prometheusMeterRegistry.gauge(NSR_LOAD_DURATION_NAME, nsrLoadDurationMillis);
    }

    @PreDestroy
    public void shutdown() {
        prometheusMeterRegistry.close();
    }

    public void markVehicleUpdate(int count, Codespace codespace) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(CODESPACE_TAG_NAME, codespace.getCodespaceId()));

        prometheusMeterRegistry.counter(VEHICLE_DATA_COUNTER_NAME, counterTags).increment(count);
        if (vehicleCounter.addAndGet(count) % 1000 == 0) {
            final int currentCount = vehicleCounter.get();

            LOG.debug("Processed {} vehicle-updates. Current rate: {}/s", currentCount, calculateRate(currentCount, lastLoggedVehicleCount, lastLoggedVehicleCountTimeMillis));

        }
    }
    public void markTimetableUpdate(int count, Codespace codespace) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(CODESPACE_TAG_NAME, codespace.getCodespaceId()));

        prometheusMeterRegistry.counter(TIMETABLE_DATA_COUNTER_NAME, counterTags).increment(count);
        if (timetableCounter.addAndGet(count) % 1000 == 0) {
            final int currentCount = timetableCounter.get();

            LOG.debug("Processed {} timetable-updates. Current rate: {}/s", currentCount, calculateRate(currentCount, lastLoggedTimetableCount, lastLoggedTimetableCountTimeMillis));

        }
    }

    public void markSituationUpdate(int count, Codespace codespace) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(CODESPACE_TAG_NAME, codespace.getCodespaceId()));

        prometheusMeterRegistry.counter(SITUATION_DATA_COUNTER_NAME, counterTags).increment(count);
        if (situationCounter.addAndGet(count) % 1000 == 0) {
            final int currentCount = situationCounter.get();

            LOG.debug("Processed {} situation-updates. Current rate: {}/s", currentCount,
                calculateRate(currentCount, lastLoggedSituationCount, lastLoggedSituationCountTimeMillis));
        }
    }

    /**
     * One scan of the timetable map triggered by a situation change (as opposed to a
     * redelivery/resend that {@code SituationTriggeredRepublisher} recognised as unchanged
     * and skipped). This is the counter that shows whether the cost model - a scan per
     * actual change, not per SX message - is holding in production.
     */
    public void markSituationRepublishScan() {
        prometheusMeterRegistry.counter(SITUATION_REPUBLISH_SCAN_COUNTER_NAME).increment();
    }

    public void markSituationRepublishedJourneys(int count) {
        prometheusMeterRegistry.counter(SITUATION_REPUBLISH_JOURNEY_COUNTER_NAME).increment(count);
    }

    public void markSituationRepublishChunk() {
        prometheusMeterRegistry.counter(SITUATION_REPUBLISH_CHUNK_COUNTER_NAME).increment();
    }

    /** A journey skipped mid-scan because it was concurrently being mutated by ET ingest. */
    public void markSituationRepublishSkipped() {
        prometheusMeterRegistry.counter(SITUATION_REPUBLISH_SKIPPED_COUNTER_NAME).increment();
    }

    public void markPlannedDataLoaded(long durationMillis, PlannedDataset.Stats stats) {
        plannedDataLoadDurationMillis.set(durationMillis);
        plannedDataLastSuccessEpochSeconds.set(System.currentTimeMillis() / 1000);

        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "operator", stats.operators());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "line", stats.lines());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "serviceJourney", stats.serviceJourneys());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "datedServiceJourney", stats.datedServiceJourneys());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "journeyPattern", stats.journeyPatterns());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "serviceLink", stats.serviceLinks());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "duplicateId", stats.duplicateIds());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "pattern", stats.unresolvedPatternRefs());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "link", stats.unresolvedLinkRefs());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "serviceJourney", stats.unresolvedServiceJourneyRefs());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "operatingDay", stats.unresolvedOperatingDayRefs());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "line", stats.unresolvedLineRefs());
    }

    private void gauge(String name, String tagKey, String tagValue, long value) {
        AtomicLong holder = plannedDataGauges.computeIfAbsent(name + "|" + tagKey + "|" + tagValue, k -> {
            AtomicLong a = new AtomicLong();
            prometheusMeterRegistry.gauge(name, List.of(new ImmutableTag(tagKey, tagValue)), a);
            return a;
        });
        holder.set(value);
    }

    public void markPlannedDataLoadFailure() {
        prometheusMeterRegistry.counter(PLANNED_DATA_LOAD_FAILURE_COUNTER_NAME).increment();
    }

    public void markPlannedDataLookupMiss(String type) {
        prometheusMeterRegistry.counter(PLANNED_DATA_LOOKUP_MISS_COUNTER_NAME, List.of(new ImmutableTag("type", type))).increment();
    }

    /** Which path the last load of a dataset took. Both label values are always present so a dashboard can plot either. */
    public void markSnapshotSource(String dataset, boolean fromSnapshot) {
        gauge(SNAPSHOT_SOURCE_NAME, List.of(new ImmutableTag("dataset", dataset), new ImmutableTag("source", "snapshot")), fromSnapshot ? 1 : 0);
        gauge(SNAPSHOT_SOURCE_NAME, List.of(new ImmutableTag("dataset", dataset), new ImmutableTag("source", "export")), fromSnapshot ? 0 : 1);
    }

    /** @param outcome uploaded, exists or failed */
    public void markSnapshotUpload(String dataset, String outcome) {
        prometheusMeterRegistry.counter(SNAPSHOT_UPLOAD_COUNTER_NAME,
                List.of(new ImmutableTag("dataset", dataset), new ImmutableTag("outcome", outcome))).increment();
    }

    public void markNsrLoaded(long durationMillis) {
        nsrLoadDurationMillis.set(durationMillis);
    }

    private void gauge(String name, List<ImmutableTag> tags, long value) {
        StringBuilder id = new StringBuilder(name);
        for (ImmutableTag tag : tags) {
            id.append('|').append(tag.getKey()).append('=').append(tag.getValue());
        }
        AtomicLong holder = plannedDataGauges.computeIfAbsent(id.toString(), k -> {
            AtomicLong a = new AtomicLong();
            prometheusMeterRegistry.gauge(name, List.copyOf(tags), a);
            return a;
        });
        holder.set(value);
    }

    private long calculateRate(int currentCount, AtomicInteger lastLoggedCount, AtomicLong lastLoggedCountTimeMillis) {

        long now = System.currentTimeMillis();

        final int updatesSinceLastTime = currentCount - lastLoggedCount.get();
        final long elapsedSinceLastTime = now - lastLoggedCountTimeMillis.get();

        final double elapsedTimeSeconds = Math.max((double) elapsedSinceLastTime / 1000, 0.1);

        final long rate = (long) (updatesSinceLastTime / elapsedTimeSeconds);

        lastLoggedCount.set(currentCount);
        lastLoggedCountTimeMillis.set(now);

        return rate;
    }

    public void markSubscription() {
        prometheusMeterRegistry
                .counter(SUBSCRIPTION_COUNTER_NAME,
                        List.of(new ImmutableTag(CLIENT_HEADER_KEY, getClientNameIfExists()))
                )
                .increment();
    }

    AtomicInteger subscriptionCounter = new AtomicInteger(0);

    public void markSubscriptionStarted() {
        prometheusMeterRegistry
                .counter(
                        SUBSCRIPTION_STARTED_NAME,
                        List.of(new ImmutableTag(CLIENT_HEADER_KEY, getClientNameIfExists()))
                )
                .increment();
    }
    public void markSubscriptionEnded() {
        prometheusMeterRegistry
                .counter(
                        SUBSCRIPTION_ENDED_NAME,
                        List.of(new ImmutableTag(CLIENT_HEADER_KEY, getClientNameIfExists()))
                )
                .increment();
    }

    public void markFilterMatch(Codespace codespace, MetricType metricType) {
        prometheusMeterRegistry
                .counter(
                        RETURNED_VEHICLE_UPDATE_COUNTER_NAME,
                        List.of(
                                new ImmutableTag(CLIENT_HEADER_KEY, getClientNameIfExists()),
                                new ImmutableTag(CODESPACE_TAG_NAME, codespace.getCodespaceId()),
                                new ImmutableTag(QUERY_TYPE, metricType.name())
                        )
                )
                .increment();
    }

    private void markQuery(String queryType) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(QUERY_TYPE, queryType));
        counterTags.add(new ImmutableTag(CLIENT_HEADER_KEY, getClientNameIfExists()));

        prometheusMeterRegistry
                .counter(QUERY_COUNTER_NAME, counterTags)
                .increment();
    }

    private static String getClientNameIfExists() {
        String clientName = MDC.get(CLIENT_HEADER_KEY);
        return clientName != null ? clientName:"";
    }

    public void markVehicleQuery() {
        markQuery(VEHICLES);
    }
    public void markLinesQuery() {
        markQuery(LINES);
    }
    public void markServiceJourneyQuery() {
        markQuery(SERVICE_JOURNEY);
    }
    public void markServiceJourneysQuery() {
        markQuery(SERVICE_JOURNEYS);
    }
    public void markDatedServiceJourneyQuery() {
        markQuery(DATED_SERVICE_JOURNEY);
    }
    public void markDatedServiceJourneysQuery() {
        markQuery(DATED_SERVICE_JOURNEYS);
    }

    public void markOperatorsQuery() {
        markQuery(OPERATORS);
    }
    public void markCodespacesQuery() {
        markQuery(CODESPACES);
    }
    public void markSituationsQuery() {
        markQuery(SITUATIONS);
    }
}
