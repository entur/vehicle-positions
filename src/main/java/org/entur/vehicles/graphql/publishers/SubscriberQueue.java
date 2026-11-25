package org.entur.vehicles.graphql.publishers;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.Consumer;

/**
 * Gives each subscription its own bounded queue, drained on Reactor's parallel scheduler.
 *
 * <p>The publishers emit from the Pub/Sub executor threads, serialised per shard of subscriptions
 * ({@link ShardedSink}). Without this, each subscription's filter and buffer ran inline under that
 * lock: ingest became a single lane whose cost grew with every connected client, and one slow
 * subscription held up all of them. With it, an emit only offers the update to each
 * subscription's queue; filtering and buffering run in parallel, off the ingest path. Emits to
 * different shards run concurrently, so {@code onOverflow} can be called from several threads at
 * once.
 *
 * <p>A subscription that cannot keep up loses updates once its queue is full - each one counted
 * through {@code onOverflow} - rather than slowing down ingest for everyone else.
 */
final class SubscriberQueue {

    /**
     * Per subscription. At ~1,500 vehicle updates/s this is a few seconds of headroom. A pod
     * catching up on a Pub/Sub backlog emits several times faster; a subscription that cannot
     * drain that fast loses updates, counted as {@link #OVERFLOW_REASON}.
     */
    static final int CAPACITY = 4096;

    static final String OVERFLOW_REASON = "subscriber_queue_full";

    private SubscriberQueue() {
    }

    /**
     * @param live       the sink's shared live stream
     * @param initial    the snapshot a new subscription starts with; never dropped, as it is
     *                   emitted only as fast as the queue drains
     * @param onOverflow called on the publishing thread for each live update the full queue rejects
     */
    static <T> Flux<T> decouple(Flux<T> live, List<T> initial, Consumer<T> onOverflow) {
        return live
                .onBackpressureDrop(onOverflow)
                .startWith(initial)
                .publishOn(Schedulers.parallel(), CAPACITY);
    }
}
