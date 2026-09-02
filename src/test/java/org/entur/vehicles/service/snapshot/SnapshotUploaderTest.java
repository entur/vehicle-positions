package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class SnapshotUploaderTest {

    private SnapshotUploader uploader;

    @AfterEach
    public void shutdown() {
        if (uploader != null) {
            uploader.shutdown();
        }
    }

    private static String gunzip(InputStream in) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(in)) {
            return new String(gz.readAllBytes());
        }
    }

    @Test
    public void uploadsAGzippedCopyAndDeletesTheRawFile(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.resolve("store"));
        uploader = new SnapshotUploader(store, "pfx", null);
        SnapshotKey key = SnapshotKey.of("nsr", 1, "abc").orElseThrow();
        Path raw = Files.writeString(dir.resolve("raw.bin"), "raw bytes");

        SnapshotUploader.Outcome outcome = uploader.upload(key, raw, false).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        try (InputStream in = store.open("pfx/nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(gunzip(in)).isEqualTo("raw bytes");
        }
        assertThat(raw).doesNotExist();
    }

    @Test
    public void aSecondUploadReportsExists(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.resolve("store"));
        uploader = new SnapshotUploader(store, "", null);
        SnapshotKey key = SnapshotKey.of("nsr", 1, "abc").orElseThrow();
        uploader.upload(key, Files.writeString(dir.resolve("one"), "one"), false).join();

        SnapshotUploader.Outcome outcome = uploader.upload(key, Files.writeString(dir.resolve("two"), "two"), false).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.EXISTS);
        try (InputStream in = store.open("nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(gunzip(in)).isEqualTo("one");
        }
    }

    @Test
    public void replaceExistingOverwrites(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.resolve("store"));
        uploader = new SnapshotUploader(store, "", null);
        SnapshotKey key = SnapshotKey.of("nsr", 1, "abc").orElseThrow();
        uploader.upload(key, Files.writeString(dir.resolve("one"), "one"), false).join();

        SnapshotUploader.Outcome outcome = uploader.upload(key, Files.writeString(dir.resolve("two"), "two"), true).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        try (InputStream in = store.open("nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(gunzip(in)).isEqualTo("two");
        }
    }

    @Test
    public void aFailingStoreReportsFailedAndStillCleansUp(@TempDir Path dir) throws Exception {
        SnapshotStore broken = new SnapshotStore() {
            @Override
            public Optional<InputStream> open(String objectName) {
                return Optional.empty();
            }

            @Override
            public boolean putIfAbsent(String objectName, Path file) throws IOException {
                throw new IOException("bucket unavailable");
            }

            @Override
            public void put(String objectName, Path file) throws IOException {
                throw new IOException("bucket unavailable");
            }
        };
        uploader = new SnapshotUploader(broken, "", null);
        Path raw = Files.writeString(dir.resolve("raw.bin"), "raw");

        SnapshotUploader.Outcome outcome = uploader.upload(SnapshotKey.of("nsr", 1, "abc").orElseThrow(), raw, false).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.FAILED);
        assertThat(raw).doesNotExist();
        assertThat(raw.resolveSibling("raw.bin.gz")).doesNotExist();
        assertThat(Files.list(dir)).withFailMessage("no gzip temp file is left behind").isEmpty();
    }
}
