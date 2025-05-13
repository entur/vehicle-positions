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
import io.micrometer.prometheus.PrometheusMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.model.Codespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.entur.vehicles.graphql.Constants.CLIENT_HEADER_KEY;

@Service
public class PrometheusMetricsService {
    private static final Logger LOG = LoggerFactory.getLogger(PrometheusMetricsService.class);

    private static final String METRICS_PREFIX = "app.vehicles.";
    private static final String DATA_COUNTER_NAME = METRICS_PREFIX + "data";

    private static final String QUERY_TYPE_LABEL = "query";
    private static final String SUBSCRIPTION_TYPE_LABEL = "subscription";
    private static final String QUERY_COUNTER_NAME = METRICS_PREFIX + QUERY_TYPE_LABEL;
    private static final String SUBSCRIPTION_COUNTER_NAME = METRICS_PREFIX + SUBSCRIPTION_TYPE_LABEL;
    private static final String SUBSCRIPTION_STARTED_NAME = METRICS_PREFIX + "subscription.started";
    private static final String SUBSCRIPTION_ENDED_NAME = METRICS_PREFIX + "subscription.ended";

    private static final String JOURNEY_PLANNER_REQUEST_COUNTER_NAME = METRICS_PREFIX + "journeyplanner.request";
    private static final String JOURNEY_PLANNER_RESPONSE_COUNTER_NAME = METRICS_PREFIX + "journeyplanner.response";
    private static final String RETURNED_VEHICLE_UPDATE_COUNTER_NAME = METRICS_PREFIX + "client.response";
    private static final String CODESPACE_TAG_NAME = "codespaceId";
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    private int lastLoggedCount;
    private long lastLoggedCountTimeMillis = System.currentTimeMillis();

    private final AtomicInteger counter = new AtomicInteger(0);

    private static final String QUERY_TYPE = "queryType";
    private static final String VEHICLES = "vehicles";
    private static final String LINES = "lines";
    private static final String SERVICE_JOURNEYS = "serviceJourneys";
    private static final String SERVICE_JOURNEY = "serviceJourney";
    private static final String OPERATORS = "operators";
    private static final String CODESPACES = "codespaces";

    public PrometheusMetricsService(@Autowired PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @PreDestroy
    public void shutdown() {
        prometheusMeterRegistry.close();
    }

    public void markUpdate(int count, Codespace codespace) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(CODESPACE_TAG_NAME, codespace.getCodespaceId()));

        prometheusMeterRegistry.counter(DATA_COUNTER_NAME, counterTags).increment(count);
        if (counter.addAndGet(count) % 1000 == 0) {
            final int currentCount = counter.get();

            LOG.debug("Processed {} updates. Current rate: {}/s", currentCount, calculateRate(currentCount));

        }
    }

    private long calculateRate(int currentCount) {
        long now = System.currentTimeMillis();

        final int updatesSinceLastTime = currentCount - lastLoggedCount;
        final long elapsedSinceLastTime = now - lastLoggedCountTimeMillis;

        final double elapsedTimeSeconds = Math.max((double) elapsedSinceLastTime / 1000, 0.1);

        final long rate = (long) (updatesSinceLastTime / elapsedTimeSeconds);

        lastLoggedCount = currentCount;
        lastLoggedCountTimeMillis = now;

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

    public void markJourneyPlannerRequest(String queryType) {

        prometheusMeterRegistry
                .counter(
                        JOURNEY_PLANNER_REQUEST_COUNTER_NAME,
                        List.of(new ImmutableTag(QUERY_TYPE, queryType))
                )
                .increment();
    }
    public void markJourneyPlannerResponse(String queryType) {
        prometheusMeterRegistry
                .counter(
                        JOURNEY_PLANNER_RESPONSE_COUNTER_NAME,
                        List.of(new ImmutableTag(QUERY_TYPE, queryType))
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

    public void markOperatorsQuery() {
        markQuery(OPERATORS);
    }
    public void markCodespacesQuery() {
        markQuery(CODESPACES);
    }
}
