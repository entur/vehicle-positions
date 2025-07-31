package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Service
public class NSRService {

    private static final Logger LOG = LoggerFactory.getLogger(NSRService.class);

    private boolean enabled;

    public NSRService(@Value("${vehicle.nsr.lookup.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    private final LoadingCache<String, StopPoint> stopPointCache = CacheBuilder.newBuilder()
            .build(new CacheLoader<>() {
                @Override
                public StopPoint load(String stopRef) {
                    return enabled ? lookup(stopRef) : new StopPoint(stopRef);
                }
            });

    @PostConstruct
    private void warmUpCache() {
        if (enabled) {
            //TODO: Populate cache from Netex-export
            stopPointCache.put(
                    "NSR:Quay:571",
                    new StopPoint(
                            "NSR:Quay:571",
                            "Oslo S",
                            new Location(10.754999, 59.910400)
                    ));
        }
    }


    public StopPoint getStop(String stopRef){
        try {
            return stopPointCache.get(stopRef);
        } catch (ExecutionException e) {
            return new StopPoint(stopRef); // Fallback to a StopPoint with just the ref if lookup fails
        }
    }

    private StopPoint lookup(String stopRef) {
        // No need to attempt lookup if id does not match pattern
        if (stopRef.contains(":Quay:")) {
            //TODO: Implement lookup logic for quays/stops
        }
        return new StopPoint(stopRef);
    }
}
