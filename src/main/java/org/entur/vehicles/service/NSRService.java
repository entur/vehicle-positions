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
import org.rutebanken.netex.model.SiteRefStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * How far the climb from a quay to its ancestors may go. NeTEx data is external and outside
     * this service's control, so a malformed or absurdly deep ParentSiteRef chain must not be
     * able to stall startup.
     */
    private static final int MAX_ANCESTOR_DEPTH = 10;

    /**
     * Every ancestor above a ref: for a quay, its stop place and any multimodal parent above
     * that. Empty when NSR lookup is disabled, which makes every consumer fall back to literal
     * stop matching - exactly today's behaviour.
     */
    private final Map<String, Set<String>> ancestorsByRef = new ConcurrentHashMap<>();

    @PostConstruct
    private void warmUpCache() {
        if (enabled) {
            long start = System.currentTimeMillis(); // For performance measurement
            NetexParser netexParser = new NetexParser();
            try {

                NetexEntitiesIndex index = netexParser.parse(readUrl(url));

                // The parser already publishes quay -> stop place; this map is not retained
                // after parsing, so its contents are copied. Stop place -> multimodal parent is
                // added below, from the loop that already visits every stop place.
                Map<String, String> childToParent = new HashMap<>(index.getStopPlaceIdByQuayIdIndex());

                index.getStopPlaceIndex().getLatestVersions().forEach( stopPlace -> {
                    String stopPlaceId = stopPlace.getId();
                    SiteRefStructure parentSiteRef = stopPlace.getParentSiteRef();
                    if (parentSiteRef != null && parentSiteRef.getRef() != null) {
                        childToParent.put(stopPlaceId, parentSiteRef.getRef());
                    }
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

                ancestorsByRef.putAll(flattenAncestors(childToParent));
                LOG.info("NSRService resolved ancestors for {} stop refs.", ancestorsByRef.size());
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

    /**
     * Every ancestor above this ref, nearest first. Returns the stored set rather than a copy,
     * so this allocates nothing - {@code SituationTriggeredRepublisher} calls it for every call
     * of every stored journey on every situation change.
     */
    public Set<String> ancestorsOf(String stopRef) {
        if (stopRef == null) {
            return Set.of();
        }
        return ancestorsByRef.getOrDefault(stopRef, Set.of());
    }

    /**
     * This ref plus every ancestor above it. Allocates, so prefer {@link #ancestorsOf} on a hot
     * path. A null ref yields an empty set; an unknown ref yields just itself, so a caller never
     * has to special-case missing NeTEx data.
     */
    public Set<String> expandWithAncestors(String stopRef) {
        if (stopRef == null) {
            return Set.of();
        }
        Set<String> ancestors = ancestorsOf(stopRef);
        if (ancestors.isEmpty()) {
            return Set.of(stopRef);
        }
        Set<String> expanded = new LinkedHashSet<>();
        expanded.add(stopRef);
        expanded.addAll(ancestors);
        return Collections.unmodifiableSet(expanded);
    }

    /**
     * Collapses a child-to-parent map into a child-to-all-ancestors map, once, at startup.
     * <p>
     * Flattening here rather than walking the chain per lookup keeps lookup O(1), which matters
     * because {@code SituationTriggeredRepublisher}'s scan sits on a hot path.
     * <p>
     * The climb stops on revisiting a ref or on reaching {@link #MAX_ANCESTOR_DEPTH}, keeping
     * whatever it found so far. Both guards exist because this data is external: a circular
     * ParentSiteRef must degrade to partial resolution, never to a hung startup.
     */
    static Map<String, Set<String>> flattenAncestors(Map<String, String> childToParent) {
        Map<String, Set<String>> flattened = new HashMap<>();
        for (String child : childToParent.keySet()) {
            Set<String> ancestors = new LinkedHashSet<>();
            Set<String> visited = new HashSet<>();
            visited.add(child);

            String current = child;
            while (ancestors.size() < MAX_ANCESTOR_DEPTH) {
                String parent = childToParent.get(current);
                if (parent == null) {
                    break;
                }
                if (!visited.add(parent)) {
                    LOG.warn("Circular ParentSiteRef chain: climbing from {} revisited {} - "
                            + "stopping there with the ancestors found so far.", child, parent);
                    break;
                }
                ancestors.add(parent);
                current = parent;
            }

            if (ancestors.size() == MAX_ANCESTOR_DEPTH && childToParent.get(current) != null) {
                LOG.warn("ParentSiteRef chain from {} is deeper than the cap of {} - "
                        + "resolution is truncated there.", child, MAX_ANCESTOR_DEPTH);
            }

            if (!ancestors.isEmpty()) {
                flattened.put(child, Set.copyOf(ancestors));
            }
        }
        return flattened;
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
