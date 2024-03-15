package org.entur.vehicles.graphql;

import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class VehicleUpdateRxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleUpdateRxPublisher.class);

    private FluxSink<VehicleUpdate> sink;
    private ConnectableFlux<VehicleUpdate> publisher;

    public VehicleUpdateRxPublisher(@Autowired VehicleRepository vehicleRepository) {

        Flux<VehicleUpdate> publisherEmitter = Flux.create(fluxEmitter -> {
            VehicleUpdateRxPublisher.this.sink = fluxEmitter;
            vehicleRepository.addUpdateListener(VehicleUpdateRxPublisher.this);
        });

        publisher = publisherEmitter.publish();
        publisher.connect();
        LOG.info("Created VehicleUpdateRxPublisher");
    }

    public void publishUpdate(VehicleUpdate vehicleUpdate) {
        sink.next(vehicleUpdate);
    }

    public Flux<List<VehicleUpdate>> getPublisher(VehicleUpdateFilter template, String uuid) {

        return publisher
            .filter(vehicleUpdate -> template == null || template.isMatch(vehicleUpdate))
            .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
            .onBackpressureDrop()
        ;
    }

}