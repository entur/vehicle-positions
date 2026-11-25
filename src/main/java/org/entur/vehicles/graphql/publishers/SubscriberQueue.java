package org.entur.vehicles.graphql.publishers;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
     * Requested from the per-subscription queue at a time by the thread that runs the filter and
     * buffer. A subscription that has stopped draining holds up to {@code CAPACITY + HANDOFF}
     * live updates before overflowing.
     */
    static final int HANDOFF = 256;

    /**
     * Subscribes to {@code live} first, holding its updates in this subscription's queue, then
     * reads the snapshot and emits it, then the queued and further live updates. An update
     * stored and published while the snapshot is read can then arrive twice - in the snapshot
     * and live - but never not at all; the later copy is the newer one or the same.
     *
     * <p>The live stream used to be subscribed only once the snapshot had been sent, which for a
     * large snapshot and a slow client took seconds, and every update published meanwhile was
     * lost to that subscription.
     *
     * @param live       the sink's shared live stream; its emits must be serialised, as the
     *                   sink's are
     * @param snapshot   read once per subscription, after it has subscribed to {@code live};
     *                   never dropped, as it is emitted only as fast as the subscription drains
     * @param onOverflow called on the publishing thread for each live update the full queue rejects
     */
    static <T> Flux<T> decouple(Flux<T> live, Supplier<List<T>> snapshot, Consumer<T> onOverflow) {
        return Flux.<T>defer(() -> {
                    Sinks.Many<T> queue = Sinks.many().unicast().onBackpressureBuffer(Queues.<T>get(CAPACITY).get());
                    Disposable liveSubscription = live.subscribe(
                            update -> {
                                // FAIL_CANCELLED only between cancel and leaving live: not lost
                                if (queue.tryEmitNext(update) == Sinks.EmitResult.FAIL_OVERFLOW) {
                                    onOverflow.accept(update);
                                }
                            },
                            queue::tryEmitError,
                            queue::tryEmitComplete);
                    List<T> initial;
                    try {
                        initial = snapshot.get();
                    } catch (RuntimeException e) {
                        liveSubscription.dispose();
                        throw e;
                    }
                    return Flux.fromIterable(initial)
                            .concatWith(queue.asFlux())
                            .doFinally(signal -> liveSubscription.dispose());
                })
                .publishOn(Schedulers.parallel(), HANDOFF);
    }
}
