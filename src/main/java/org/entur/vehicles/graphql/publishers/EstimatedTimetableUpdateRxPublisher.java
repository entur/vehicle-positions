package org.entur.vehicles.graphql.publishers;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.repository.TimetableRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class EstimatedTimetableUpdateRxPublisher {

    private final Sinks.Many<EstimatedTimetableUpdate> sink = Sinks.many().multicast().directBestEffort();
    private TimetableRepository repository;

    public void setRepository(TimetableRepository repository) {
        this.repository = repository;
    }

    public void publishUpdate(EstimatedTimetableUpdate vehicleUpdate) {
        sink.tryEmitNext(vehicleUpdate);
    }

    public Flux<List<EstimatedTimetableUpdate>> getPublisher(QueryFilter template, String uuid) {
        List<EstimatedTimetableUpdate> initialdata = new ArrayList<>();
        if (repository != null) {
            initialdata.addAll(repository.getTimetables(null));
        }

        return sink.asFlux()
                .startWith(initialdata)
                .filter(vehicleUpdate -> template == null || template.isMatch(vehicleUpdate))
                .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
                .onBackpressureDrop();
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}