package org.entur.vehicles.service.planned;

import jakarta.annotation.PostConstruct;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.snapshot.ExportDownloader;
import org.entur.vehicles.service.snapshot.SnapshotCache;
import org.entur.vehicles.service.snapshot.SnapshotKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the current {@link PlannedDataset}. Loads it once at startup - blocking the Spring
 * context, and therefore readiness, until it is in place - and swaps in a fresh one from a
 * nightly reload. A failed startup load is fatal; a failed nightly reload keeps the
 * previous dataset.
 * <p>
 * Each load tries the snapshot named by the export's ETag before parsing; a parse that
 * completes without skipping anything is written to a new snapshot, from the builder, and
 * uploaded after the dataset is live. See
 * {@code docs/superpowers/specs/2026-09-02-planned-data-snapshot-design.md} and
 * {@code docs/superpowers/specs/2026-09-03-snapshot-v2-encoding-design.md}.
 * <p>
 * The {@code find*} methods are the lookup surface the enrichment services use. They return
 * null on a miss and count it, so a producer referencing ids the export lacks is visible.
 */
@Service
public class PlannedDataService {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataService.class);

    private final boolean enabled;
    private final String url;
    private final PlannedDataLoader loader;
    private final PrometheusMetricsService metrics;
    private final int minServiceJourneys;
    private final ExportDownloader downloader;
    private final SnapshotCache snapshots;
    private final AtomicReference<PlannedDataset> current = new AtomicReference<>(PlannedDataset.EMPTY);

    @Autowired
    public PlannedDataService(@Value("${vehicle.planned.data.enabled:false}") boolean enabled,
                              @Value("${vehicle.planned.data.url:https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip}") String url,
                              PlannedDataLoader loader,
                              PrometheusMetricsService metrics,
                              @Value("${vehicle.planned.data.min.service.journeys:50000}") int minServiceJourneys,
                              ExportDownloader downloader,
                              SnapshotCache snapshots) {
        this.enabled = enabled;
        this.url = url;
        this.loader = loader;
        this.metrics = metrics;
        this.minServiceJourneys = minServiceJourneys;
        this.downloader = downloader;
        this.snapshots = snapshots;
    }

    /** A service that never loads and serves {@link PlannedDataset#EMPTY} - for tests. */
    public static PlannedDataService disabled() {
        return new PlannedDataService(false, null, null, null, 0, null, SnapshotCache.disabled());
    }

    @PostConstruct
    void initialLoad() {
        if (!enabled) {
            LOG.info("Planned data disabled - lookups return bare refs");
            return;
        }
        try {
            load();
        } catch (PlannedDataLoadException e) {
            throw new IllegalStateException("Initial planned data load failed", e);
        }
    }

    @Scheduled(cron = "${vehicle.planned.data.reload.cron:0 0 8 * * *}", zone = "Europe/Oslo")
    void scheduledReload() {
        if (!enabled) {
            return;
        }
        try {
            load();
        } catch (PlannedDataLoadException e) {
            LOG.error("Planned data reload failed - keeping the current dataset", e);
        }
    }

    private void load() throws PlannedDataLoadException {
        long start = System.currentTimeMillis();
        Path zip = null;
        Path raw = null;
        try {
            PlannedDataset.Builder builder = new PlannedDataset.Builder();
            String source = null;
            SnapshotKey key = null;
            boolean unreadable = false;

            // 1. Snapshot first: the export's ETag names the object that holds exactly its records.
            if (snapshots.enabled()) {
                Optional<SnapshotKey> candidate = downloader.head(url)
                        .flatMap(etag -> SnapshotKey.of(PlannedDataSnapshot.DATASET, PlannedDataSnapshot.FORMAT_VERSION, etag));
                if (candidate.isEmpty()) {
                    LOG.info("No ETag for {} - loading the export without a snapshot", url);
                }
                Optional<InputStream> in = candidate.flatMap(snapshots::open);
                if (in.isPresent()) {
                    boolean replayed = false;
                    try (InputStream stream = in.get()) {
                        PlannedDataSnapshot.replay(stream, builder);
                        replayed = true;
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("Snapshot {} could not be replayed - falling back to the export", candidate.get(), e);
                        builder = new PlannedDataset.Builder();
                        unreadable = true;
                        replayed = false;
                    }
                    // Set outside the try: a close() that throws lands in the catch, and a discarded
                    // builder must never be paired with a source that skips the export path.
                    if (replayed) {
                        key = candidate.get();
                        source = "snapshot";
                    }
                }
            }

            // 2. Full parse into the builder; the snapshot, if any, is written from the
            //    builder's completed state afterwards - see PlannedDataSnapshot.write.
            if (source == null) {
                zip = Files.createTempFile("planned-netex", ".zip");
                long downloadStart = System.currentTimeMillis();
                Optional<String> etag;
                try {
                    etag = downloader.download(url, zip);
                } catch (IOException e) {
                    throw new PlannedDataLoadException("Could not download " + url, e);
                }
                LOG.info("Download of {} took {} ms", url, System.currentTimeMillis() - downloadStart);
                Optional<SnapshotKey> uploadKey = snapshots.enabled()
                        ? etag.flatMap(e -> SnapshotKey.of(PlannedDataSnapshot.DATASET, PlannedDataSnapshot.FORMAT_VERSION, e))
                        : Optional.empty();

                int skipped = loader.load(zip, builder);

                if (uploadKey.isPresent() && skipped == 0) {
                    // The bucket is a cache, never a dependency: failing to prepare or write the
                    // snapshot file costs the snapshot, never the load. IOException covers a
                    // disk problem; RuntimeException covers a bug in the writer itself (e.g.
                    // IdCodec.Writer.writeId's IllegalStateException on an un-interned prefix) -
                    // either way the parsed dataset must still install.
                    try {
                        raw = createSnapshotFile("planned-snapshot", ".bin");
                        snapshotWriter.write(builder, raw, uploadKey.get().etag());
                        key = uploadKey.get();
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("Could not write the planned-data snapshot - loading without one", e);
                        deleteQuietly(raw);
                        raw = null;
                        key = null;
                    }
                } else if (uploadKey.isPresent()) {
                    // A partial parse must not become this export's snapshot for the whole fleet.
                    LOG.warn("{} NeTEx entries were skipped - not snapshotting a partial parse", skipped);
                }
                source = "export";
            }

            // 3. Build, check, install.
            PlannedDataset fresh = builder.build();
            if (fresh.serviceJourneyCount() < minServiceJourneys) {
                throw new PlannedDataLoadException("Fresh dataset has " + fresh.serviceJourneyCount()
                        + " service journeys, below the configured minimum of " + minServiceJourneys
                        + " - rejecting");
            }
            PlannedDataset previous = current.get();
            if (isSuspiciouslySmall(fresh, previous)) {
                throw new PlannedDataLoadException("Fresh dataset has " + fresh.serviceJourneyCount()
                        + " service journeys, current has " + previous.serviceJourneyCount()
                        + " - rejecting as a truncated export");
            }
            current.set(fresh);
            long duration = System.currentTimeMillis() - start;
            if (metrics != null) {
                metrics.markPlannedDataLoaded(duration, fresh.stats());
                metrics.markSnapshotSource(PlannedDataSnapshot.DATASET, "snapshot".equals(source));
            }
            LOG.info("Planned data loaded in {} ms from {} (etag={}): {}", duration, source,
                    key == null ? "none" : key.etag(), fresh.stats());

            // 4. Only now, off the readiness path, does the raw file go to the bucket.
            if (raw != null) {
                snapshots.upload(key, raw, unreadable);
                raw = null; // the uploader owns it from here
            }
        } catch (IOException e) {
            if (metrics != null) {
                metrics.markPlannedDataLoadFailure();
            }
            throw new PlannedDataLoadException("Planned data load failed", e);
        } catch (PlannedDataLoadException e) {
            if (metrics != null) {
                metrics.markPlannedDataLoadFailure();
            }
            throw e;
        } finally {
            deleteQuietly(zip);
            deleteQuietly(raw);
        }
    }

    /**
     * Test seam: the directory the raw snapshot file is created in. Null - the default - means
     * the JVM's temp directory. Only tests set it, to make that creation fail.
     */
    private Path snapshotTempDir;

    void snapshotTempDir(Path dir) {
        this.snapshotTempDir = dir;
    }

    /** The write step, as a seam so a test can replace it - e.g. to make it throw an unchecked exception the way a bug in the real writer would, without needing a genuinely un-interned id. */
    interface SnapshotWriter {
        void write(PlannedDataset.Builder builder, Path file, String etag) throws IOException;
    }

    private SnapshotWriter snapshotWriter = PlannedDataSnapshot::write;

    void snapshotWriter(SnapshotWriter writer) {
        this.snapshotWriter = writer;
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

    /**
     * A fresh dataset with fewer than half the current one's service journeys is far more
     * likely a truncated export than a real change in Norway's timetable. Never applies to
     * the first load, which has nothing to compare against.
     */
    static boolean isSuspiciouslySmall(PlannedDataset fresh, PlannedDataset previous) {
        if (previous.serviceJourneyCount() == 0) {
            return false;
        }
        return fresh.serviceJourneyCount() * 2 < previous.serviceJourneyCount();
    }

    public PlannedDataset current() {
        return current.get();
    }

    public Line findLine(String lineRef) {
        Line line = current.get().line(lineRef);
        if (line == null) {
            miss("line");
        }
        return line;
    }

    public Operator findOperator(String operatorRef) {
        Operator operator = current.get().operator(operatorRef);
        if (operator == null) {
            miss("operator");
        }
        return operator;
    }

    public boolean hasServiceJourney(String serviceJourneyId) {
        boolean known = current.get().hasServiceJourney(serviceJourneyId);
        if (!known) {
            miss("serviceJourney");
        }
        return known;
    }

    /** Geometry for a service journey, or null if the journey or its geometry is unknown. Not miss-counted - use {@link #hasServiceJourney} for that. */
    public PointsOnLink findPointsOnLink(String serviceJourneyId) {
        PlannedDataset dataset = current.get();
        return dataset.pointsOnLink(dataset.journeyPatternOf(serviceJourneyId));
    }

    public DatedJourneyRef findDatedServiceJourney(String datedServiceJourneyId) {
        DatedJourneyRef ref = current.get().datedServiceJourney(datedServiceJourneyId);
        if (ref == null) {
            miss("datedServiceJourney");
        }
        return ref;
    }

    private void miss(String type) {
        if (metrics != null) {
            metrics.markPlannedDataLookupMiss(type);
        }
    }
}
