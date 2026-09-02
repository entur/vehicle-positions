package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.snapshot.ExportDownloader;
import org.entur.vehicles.service.snapshot.SnapshotCache;
import org.entur.vehicles.service.snapshot.SnapshotKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Service
public class NSRService {

    private static final Logger LOG = LoggerFactory.getLogger(NSRService.class);
    private final String url;
    private final boolean enabled;
    private final ExportDownloader downloader;
    private final SnapshotCache snapshots;
    private final PrometheusMetricsService metrics;

    @Autowired
    public NSRService(
            @Value("${vehicle.nsr.lookup.enabled:false}") boolean enabled,
            @Value("${vehicle.nsr.lookup.url:}") String url,
            ExportDownloader downloader,
            SnapshotCache snapshots,
            PrometheusMetricsService metrics
    ) {
        this.enabled = enabled;
        this.url = url;
        this.downloader = downloader;
        this.snapshots = snapshots;
        this.metrics = metrics;
    }

    /**
     * Test seam: constructs with no downloader, metrics or snapshot cache, delegating to the
     * {@link #NSRService(boolean, String, Map)} seam with an empty map. Kept public because
     * GraphQL test fixtures outside this package build a disabled {@code NSRService} this way
     * to satisfy {@code Query}'s constructor; not part of the runtime (Spring-wired) surface.
     */
    public NSRService(boolean enabled, String url) {
        this(enabled, url, Map.of());
    }

    /**
     * Test seam: installs a child-to-parent map directly, via the same {@link #install} the
     * real warm-up uses, without downloading or parsing a NeTEx file. Package-private - not
     * part of the public surface. The {@code enabled}/{@code url} it forwards only ever govern
     * {@link #warmUpCache} (never run here) and {@link #getStop} lookup.
     */
    NSRService(boolean enabled, String url, Map<String, String> childToParent) {
        this(enabled, url, null, SnapshotCache.disabled(), null);
        install(new NsrData(Map.of(), childToParent));
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
    void warmUpCache() {
        if (!enabled) {
            return;
        }
        long start = System.currentTimeMillis();
        Path zip = null;
        Path raw = null;
        try {
            NsrData data = null;
            String source = null;
            SnapshotKey key = null;
            boolean unreadable = false;

            if (snapshots.enabled()) {
                Optional<SnapshotKey> candidate = downloader.head(url)
                        .flatMap(etag -> SnapshotKey.of(NsrSnapshot.DATASET, NsrSnapshot.FORMAT_VERSION, etag));
                if (candidate.isEmpty()) {
                    LOG.info("No ETag for {} - loading the NSR export without a snapshot", url);
                }
                Optional<InputStream> in = candidate.flatMap(snapshots::open);
                if (in.isPresent()) {
                    boolean replayed = false;
                    try (InputStream stream = in.get()) {
                        data = NsrSnapshot.read(stream);
                        replayed = true;
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("Snapshot {} could not be read - falling back to the NSR export", candidate.get(), e);
                        data = null;
                        unreadable = true;
                        replayed = false;
                    }
                    // Set outside the try: a close() that throws lands in the catch, and the export
                    // path must then still run.
                    if (replayed) {
                        key = candidate.get();
                        source = "snapshot";
                    }
                }
            }

            if (data == null) {
                zip = Files.createTempFile("nsr-netex", ".zip");
                long downloadStart = System.currentTimeMillis();
                Optional<String> etag = downloader.download(url, zip);
                LOG.info("Download of {} took {} ms", url, System.currentTimeMillis() - downloadStart);
                data = new NsrNetexParser().parse(zip);
                source = "export";
                Optional<SnapshotKey> uploadKey = snapshots.enabled()
                        ? etag.flatMap(e -> SnapshotKey.of(NsrSnapshot.DATASET, NsrSnapshot.FORMAT_VERSION, e))
                        : Optional.empty();
                if (uploadKey.isPresent()) {
                    // The bucket is a cache, never a dependency: failing to write the snapshot file
                    // costs the snapshot, never the warm-up.
                    try {
                        raw = createSnapshotFile("nsr-snapshot", ".bin");
                        NsrSnapshot.write(data, raw, uploadKey.get().etag());
                        key = uploadKey.get();
                    } catch (IOException e) {
                        LOG.warn("Could not prepare snapshot file - loading without a snapshot", e);
                        deleteQuietly(raw);
                        raw = null;
                        key = null;
                    }
                }
            }

            install(data);
            long duration = System.currentTimeMillis() - start;
            if (metrics != null) {
                metrics.markNsrLoaded(duration);
                metrics.markSnapshotSource(NsrSnapshot.DATASET, "snapshot".equals(source));
            }
            LOG.info("NSRService resolved ancestors for {} stop refs.", ancestorsByRef.size());
            LOG.info("NSRService cache warm-up took: {} ms from {} (etag={})", duration, source,
                    key == null ? "none" : key.etag());

            if (raw != null) {
                snapshots.upload(key, raw, unreadable);
                raw = null;
            }
        } catch (IOException e) {
            throw new IllegalStateException("NSR warm-up failed", e);
        } finally {
            deleteQuietly(zip);
            deleteQuietly(raw);
        }
    }

    /** The one place the caches are filled, on both the snapshot and the parse path. */
    void install(NsrData data) {
        stopPointCache.putAll(data.stopPoints());
        ancestorsByRef.putAll(flattenAncestors(data.childToParent()));
    }

    /**
     * Test seam: the directory the raw snapshot file is created in. Null - the default - means
     * the JVM's temp directory. Only tests set it, to make that creation fail.
     */
    private Path snapshotTempDir;

    void snapshotTempDir(Path dir) {
        this.snapshotTempDir = dir;
    }

    private Path createSnapshotFile(String prefix, String suffix) throws IOException {
        return snapshotTempDir == null
                ? Files.createTempFile(prefix, suffix)
                : Files.createTempFile(snapshotTempDir, prefix, suffix);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not delete temp file {}", file, e);
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
     * Every ancestor above this ref, in no particular order - the set is unordered. Returns
     * the stored set rather than a copy, so this allocates nothing - {@code
     * SituationTriggeredRepublisher} calls it for every call of every stored journey on every
     * situation change.
     */
    public Set<String> ancestorsOf(String stopRef) {
        if (stopRef == null) {
            return Set.of();
        }
        return ancestorsByRef.getOrDefault(stopRef, Set.of());
    }

    /**
     * This ref plus every ancestor above it, in no particular order - the set is unordered.
     * Allocates, so prefer {@link #ancestorsOf} on a hot path. A null ref yields an empty set;
     * an unknown ref yields just itself, so a caller never has to special-case missing NeTEx
     * data.
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
}
