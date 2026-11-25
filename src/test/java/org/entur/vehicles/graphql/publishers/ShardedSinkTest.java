package org.entur.vehicles.graphql.publishers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ingest used to emit to WebSocket subscriptions under a single lock, which capped a pod's
 * throughput whatever its number of Pub/Sub threads: in prd a pod fell ten minutes behind at the
 * morning peak. The sink is now split into shards with a lock each. That must not lose or
 * duplicate updates, and subscriptions must stay spread over the shards.
 */
class ShardedSinkTest {

    private final List<Disposable> subscriptions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        subscriptions.forEach(Disposable::dispose);
    }

    @Test
    void everySubscriptionGetsEveryUpdateWhenManyThreadsEmitAtOnce() throws Exception {
        ShardedSink<Integer> sink = new ShardedSink<>(4);
        int subscriberCount = 10;
        List<AtomicInteger> received = new ArrayList<>();
        for (int s = 0; s < subscriberCount; s++) {
            AtomicInteger count = new AtomicInteger();
            received.add(count);
            subscriptions.add(sink.asFlux().subscribe(v -> count.incrementAndGet()));
        }
        AtomicInteger failures = new AtomicInteger();

        int threads = 8;
        int perThread = 5_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        sink.emit(i, result -> failures.incrementAndGet());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        // Overlapping emits to one shard would fail with FAIL_NON_SERIALIZED and be lost
        assertThat(failures).hasValue(0);
        for (AtomicInteger count : received) {
            assertThat(count).hasValue(threads * perThread);
        }
    }

    @Test
    void subscriptionsAreSpreadEvenlyAndRefillTheEmptiestShard() {
        ShardedSink<Integer> sink = new ShardedSink<>(4);
        List<Disposable> first = new ArrayList<>();
        for (int s = 0; s < 8; s++) {
            first.add(sink.asFlux().subscribe());
        }
        assertThat(sink.subscribersPerShard()).containsExactly(2, 2, 2, 2);

        // Two clients of the same shard leave; the next two subscriptions go there
        int[] before = sink.subscribersPerShard();
        first.get(0).dispose();
        first.get(4).dispose();
        int[] after = sink.subscribersPerShard();
        int emptied = -1;
        for (int i = 0; i < after.length; i++) {
            if (after[i] == before[i] - 2) {
                emptied = i;
            }
        }
        assertThat(emptied).as("both disposed subscriptions were in one shard").isNotNegative();

        subscriptions.add(sink.asFlux().subscribe());
        subscriptions.add(sink.asFlux().subscribe());
        assertThat(sink.subscribersPerShard()).containsExactly(2, 2, 2, 2);
        assertThat(sink.currentSubscriberCount()).isEqualTo(8);
        subscriptions.addAll(first);
    }

    @Test
    void theShardIsChosenWhenSubscribedNotWhenTheFluxIsCreated() {
        ShardedSink<Integer> sink = new ShardedSink<>(4);
        // Fluxes created together, then subscribed one by one - as when many clients reconnect
        List<Flux<Integer>> fluxes = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            fluxes.add(sink.asFlux());
        }
        fluxes.forEach(f -> subscriptions.add(f.subscribe()));
        assertThat(sink.subscribersPerShard()).containsExactly(1, 1, 1, 1);
    }

    @Test
    void aThreadStuckInOneShardDoesNotStopAnotherThreadReachingTheOtherShards() throws Exception {
        ShardedSink<Integer> sink = new ShardedSink<>(2);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherReceived = new CountDownLatch(1);
        // Subscribed first, so in shard 0: blocks on 1 while holding that shard's lock
        subscriptions.add(sink.asFlux().subscribe(v -> {
            if (v == 1) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));
        // In shard 1
        subscriptions.add(sink.asFlux().subscribe(v -> {
            if (v == 2) {
                otherReceived.countDown();
            }
        }));
        assertThat(sink.subscribersPerShard()).containsExactly(1, 1);

        Thread stuck = null;
        Thread other = null;
        try {
            // The first emit starts at shard 0 and stays there, holding its lock
            stuck = Thread.ofPlatform().daemon().start(() -> sink.emit(1, r -> { }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            // The next emit starts at shard 1, so it is delivered there without waiting
            other = Thread.ofPlatform().daemon().start(() -> sink.emit(2, r -> { }));
            assertThat(otherReceived.await(5, TimeUnit.SECONDS))
                    .as("shard 1 got the update while shard 0 was held").isTrue();
            assertThat(stuck.isAlive()).isTrue();
        } finally {
            release.countDown();
        }
        stuck.join(5_000);
        other.join(5_000);
        assertThat(stuck.isAlive()).isFalse();
        assertThat(other.isAlive()).isFalse();
    }

    @Test
    void anUpdateNoShardCouldTakeIsReportedOnce() {
        ShardedSink<Integer> sink = new ShardedSink<>(4);
        // A subscription that never requests anything: directBestEffort cannot deliver to it,
        // and a shard where no subscription can take the update fails with FAIL_OVERFLOW
        for (int s = 0; s < 4; s++) {
            BaseSubscriber<Integer> noDemand = new BaseSubscriber<>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                }
            };
            sink.asFlux().subscribe(noDemand);
            subscriptions.add(noDemand);
        }
        assertThat(sink.subscribersPerShard()).containsExactly(1, 1, 1, 1);

        List<Sinks.EmitResult> failures = new ArrayList<>();
        sink.emit(1, failures::add);

        assertThat(failures).containsExactly(Sinks.EmitResult.FAIL_OVERFLOW);
    }

    @Test
    void noSubscribersIsNotAFailure() {
        ShardedSink<Integer> sink = new ShardedSink<>(3);
        List<Sinks.EmitResult> failures = new ArrayList<>();
        sink.emit(1, failures::add);
        assertThat(failures).isEmpty();
        assertThat(sink.currentSubscriberCount()).isZero();
    }

    @Test
    void atLeastOneShard() {
        assertThatThrownBy(() -> new ShardedSink<Integer>(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
