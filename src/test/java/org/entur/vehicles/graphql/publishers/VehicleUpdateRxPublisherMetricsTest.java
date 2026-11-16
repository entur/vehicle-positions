package org.entur.vehicles.graphql.publishers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every update handed to the publisher either reaches a live subscriber or is counted in
 * {@code app.vehicles.dropped}. Pub/Sub calls the publisher from several threads at once.
 */
class VehicleUpdateRxPublisherMetricsTest {

    private static final int THREADS = 8;
    private static final int UPDATES_PER_THREAD = 5_000;
    private static final int TOTAL = THREADS * UPDATES_PER_THREAD;

    private PrometheusMeterRegistry registry;
    private PrometheusMetricsService metrics;
    private VehicleUpdateRxPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new PrometheusMetricsService(registry);
        publisher = new VehicleUpdateRxPublisher(metrics);
    }

    private QueryFilter matchAll() {
        return new QueryFilter(metrics, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 20, 250);
    }

    private static VehicleUpdate update() {
        VehicleUpdate update = new VehicleUpdate();
        update.setCodespace(Codespace.getCodespace("TST"));
        return update;
    }

    private double dropped(String reason) {
        return registry.find("app.vehicles.dropped").tags("type", "vehicle", "reason", reason)
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    private double droppedForAnyReason() {
        return registry.find("app.vehicles.dropped").tag("type", "vehicle")
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void everyConcurrentUpdateIsDeliveredOrCountedAsDropped() throws Exception {
        AtomicInteger received = new AtomicInteger();
        Disposable subscription = publisher.getPublisher(matchAll(), "test")
                .subscribe(batch -> received.addAndGet(batch.size()));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < UPDATES_PER_THREAD; i++) {
                        publisher.publishUpdate(update());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);

            waitFor(() -> received.get() + droppedForAnyReason() >= TOTAL, 2_000);
            assertEquals(TOTAL, received.get() + droppedForAnyReason(),
                    "received " + received.get() + ", counted as dropped " + droppedForAnyReason());
        } finally {
            subscription.dispose();
            pool.shutdownNow();
        }
    }

    @Test
    void batchesDroppedForASlowSubscriberAreCounted() throws Exception {
        // A subscriber that never requests anything: every batch hits onBackpressureDrop
        BaseSubscriber<List<VehicleUpdate>> stalled = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
            }
        };
        publisher.getPublisher(matchAll(), "test").subscribe(stalled);
        try {
            for (int i = 0; i < 50; i++) {
                publisher.publishUpdate(update());
            }
            // Two full batches of 20 go at once, the last 10 after bufferTime (250 ms)
            waitFor(() -> dropped("backpressure") >= 50, 2_000);
            assertEquals(50, dropped("backpressure"));
        } finally {
            stalled.dispose();
        }
    }

    private static void waitFor(Supplier<Boolean> condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
