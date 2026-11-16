package org.entur.vehicles.graphql.publishers;

import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class VehicleUpdateRxPublisher {

    private final Sinks.Many<VehicleUpdate> sink = Sinks.many().multicast().directBestEffort();
    private final PrometheusMetricsService metricsService;
    private VehicleRepository repository;

    public VehicleUpdateRxPublisher(PrometheusMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void setRepository(VehicleRepository repository) {
        this.repository = repository;
    }

    /**
     * Called from several Pub/Sub executor threads. A Reactor sink rejects overlapping emits with
     * FAIL_NON_SERIALIZED, so emits must be serialised. The work under the lock is each
     * subscription's filter and buffer; blocking WebSocket sends happen on the session's own
     * thread (publishOn in Spring GraphQL), so a slow client cannot hold the lock.
     */
    public synchronized void publishUpdate(VehicleUpdate vehicleUpdate) {
        Sinks.EmitResult result = sink.tryEmitNext(vehicleUpdate);
        // No subscribers means nobody is listening, not a lost update
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            metricsService.markVehicleDropped(result.name().toLowerCase(Locale.ROOT), 1);
        }
    }

    public Flux<List<VehicleUpdate>> getPublisher(QueryFilter template, String uuid) {
        List<VehicleUpdate> initialdata = new ArrayList<>();
        if (repository != null) {
            initialdata.addAll(repository.getVehicles(null));
        }

        return sink.asFlux()
                .startWith(initialdata)
                .filter(vehicleUpdate -> template == null || template.isMatch(vehicleUpdate))
                .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
                .onBackpressureDrop(batch -> metricsService.markVehicleDropped("backpressure", batch.size()));
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}