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

    public void publishUpdate(SituationUpdate situationUpdate) {
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
