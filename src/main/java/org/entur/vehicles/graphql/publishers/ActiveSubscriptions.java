package org.entur.vehicles.graphql.publishers;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts a publisher's open GraphQL subscriptions, from subscribe until cancel, completion or
 * error.
 *
 * <p>The sink's own subscriber count misses new subscriptions: a subscription reaches the sink
 * only once its initial snapshot has been sent ({@link SubscriberQueue}), which takes seconds
 * for a large snapshot and a slow client - so the sink undercounts most right after a reconnect
 * storm.
 */
final class ActiveSubscriptions {

    private final AtomicInteger count = new AtomicInteger();

    <T> Flux<T> track(Flux<T> subscription) {
        return subscription
                .doOnSubscribe(s -> count.incrementAndGet())
                .doFinally(signal -> count.decrementAndGet());
    }

    int count() {
        return count.get();
    }
}
