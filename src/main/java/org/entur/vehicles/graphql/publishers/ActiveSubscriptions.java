package org.entur.vehicles.graphql.publishers;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts a publisher's open GraphQL subscriptions, from subscribe until cancel, completion or
 * error.
 *
 * <p>Counted here rather than from the sink's subscriber count, so the count does not depend on
 * when {@link SubscriberQueue} subscribes to the sink. It used to do so only once the initial
 * snapshot had been sent, and the sink's count then read low right after a reconnect storm.
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
