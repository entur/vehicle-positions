package org.entur.vehicles.graphql.publishers;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A multicast sink split into shards, each with its own lock, so that several Pub/Sub executor
 * threads can publish at the same time.
 *
 * <p>A Reactor sink rejects overlapping emits, so a single sink needs one lock around every
 * emit, and an emit offers the update to every subscription's queue: about 130 µs with 180
 * subscriptions. Ingest then had a single lane, however many executor threads it had: in prd a
 * pod on a slower node fell ten minutes behind at the morning peak while its node had CPU to
 * spare, and adding pods cannot help, as each one ingests the whole feed. With shards, each
 * subscription lives in one shard, and each emit walks all shards starting from the next one in
 * turn, so concurrent emits mostly start, and stay, on different shards.
 *
 * <p>Each subscription still sees updates one at a time, in the order they were emitted to its
 * shard. Updates from different executor threads never had a defined order, but with one lock
 * every subscription saw the same order. Now two updates emitted at the same time - such as two
 * versions of one journey processed by two threads within about 100 µs - can reach subscriptions
 * in different shards in different orders. The next update for that journey or vehicle corrects
 * it.
 */
final class ShardedSink<T> {

    /** Matches the Pub/Sub executor threads per subscriber in prd */
    static final int DEFAULT_SHARDS = 8;

    private final Sinks.Many<T>[] shards;
    /** The shard the next subscription's choice starts from */
    private final AtomicInteger nextSubscriptionShard = new AtomicInteger();
    /** The shard the next emit starts from */
    private final AtomicInteger nextEmitShard = new AtomicInteger();

    @SuppressWarnings("unchecked")
    ShardedSink(int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1, was " + shardCount);
        }
        shards = new Sinks.Many[shardCount];
        for (int i = 0; i < shardCount; i++) {
            shards[i] = Sinks.many().multicast().directBestEffort();
        }
    }

    /**
     * Offers {@code value} to every subscription. Thread-safe.
     *
     * @param onFailure called at most once per emit, with the first shard's failed result other
     *                  than "no subscribers", so a lost update is counted once
     */
    void emit(T value, Consumer<Sinks.EmitResult> onFailure) {
        int start = Math.floorMod(nextEmitShard.getAndIncrement(), shards.length);
        Sinks.EmitResult failure = null;
        for (int n = 0; n < shards.length; n++) {
            Sinks.Many<T> shard = shards[(start + n) % shards.length];
            Sinks.EmitResult result;
            synchronized (shard) {
                result = shard.tryEmitNext(value);
            }
            // No subscribers means nobody is listening, not a lost update
            if (failure == null && result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                failure = result;
            }
        }
        if (failure != null) {
            onFailure.accept(failure);
        }
    }

    /**
     * A new subscription's stream. The shard is chosen when it is subscribed to, not when the
     * Flux is created: the one with the fewest subscriptions, ties going round-robin, so a burst
     * of clients reconnecting at once is still spread out.
     */
    Flux<T> asFlux() {
        return Flux.defer(() -> leastLoaded().asFlux());
    }

    private Sinks.Many<T> leastLoaded() {
        int offset = Math.floorMod(nextSubscriptionShard.getAndIncrement(), shards.length);
        Sinks.Many<T> least = null;
        int leastCount = Integer.MAX_VALUE;
        for (int n = 0; n < shards.length; n++) {
            Sinks.Many<T> shard = shards[(offset + n) % shards.length];
            int count = shard.currentSubscriberCount();
            if (count < leastCount) {
                least = shard;
                leastCount = count;
            }
        }
        return least;
    }

    int currentSubscriberCount() {
        int count = 0;
        for (Sinks.Many<T> shard : shards) {
            count += shard.currentSubscriberCount();
        }
        return count;
    }

    /** For tests */
    int[] subscribersPerShard() {
        int[] counts = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            counts[i] = shards[i].currentSubscriberCount();
        }
        return counts;
    }
}
