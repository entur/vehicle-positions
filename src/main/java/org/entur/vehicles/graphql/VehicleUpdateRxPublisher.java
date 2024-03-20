package org.entur.vehicles.graphql;

import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class VehicleUpdateRxPublisher {

    private final Sinks.Many<VehicleUpdate> sink = Sinks.many().multicast().directBestEffort();

    public void publishUpdate(VehicleUpdate vehicleUpdate) {
        sink.tryEmitNext(vehicleUpdate);
    }

    public Flux<List<VehicleUpdate>> getPublisher(VehicleUpdateFilter template, String uuid) {

        return sink.asFlux()
                .filter(vehicleUpdate -> template == null || template.isMatch(vehicleUpdate))
                .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
                .onBackpressureDrop();
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}