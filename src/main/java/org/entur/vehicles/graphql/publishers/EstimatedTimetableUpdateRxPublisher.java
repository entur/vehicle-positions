package org.entur.vehicles.graphql.publishers;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.TimetableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class EstimatedTimetableUpdateRxPublisher {

    private final ShardedSink<EstimatedTimetableUpdate> sink;
    private final PrometheusMetricsService metricsService;
    private TimetableRepository repository;

    public EstimatedTimetableUpdateRxPublisher(PrometheusMetricsService metricsService) {
        this(metricsService, ShardedSink.DEFAULT_SHARDS);
    }

    @Autowired
    public EstimatedTimetableUpdateRxPublisher(PrometheusMetricsService metricsService,
            @Value("${entur.vehicle-positions.publisher.shards:" + ShardedSink.DEFAULT_SHARDS + "}") int shards) {
        this.metricsService = metricsService;
        this.sink = new ShardedSink<>(shards);
        metricsService.registerActiveSubscriptions(PrometheusMetricsService.TYPE_TIMETABLE, this::currentSubscribers);
    }

    public void setRepository(TimetableRepository repository) {
        this.repository = repository;
    }

    /**
     * Called from several Pub/Sub executor threads at once; see {@link ShardedSink}. The only
     * work here is offering the update to each subscription's queue ({@link SubscriberQueue});
     * filtering, buffering and WebSocket sends all run on other threads.
     */
    public void publishUpdate(EstimatedTimetableUpdate vehicleUpdate) {
        sink.emit(vehicleUpdate, result ->
                metricsService.markDropped(PrometheusMetricsService.TYPE_TIMETABLE, result.name().toLowerCase(Locale.ROOT), 1));
    }

    public Flux<List<EstimatedTimetableUpdate>> getPublisher(QueryFilter template, String uuid) {
        List<EstimatedTimetableUpdate> initialdata = new ArrayList<>();
        if (repository != null) {
            initialdata.addAll(repository.getTimetables(null));
        }

        return SubscriberQueue.decouple(sink.asFlux(), initialdata,
                        dropped -> metricsService.markDropped(PrometheusMetricsService.TYPE_TIMETABLE, SubscriberQueue.OVERFLOW_REASON, 1))
                .filter(vehicleUpdate -> template == null || template.isMatch(vehicleUpdate))
                .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
                .onBackpressureDrop(batch -> metricsService.markDropped(PrometheusMetricsService.TYPE_TIMETABLE, "backpressure", batch.size()));
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}