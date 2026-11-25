package org.entur.vehicles.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On 2026-11-25 one prd pod fell ten minutes behind on vehicle positions while the dashboard
 * showed a flat "1 min": the delivery histogram ended at 60 s, and nothing showed that the pod's
 * ingest threads were saturated or how many clients it carried. These metrics show both.
 */
class IngestSaturationMetricsTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final PrometheusMetricsService metrics = new PrometheusMetricsService(registry);

    private double gauge(String name, String type) {
        Gauge gauge = registry.find(name).tag("dataType", type).gauge();
        assertThat(gauge).as("%s{dataType=%s}", name, type).isNotNull();
        return gauge.value();
    }

    @Test
    void deliveryLatencyIsBucketedUpToTheSubscriptionRetention() {
        metrics.recordPubsubLatency(PrometheusMetricsService.TYPE_VEHICLE, Duration.ofSeconds(400));

        String scrape = registry.scrape();
        assertThat(scrape)
                .contains("app_vehicles_latency_pubsub_seconds_bucket{dataType=\"vehicle\",le=\"300.0\"} 0")
                .contains("app_vehicles_latency_pubsub_seconds_bucket{dataType=\"vehicle\",le=\"600.0\"} 1");
    }

    @Test
    void ingestThreadsAreExposedPerDataType() {
        metrics.registerIngestThreads(PrometheusMetricsService.TYPE_VEHICLE, 8);

        assertThat(gauge("app.vehicles.ingest.threads", "vehicle")).isEqualTo(8);
    }

    @Test
    void activeSubscriptionsFollowThePublishers() {
        VehicleUpdateRxPublisher vehicles = new VehicleUpdateRxPublisher(metrics);
        new EstimatedTimetableUpdateRxPublisher(metrics);
        new SituationUpdateRxPublisher(metrics);
        QueryFilter all = new QueryFilter(metrics, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 20, 50);

        assertThat(gauge("app.vehicles.subscriptions.active", "vehicle")).isZero();
        assertThat(gauge("app.vehicles.subscriptions.active", "timetable")).isZero();
        assertThat(gauge("app.vehicles.subscriptions.active", "situation")).isZero();

        Disposable first = vehicles.getPublisher(all, "a").subscribe();
        Disposable second = vehicles.getPublisher(all, "b").subscribe();
        assertThat(gauge("app.vehicles.subscriptions.active", "vehicle")).isEqualTo(2);

        first.dispose();
        second.dispose();
        assertThat(gauge("app.vehicles.subscriptions.active", "vehicle")).isZero();
    }
}
