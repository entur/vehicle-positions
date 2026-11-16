package org.entur.vehicles.graphql.publishers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Batches dropped for a slow subscriber are counted for timetables and situations too */
class TimetableSituationPublisherMetricsTest {

    private PrometheusMeterRegistry registry;
    private PrometheusMetricsService metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new PrometheusMetricsService(registry);
    }

    private double backpressure(String type) {
        return registry.find("app.vehicles.dropped").tags("dataType", type, "reason", "backpressure")
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    /** A subscriber that never requests anything: every batch hits onBackpressureDrop */
    private static <T> BaseSubscriber<List<T>> stalled() {
        return new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
            }
        };
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void timetableBatchesDroppedForASlowSubscriberAreCounted() throws Exception {
        EstimatedTimetableUpdateRxPublisher publisher = new EstimatedTimetableUpdateRxPublisher(metrics);
        BaseSubscriber<List<EstimatedTimetableUpdate>> subscriber = stalled();
        publisher.getPublisher(new QueryFilter(metrics, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 20, 250), "test").subscribe(subscriber);
        try {
            for (int i = 0; i < 50; i++) {
                EstimatedTimetableUpdate update = new EstimatedTimetableUpdate();
                update.setCodespace(Codespace.getCodespace("TST"));
                publisher.publishUpdate(update);
            }
            waitFor(() -> backpressure("timetable") >= 50);
            assertEquals(50, backpressure("timetable"));
        } finally {
            subscriber.dispose();
        }
    }

    @Test
    void situationBatchesDroppedForASlowSubscriberAreCounted() throws Exception {
        SituationUpdateRxPublisher publisher = new SituationUpdateRxPublisher(metrics);
        BaseSubscriber<List<SituationUpdate>> subscriber = stalled();
        publisher.getPublisher(new SituationFilter(metrics, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, true, 20, 250), "test").subscribe(subscriber);
        try {
            for (int i = 0; i < 50; i++) {
                SituationUpdate update = new SituationUpdate();
                update.setCodespace(Codespace.getCodespace("TST"));
                publisher.publishUpdate(update);
            }
            waitFor(() -> backpressure("situation") >= 50);
            assertEquals(50, backpressure("situation"));
        } finally {
            subscriber.dispose();
        }
    }
}
