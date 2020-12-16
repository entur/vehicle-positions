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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class VehicleUpdateRxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleUpdateRxPublisher.class);

    private final Flowable<VehicleUpdate> publisher;
    private ObservableEmitter<VehicleUpdate> emitter;

    public VehicleUpdateRxPublisher(@Autowired VehicleRepository vehicleRepository) {
        Observable<VehicleUpdate> vehicleUpdateObservable = Observable.create(emitter -> {
            VehicleUpdateRxPublisher.this.emitter = emitter;
            vehicleRepository.addUpdateListener(VehicleUpdateRxPublisher.this);
        });

        ConnectableObservable<VehicleUpdate> connectableObservable = vehicleUpdateObservable.share().publish();
        connectableObservable.connect();

        publisher = connectableObservable.toFlowable(BackpressureStrategy.BUFFER);
        LOG.info("Created VehicleUpdateRxPublisher");
    }

    public void publishUpdate(VehicleUpdate vehicleUpdate) {
        emitter.onNext(vehicleUpdate);
    }

    public Flowable<List<VehicleUpdate>> getPublisher(VehicleUpdateFilter template, String uuid) {

        return publisher.filter(vehicleUpdate -> template == null || template.isMatch(vehicleUpdate))
                .buffer(template.getBufferTimeMillis(), TimeUnit.MILLISECONDS, template.getBufferSize())
                ;
    }

}