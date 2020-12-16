package org.entur.vehicles.graphql;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.observables.ConnectableObservable;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.VehicleUpdateFilter;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VehicleUpdateRxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleUpdateRxPublisher.class);

    private final Flowable<VehicleUpdate> publisher;
    private ObservableEmitter<VehicleUpdate> emitter;

    private String uuid;

    private int counter;

    public VehicleUpdateRxPublisher(@Autowired VehicleRepository vehicleRepository) {
        MDC.put(Constants.TRACING_HEADER_NAME, uuid);
        Observable<VehicleUpdate> vehicleUpdateObservable = Observable.create(emitter -> {
            VehicleUpdateRxPublisher.this.emitter = emitter;
            vehicleRepository.addUpdateListener(VehicleUpdateRxPublisher.this);
        });

        ConnectableObservable<VehicleUpdate> connectableObservable = vehicleUpdateObservable.share().publish();
        connectableObservable.connect();

        publisher = connectableObservable.toFlowable(BackpressureStrategy.BUFFER);
        LOG.info("Created subscription");
        MDC.remove(Constants.TRACING_HEADER_NAME);
    }

    public void publishUpdate(VehicleUpdate vehicleUpdate) {
        MDC.put(Constants.TRACING_HEADER_NAME, uuid);
        emitter.onNext(vehicleUpdate);
        if (counter++ % 1000 == 0) {
            LOG.info("Published {} updates to subscription");
        }
        publisher.publish();
        MDC.remove(Constants.TRACING_HEADER_NAME);
    }

    public Flowable<VehicleUpdate> getPublisher(VehicleUpdateFilter template, String uuid) {
        this.uuid = uuid;
        if (template != null) {
            return publisher.filter(vehicleUpdate -> template.isMatch(vehicleUpdate));
        }
        return publisher;
    }

}