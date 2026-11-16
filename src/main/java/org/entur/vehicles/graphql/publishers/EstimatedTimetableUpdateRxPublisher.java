package org.entur.vehicles.graphql.publishers;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.TimetableRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class EstimatedTimetableUpdateRxPublisher {

    private final Sinks.Many<EstimatedTimetableUpdate> sink = Sinks.many().multicast().directBestEffort();
    private final PrometheusMetricsService metricsService;
    private TimetableRepository repository;

    public EstimatedTimetableUpdateRxPublisher(PrometheusMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void setRepository(TimetableRepository repository) {
        this.repository = repository;
    }

    /**
     * Called from several Pub/Sub executor threads. A Reactor sink rejects overlapping emits with
     * FAIL_NON_SERIALIZED, so emits must be serialised. The work under the lock is each
     * subscription's filter and buffer; blocking WebSocket sends happen on the session's own
     * thread (publishOn in Spring GraphQL), so a slow client cannot hold the lock.
     */
    public synchronized void publishUpdate(EstimatedTimetableUpdate vehicleUpdate) {
        Sinks.EmitResult result = sink.tryEmitNext(vehicleUpdate);
        // No subscribers means nobody is listening, not a lost update
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            metricsService.markDropped(PrometheusMetricsService.TYPE_TIMETABLE, result.name().toLowerCase(Locale.ROOT), 1);
        }
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
                .onBackpressureDrop(batch -> metricsService.markDropped(PrometheusMetricsService.TYPE_TIMETABLE, "backpressure", batch.size()));
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}