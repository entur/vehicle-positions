package org.entur.vehicles.graphql.publishers;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Updates are published from several Pub/Sub executor threads at once. Every published update
 * must reach a live subscriber. A Reactor sink rejects overlapping emits with
 * FAIL_NON_SERIALIZED, so emits that are not serialised are lost.
 *
 * <p>The burst fits in one subscription's queue: a tight loop publishes far faster than any
 * subscription drains, and beyond {@link SubscriberQueue#CAPACITY} the overflow is dropped and
 * counted by design (see VehicleUpdateRxPublisherMetricsTest), which is not what this test is about.
 */
class RxPublisherConcurrencyTest {

    private static final int THREADS = 8;
    private static final int UPDATES_PER_THREAD = SubscriberQueue.CAPACITY / THREADS;
    private static final int TOTAL = THREADS * UPDATES_PER_THREAD;

    private static final Codespace CODESPACE = Codespace.getCodespace("TST");

    private final PrometheusMetricsService metrics =
            new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

    private QueryFilter matchAllQuery() {
        return new QueryFilter(metrics, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 20, 250);
    }

    @Test
    void concurrentVehicleUpdatesAllReachSubscriber() throws Exception {
        VehicleUpdateRxPublisher publisher = new VehicleUpdateRxPublisher(metrics);
        assertAllDelivered(
                received -> publisher.getPublisher(matchAllQuery(), "test").subscribe(batch -> received.accept(batch.size())),
                () -> {
                    VehicleUpdate update = new VehicleUpdate();
                    update.setCodespace(CODESPACE);
                    publisher.publishUpdate(update);
                });
    }

    @Test
    void concurrentTimetableUpdatesAllReachSubscriber() throws Exception {
        EstimatedTimetableUpdateRxPublisher publisher = new EstimatedTimetableUpdateRxPublisher(metrics);
        assertAllDelivered(
                received -> publisher.getPublisher(matchAllQuery(), "test").subscribe(batch -> received.accept(batch.size())),
                () -> {
                    EstimatedTimetableUpdate update = new EstimatedTimetableUpdate();
                    update.setCodespace(CODESPACE);
                    publisher.publishUpdate(update);
                });
    }

    @Test
    void concurrentSituationUpdatesAllReachSubscriber() throws Exception {
        SituationUpdateRxPublisher publisher = new SituationUpdateRxPublisher(metrics);
        SituationFilter matchAll = new SituationFilter(metrics, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, true, 20, 250);
        assertAllDelivered(
                received -> publisher.getPublisher(matchAll, "test").subscribe(batch -> received.accept(batch.size())),
                () -> {
                    SituationUpdate update = new SituationUpdate();
                    update.setCodespace(CODESPACE);
                    publisher.publishUpdate(update);
                });
    }

    /**
     * @param subscribe subscribes to the publisher, reporting the size of each received batch
     */
    private void assertAllDelivered(Function<IntConsumer, Disposable> subscribe, Runnable publishOne) throws Exception {
        AtomicInteger received = new AtomicInteger();
        Disposable subscription = subscribe.apply(received::addAndGet);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < UPDATES_PER_THREAD; i++) {
                        publishOne.run();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);

            // Let the last partial batch flush (bufferTime is 250 ms)
            long deadline = System.currentTimeMillis() + 2_000;
            while (received.get() < TOTAL && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(TOTAL, received.get(), "updates lost between publisher and subscriber");
        } finally {
            subscription.dispose();
            pool.shutdownNow();
        }
    }
}
