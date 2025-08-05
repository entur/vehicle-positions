package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.entur.netex.NetexParser;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.rutebanken.netex.model.LocationStructure;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.Quays_RelStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutionException;

@Service
public class NSRService {

    private static final Logger LOG = LoggerFactory.getLogger(NSRService.class);
    private final String url;

    private final boolean enabled;

    public NSRService(
            @Value("${vehicle.nsr.lookup.enabled:false}") boolean enabled,
            @Value("${vehicle.nsr.lookup.url:}") String url
    ) {
        this.enabled = enabled;
        this.url = url;
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
            long start = System.currentTimeMillis(); // For performance measurement
            NetexParser netexParser = new NetexParser();
            try {

                NetexEntitiesIndex index = netexParser.parse(readUrl(url));
                index.getStopPlaceIndex().getLatestVersions().forEach( stopPlace -> {
                    String stopPlaceId = stopPlace.getId();
                    String stopPlaceName = stopPlace.getName().getValue();
                    LocationStructure stopPlaceLocation = stopPlace.getCentroid().getLocation();
                    stopPointCache.put(
                            stopPlaceId,
                            new StopPoint(
                                    stopPlaceId,
                                    stopPlaceName,
                                    new Location(
                                            stopPlaceLocation.getLongitude().doubleValue(),
                                            stopPlaceLocation.getLatitude().doubleValue()
                                    )
                            )
                    );
                    Quays_RelStructure quays = stopPlace.getQuays();
                    if (quays != null) {

                        quays.getQuayRefOrQuay().forEach(jaxbQuay -> {

                                if (jaxbQuay.getValue() instanceof Quay quay) {
                                    String id = quay.getId();
                                    String name;
                                    if (quay.getName() == null || quay.getName().getValue() == null) {
                                        name = stopPlaceName;
                                    } else {
                                        name = quay.getName().getValue();
                                    }
                                    LocationStructure quayLocation = quay.getCentroid().getLocation();
                                    stopPointCache.put(
                                            id,
                                            new StopPoint(
                                                    id,
                                                    name,
                                                    new Location(
                                                            quayLocation.getLongitude().doubleValue(),
                                                            quayLocation.getLatitude().doubleValue()
                                                    )
                                            )
                                    );
                                }
                        });
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // For performance measurement
            LOG.info("NSRService cache warm-up took: {} ms", (System.currentTimeMillis() - start));
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

    private static String readUrl(String url) {


        long start = System.currentTimeMillis();
        try {
            File tmpFile = File.createTempFile("netex", ".zip");
            FileUtils.copyURLToFile(
                    new URL(url),
                    tmpFile, 5000,
                    5000);

            return tmpFile.getAbsolutePath();
        } catch (IOException e) {
            LOG.error("Could not download file", e);
        } finally {
            long done = System.currentTimeMillis();
            LOG.info("Download of {} took: {} ms", url, (done - start));
        }
        return null;
    }
}
