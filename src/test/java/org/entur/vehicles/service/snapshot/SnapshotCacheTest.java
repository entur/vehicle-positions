package org.entur.vehicles.service.snapshot;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SnapshotCacheTest {

    private static PrometheusMetricsService metrics() {
        return new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    @Test
    public void emptyUriIsDisabled(@TempDir Path dir) throws Exception {
        SnapshotCache cache = SnapshotCache.fromUri("", null);
        Path raw = Files.writeString(dir.resolve("raw"), "x");

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.open(SnapshotKey.of("nsr", 1, "abc").orElseThrow())).isEmpty();
        cache.upload(SnapshotKey.of("nsr", 1, "abc").orElseThrow(), raw, false);
        assertThat(raw).withFailMessage("a disabled cache still takes ownership of the raw file").doesNotExist();
        cache.close();
    }

    @Test
    public void unknownSchemesAreRejected() {
        assertThatThrownBy(() -> SnapshotCache.fromUri("s3://bucket/x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SnapshotCache.fromUri("gs://", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void fileUriRoundTripsThroughGzip(@TempDir Path dir) throws Exception {
        SnapshotCache cache = SnapshotCache.fromUri(dir.resolve("store").toUri().toString(), metrics());
        SnapshotKey key = SnapshotKey.of("planned-data", 2, "\"e1\"").orElseThrow();
        Path raw = Files.writeString(dir.resolve("raw"), "records");

        assertThat(cache.enabled()).isTrue();
        assertThat(cache.open(key)).isEmpty();

        cache.upload(key, raw, false);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(dir.resolve("store/planned-data/v2/e1.bin.gz")).exists();

        try (InputStream in = cache.open(key).orElseThrow()) {
            assertThat(new String(in.readAllBytes())).withFailMessage("open() gunzips").isEqualTo("records");
        }
        cache.close();
    }

    @Test
    public void openSwallowsStoreErrorsAndReportsEmpty(@TempDir Path dir) throws Exception {
        Path notADirectory = Files.writeString(dir.resolve("file"), "x");
        SnapshotCache cache = SnapshotCache.fromUri(notADirectory.toUri().toString(), null);

        assertThat(cache.open(SnapshotKey.of("nsr", 1, "abc").orElseThrow())).isEmpty();
        cache.close();
    }

    @Test
    public void openReturnsTheStreamEvenIfItIsNotValidGzip(@TempDir Path dir) throws Exception {
        // Corruption is the reader's problem: the cache only checks presence. A bad gzip
        // header surfaces as an IOException on the first read, which the service turns into
        // a fallback and a replacement upload.
        Path store = dir.resolve("store");
        Path object = store.resolve("nsr/v1/abc.bin.gz");
        Files.createDirectories(object.getParent());
        Files.writeString(object, "not gzip");
        SnapshotCache cache = SnapshotCache.fromUri(store.toUri().toString(), null);

        assertThat(cache.open(SnapshotKey.of("nsr", 1, "abc").orElseThrow())).isPresent();
        cache.close();
    }

    @Test
    public void gsUriParsesBucketAndPrefix() {
        assertThat(SnapshotCache.parseGs("gs://my-bucket/some/prefix")).containsExactly("my-bucket", "some/prefix");
        assertThat(SnapshotCache.parseGs("gs://my-bucket")).containsExactly("my-bucket", "");
        assertThat(SnapshotCache.parseGs("gs://my-bucket/")).containsExactly("my-bucket", "");
    }
}
