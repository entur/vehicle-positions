package org.entur.vehicles.graphql.publishers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * publishUpdate runs on the Pub/Sub executor threads, under a lock every one of them contends on.
 * It used to run each subscription's filter and buffer inline, so ingest slowed down with every
 * connected client and one slow subscription held up all of them - in prd the busiest pods fell
 * ten minutes behind and Pub/Sub expired positions before they were processed. A subscription's
 * own work must not hold up publishing, or the other subscriptions.
 */
class SlowSubscriberIsolationTest {

    private static final Codespace CODESPACE = Codespace.getCodespace("TST");

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final PrometheusMetricsService metrics = new PrometheusMetricsService(registry);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Disposable> subscriptions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        release.countDown();
        subscriptions.forEach(Disposable::dispose);
    }

    private QueryFilter matchAll() {
        return new QueryFilter(metrics, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 20, 50);
    }

    /** A filter that never returns until the test ends - a subscription stuck in its own work */
    private QueryFilter blockingFilter() {
        QueryFilter filter = Mockito.mock(QueryFilter.class);
        when(filter.getBufferSize()).thenReturn(20);
        when(filter.getBufferTimeMillis()).thenReturn(50);
        when(filter.isMatch(any(VehicleUpdate.class))).thenAnswer(i -> { release.await(); return true; });
        when(filter.isMatch(any(EstimatedTimetableUpdate.class))).thenAnswer(i -> { release.await(); return true; });
        return filter;
    }

    private static VehicleUpdate vehicle() {
        VehicleUpdate update = new VehicleUpdate();
        update.setCodespace(CODESPACE);
        return update;
    }

    private double dropped(String type, String reason) {
        return registry.find("app.vehicles.dropped").tags("dataType", type, "reason", reason)
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void aStuckSubscriptionDoesNotBlockPublishingOrOtherSubscriptions() throws Exception {
        VehicleUpdateRxPublisher publisher = new VehicleUpdateRxPublisher(metrics);
        subscriptions.add(publisher.getPublisher(blockingFilter(), "stuck").subscribe());
        AtomicInteger received = new AtomicInteger();
        subscriptions.add(publisher.getPublisher(matchAll(), "healthy").subscribe(b -> received.addAndGet(b.size())));

        // In chunks the healthy subscription can keep up with, so only the stuck one overflows
        int chunk = 512;
        int total = SubscriberQueue.CAPACITY + 2 * chunk;
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int published = 0; published < total; ) {
                for (int i = 0; i < chunk; i++, published++) {
                    publisher.publishUpdate(vehicle());
                }
                long deadline = System.currentTimeMillis() + 2_000;
                while (received.get() < published && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5);
                }
                assertEquals(published, received.get(), "the healthy subscription missed updates");
            }
        }, "publishUpdate waited on a subscription's own work");

        // The stuck subscription holds one update in its filter and CAPACITY in its queue
        assertTrue(dropped("vehicle", "subscriber_queue_full") >= total - SubscriberQueue.CAPACITY - 1,
                "overflow of a stuck subscription must be counted as dropped");
    }

    @Test
    void aStuckTimetableSubscriptionDoesNotBlockPublishing() {
        EstimatedTimetableUpdateRxPublisher publisher = new EstimatedTimetableUpdateRxPublisher(metrics);
        subscriptions.add(publisher.getPublisher(blockingFilter(), "stuck").subscribe());

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 100; i++) {
                EstimatedTimetableUpdate update = new EstimatedTimetableUpdate();
                update.setCodespace(CODESPACE);
                publisher.publishUpdate(update);
            }
        }, "publishUpdate waited on a subscription's own work");
    }
}
