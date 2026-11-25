package org.entur.vehicles.graphql.publishers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new subscription gets a snapshot of the current data, then live updates. It used to subscribe
 * to the live updates only once the whole snapshot had been sent, which for a full-feed client on
 * a slow connection took seconds: every update published meanwhile was lost to it, and a vehicle
 * kept its snapshot position until its next update. Nothing may fall between the snapshot and the
 * live updates.
 */
class SubscriberQueueSnapshotTest {

    private final Sinks.Many<Integer> live = Sinks.many().multicast().directBestEffort();
    private final List<Integer> received = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger overflow = new AtomicInteger();
    private Disposable subscription;

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private void awaitReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (received.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(received).hasSize(count);
    }

    private static List<Integer> snapshot(int size) {
        List<Integer> snapshot = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            snapshot.add(-1 - i);
        }
        return snapshot;
    }

    @Test
    void anUpdatePublishedWhileTheSnapshotIsSentIsDeliveredAfterIt() throws Exception {
        // More than the subscription's queue holds, to a client stuck on the first one
        List<Integer> snapshot = snapshot(SubscriberQueue.CAPACITY * 2);
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        subscription = SubscriberQueue.decouple(live.asFlux(), () -> snapshot, v -> overflow.incrementAndGet())
                .subscribe(v -> {
                    if (received.isEmpty()) {
                        stuck.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    received.add(v);
                });
        assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();

        live.tryEmitNext(1);
        live.tryEmitNext(2);
        release.countDown();

        awaitReceived(snapshot.size() + 2);
        assertThat(received.subList(0, snapshot.size())).isEqualTo(snapshot);
        assertThat(received.subList(snapshot.size(), received.size())).containsExactly(1, 2);
        assertThat(overflow).hasValue(0);
    }

    @Test
    void anUpdatePublishedWhileTheSnapshotIsReadIsNotLost() throws Exception {
        // The update is stored after the snapshot was read and published before the subscription
        // reached the live stream - unless the subscription reaches it first
        subscription = SubscriberQueue.decouple(live.asFlux(), () -> {
                    List<Integer> snapshot = snapshot(3);
                    live.tryEmitNext(1);
                    return snapshot;
                }, v -> overflow.incrementAndGet())
                .subscribe(received::add);

        awaitReceived(4);
        assertThat(received).containsExactly(-1, -2, -3, 1);
    }

    @Test
    void liveUpdatesBeyondTheQueueWhileTheSnapshotIsSentAreCountedAsOverflow() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        subscription = SubscriberQueue.decouple(live.asFlux(), () -> snapshot(1), v -> overflow.incrementAndGet())
                .subscribe(v -> {
                    stuck.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();

        int extra = 100;
        for (int i = 0; i < SubscriberQueue.CAPACITY + SubscriberQueue.HANDOFF + extra; i++) {
            live.tryEmitNext(i);
        }
        release.countDown();
        assertThat(overflow.get()).isBetween(extra, SubscriberQueue.HANDOFF + extra);
    }

    @Test
    void cancellingLeavesTheLiveStream() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        subscription = SubscriberQueue.decouple(live.asFlux(), () -> snapshot(10), v -> { })
                .subscribe(v -> {
                    stuck.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(live.currentSubscriberCount()).isEqualTo(1);

        subscription.dispose();
        release.countDown();
        assertThat(live.currentSubscriberCount()).isZero();
    }
}
