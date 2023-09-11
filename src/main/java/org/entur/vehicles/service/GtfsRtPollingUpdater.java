package org.entur.vehicles.service;

import com.google.transit.realtime.GtfsRealtime;
import jakarta.annotation.PostConstruct;
import org.entur.vehicles.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class GtfsRtPollingUpdater {

    private final URL url;
    private final Duration interval;
    private final VehicleRepository vehicleRepository;

    public GtfsRtPollingUpdater(@Autowired VehicleRepository vehicleRepository,
                                @Value("${vehicle.updates.gtfsrt.url}") URL url,
                                @Value("${vehicle.updates.gtfsrt.interval:PT10S}") Duration interval) {
        this.vehicleRepository = vehicleRepository;
        this.url = url;
        this.interval = interval;
    }

    @PostConstruct
    private void init() {
        if (url != null && !url.toString().isEmpty()) {
            Executors.newSingleThreadScheduledExecutor()
                    .scheduleAtFixedRate(() -> updateData(url), 0, interval.getSeconds(), TimeUnit.SECONDS);
        }
    }

    private void updateData(URL url) {
        try {
            GtfsRealtime.FeedMessage feed = GtfsRealtime.FeedMessage.parseFrom(url.openStream());

            for (GtfsRealtime.FeedEntity entity : feed.getEntityList()) {
                if (entity.hasVehicle()) {
                    vehicleRepository.add(entity.getVehicle());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
