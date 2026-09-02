package org.entur.vehicles.service.snapshot;

import jakarta.annotation.PreDestroy;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/**
 * What the two dataset services talk to. Resolves object names, gunzips on open, uploads in
 * the background, and turns every failure on the way into a log line and an empty result.
 * The disabled instance is what runs when {@code vehicle.snapshot.uri} is empty.
 */
public final class SnapshotCache {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotCache.class);

    private final SnapshotStore store;
    private final String prefix;
    private final SnapshotUploader uploader;
    private volatile CompletableFuture<SnapshotUploader.Outcome> lastUpload = CompletableFuture.completedFuture(null);

    private SnapshotCache(SnapshotStore store, String prefix, SnapshotUploader uploader) {
        this.store = store;
        this.prefix = prefix;
        this.uploader = uploader;
    }

    public static SnapshotCache disabled() {
        return new SnapshotCache(null, "", null);
    }

    public static SnapshotCache fromUri(String uri, PrometheusMetricsService metrics) {
        if (uri == null || uri.isBlank()) {
            LOG.info("Snapshots disabled - vehicle.snapshot.uri is empty");
            return disabled();
        }
        SnapshotStore store;
        String prefix;
        if (uri.startsWith("gs://")) {
            List<String> parts = parseGs(uri);
            store = new GcsSnapshotStore(parts.get(0));
            prefix = parts.get(1);
        } else if (uri.startsWith("file:")) {
            store = new FileSnapshotStore(Path.of(URI.create(uri)));
            prefix = "";
        } else {
            throw new IllegalArgumentException("vehicle.snapshot.uri must be gs://bucket/prefix, file:///dir or empty, got " + uri);
        }
        LOG.info("Snapshots enabled at {}", uri);
        return new SnapshotCache(store, prefix, new SnapshotUploader(store, prefix, metrics));
    }

    /** {@code gs://bucket/some/prefix} into [bucket, prefix]; the prefix may be empty. */
    static List<String> parseGs(String uri) {
        String rest = uri.substring("gs://".length());
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String prefix = slash < 0 ? "" : rest.substring(slash + 1);
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("vehicle.snapshot.uri has no bucket: " + uri);
        }
        return List.of(bucket, prefix);
    }

    public boolean enabled() {
        return store != null;
    }

    /** A gunzipped stream of the snapshot, or empty on disabled, not found, or any store error. */
    public Optional<InputStream> open(SnapshotKey key) {
        if (!enabled()) {
            return Optional.empty();
        }
        String objectName = key.objectName(prefix);
        try {
            Optional<InputStream> raw = store.open(objectName);
            if (raw.isEmpty()) {
                LOG.info("Snapshot miss: {}", objectName);
                return Optional.empty();
            }
            LOG.info("Snapshot hit: {}", objectName);
            return Optional.of(new LazyGzipInputStream(raw.get()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Snapshot lookup of {} failed - treating as a miss", objectName, e);
            return Optional.empty();
        }
    }

    /** Hands the raw file to the uploader (or deletes it when disabled). Never blocks, never throws. */
    public void upload(SnapshotKey key, Path rawFile, boolean replaceExisting) {
        if (!enabled()) {
            try {
                Files.deleteIfExists(rawFile);
            } catch (IOException e) {
                LOG.warn("Could not delete {}", rawFile, e);
            }
            return;
        }
        lastUpload = uploader.upload(key, rawFile, replaceExisting);
    }

    /** The most recent upload's future, for tests that need to wait for it. */
    public CompletableFuture<SnapshotUploader.Outcome> lastUpload() {
        return lastUpload;
    }

    @PreDestroy
    public void close() {
        if (uploader != null) {
            uploader.shutdown();
        }
    }

    /** Defers the gzip header read to the first read() so a corrupt object surfaces to the reader, not the lookup. */
    private static final class LazyGzipInputStream extends InputStream {
        private final InputStream raw;
        private InputStream gz;

        LazyGzipInputStream(InputStream raw) {
            this.raw = raw;
        }

        private InputStream delegate() throws IOException {
            if (gz == null) {
                gz = new GZIPInputStream(raw, 1 << 16);
            }
            return gz;
        }

        @Override
        public int read() throws IOException {
            return delegate().read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate().read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (gz != null) {
                gz.close();
            } else {
                raw.close();
            }
        }
    }
}
