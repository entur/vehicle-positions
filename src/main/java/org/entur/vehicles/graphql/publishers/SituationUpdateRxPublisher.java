package org.entur.vehicles.graphql.publishers;

import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.SituationRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class SituationUpdateRxPublisher {

    private final Sinks.Many<SituationUpdate> sink = Sinks.many().multicast().directBestEffort();
    private final PrometheusMetricsService metricsService;
    private SituationRepository repository;

    public SituationUpdateRxPublisher(PrometheusMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void setRepository(SituationRepository repository) {
        this.repository = repository;
    }

    /**
     * Called from several Pub/Sub executor threads. A Reactor sink rejects overlapping emits with
     * FAIL_NON_SERIALIZED, so emits must be serialised. The only work under the lock is offering
     * the update to each subscription's queue ({@link SubscriberQueue}); filtering, buffering and
     * WebSocket sends all run on other threads, so neither the number of clients nor a slow one
     * can slow down ingest.
     */
    public synchronized void publishUpdate(SituationUpdate situationUpdate) {
        Sinks.EmitResult result = sink.tryEmitNext(situationUpdate);
        // No subscribers means nobody is listening, not a lost update
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            metricsService.markDropped(PrometheusMetricsService.TYPE_SITUATION, result.name().toLowerCase(Locale.ROOT), 1);
        }
    }

    /** {@code template} is required - Subscription always supplies a filter. */
    public Flux<List<SituationUpdate>> getPublisher(SituationFilter template, String uuid) {
        List<SituationUpdate> initialdata = new ArrayList<>();
        if (repository != null) {
            initialdata.addAll(repository.getSituations(template));
        }

        return SubscriberQueue.decouple(sink.asFlux(), initialdata,
                        dropped -> metricsService.markDropped(PrometheusMetricsService.TYPE_SITUATION, SubscriberQueue.OVERFLOW_REASON, 1))
                .filter(template::isMatch)
                .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
                .onBackpressureDrop(batch -> metricsService.markDropped(PrometheusMetricsService.TYPE_SITUATION, "backpressure", batch.size()));
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}
