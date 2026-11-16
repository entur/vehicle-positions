package org.entur.vehicles.graphql.publishers;

import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.repository.SituationRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class SituationUpdateRxPublisher {

    private final Sinks.Many<SituationUpdate> sink = Sinks.many().multicast().directBestEffort();
    private SituationRepository repository;

    public void setRepository(SituationRepository repository) {
        this.repository = repository;
    }

    /**
     * Called from several Pub/Sub executor threads. A Reactor sink rejects overlapping emits with
     * FAIL_NON_SERIALIZED, so emits must be serialised. The work under the lock is each
     * subscription's filter and buffer; blocking WebSocket sends happen on the session's own
     * thread (publishOn in Spring GraphQL), so a slow client cannot hold the lock.
     */
    public synchronized void publishUpdate(SituationUpdate situationUpdate) {
        sink.tryEmitNext(situationUpdate);
    }

    /** {@code template} is required - Subscription always supplies a filter. */
    public Flux<List<SituationUpdate>> getPublisher(SituationFilter template, String uuid) {
        List<SituationUpdate> initialdata = new ArrayList<>();
        if (repository != null) {
            initialdata.addAll(repository.getSituations(template));
        }

        return sink.asFlux()
                .startWith(initialdata)
                .filter(template::isMatch)
                .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
                .onBackpressureDrop();
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}
