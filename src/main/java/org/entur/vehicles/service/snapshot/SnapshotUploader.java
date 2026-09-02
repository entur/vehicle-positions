package org.entur.vehicles.service.snapshot;

import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses and stores a raw snapshot file off the caller's thread. The caller hands over
 * ownership of the raw file: it is deleted here whatever happens, along with the gzip sibling
 * file created alongside it. Nothing here ever propagates to the caller - a failed upload is a
 * log line and a counter, and the next pod that misses will simply try again.
 */
public final class SnapshotUploader {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotUploader.class);

    public enum Outcome { UPLOADED, EXISTS, FAILED }

    private final SnapshotStore store;
    private final String prefix;
    private final PrometheusMetricsService metrics;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snapshot-upload");
        t.setDaemon(true);
        return t;
    });

    public SnapshotUploader(SnapshotStore store, String prefix, PrometheusMetricsService metrics) {
        this.store = store;
        this.prefix = prefix == null ? "" : prefix;
        this.metrics = metrics;
    }

    public CompletableFuture<Outcome> upload(SnapshotKey key, Path rawFile, boolean replaceExisting) {
        return CompletableFuture.supplyAsync(() -> doUpload(key, rawFile, replaceExisting), executor);
    }

    private Outcome doUpload(SnapshotKey key, Path rawFile, boolean replaceExisting) {
        String objectName = key.objectName(prefix);
        long start = System.currentTimeMillis();
        Path gz = null;
        Outcome outcome;
        try {
            gz = rawFile.resolveSibling(rawFile.getFileName() + ".gz");
            gzip(rawFile, gz);
            long size = Files.size(gz);
            if (replaceExisting) {
                store.put(objectName, gz);
                outcome = Outcome.UPLOADED;
            } else {
                outcome = store.putIfAbsent(objectName, gz) ? Outcome.UPLOADED : Outcome.EXISTS;
            }
            LOG.info("Snapshot {} {}: {} bytes in {} ms", objectName, outcome == Outcome.UPLOADED ? "uploaded" : "already existed",
                    size, System.currentTimeMillis() - start);
        } catch (IOException | RuntimeException e) {
            outcome = Outcome.FAILED;
            LOG.warn("Snapshot {} upload failed after {} ms", objectName, System.currentTimeMillis() - start, e);
        } finally {
            deleteQuietly(rawFile);
            deleteQuietly(gz);
        }
        if (metrics != null) {
            metrics.markSnapshotUpload(key.dataset(), outcome.name().toLowerCase());
        }
        return outcome;
    }

    private static void gzip(Path from, Path to) throws IOException {
        try (InputStream in = Files.newInputStream(from);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(to), 1 << 16) {
                 {
                     def.setLevel(Deflater.BEST_SPEED);
                 }
             }) {
            in.transferTo(out);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not delete {}", file, e);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
