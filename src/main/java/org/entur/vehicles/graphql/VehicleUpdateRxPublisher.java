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

import java.util.ArrayList;
import java.util.List;

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

    }
    List<VehicleUpdate> vehicleUpdates = new ArrayList<>();

    public void publishUpdate(VehicleUpdate vehicleUpdate) {
        emitter.onNext(vehicleUpdate);

        publisher.publish();
    }

    public Flowable<VehicleUpdate> getPublisher() {
        return publisher;
    }

    public Flowable<VehicleUpdate> getPublisher(VehicleUpdateFilter template) {
        if (template != null) {
            return publisher.filter(vehicleUpdate -> template.isMatch(vehicleUpdate));
        }
        return publisher;
    }

}