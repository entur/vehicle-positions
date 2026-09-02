package org.entur.vehicles.service;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.snapshot.EtagHttpServer;
import org.entur.vehicles.service.snapshot.ExportDownloader;
import org.entur.vehicles.service.snapshot.SnapshotCache;
import org.entur.vehicles.service.snapshot.SnapshotUploader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class NSRServiceSnapshotTest {

    @TempDir
    Path dir;
    private Path storeDir;
    private EtagHttpServer server;
    private SnapshotCache cache;

    @BeforeEach
    public void setUp() throws Exception {
        server = new EtagHttpServer(NsrNetexParserTest.zip(dir), "\"nsr-1\"");
        storeDir = dir.resolve("store");
        cache = SnapshotCache.fromUri(storeDir.toUri().toString(), metrics());
    }

    @AfterEach
    public void tearDown() {
        cache.close();
        server.close();
    }

    private static PrometheusMetricsService metrics() {
        return new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    private NSRService service(SnapshotCache snapshots) {
        return new NSRService(true, server.url(), new ExportDownloader(Duration.ofSeconds(5)), snapshots, metrics());
    }

    private Path expectedObject() {
        return storeDir.resolve("nsr/v" + NsrSnapshot.FORMAT_VERSION + "/nsr-1.bin.gz");
    }

    private static void assertFixtureInstalled(NSRService service) {
        assertThat(service.getStop("NSR:Quay:22").getName()).isEqualTo("Oslo S");
        assertThat(service.getStop("NSR:StopPlace:3").getLocation().getLatitude()).isEqualTo(60.39);
        assertThat(service.ancestorsOf("NSR:Quay:21")).isEqualTo(Set.of("NSR:StopPlace:2", "NSR:StopPlace:1"));
        assertThat(service.getStop("NSR:Quay:999").getName()).withFailMessage("unknown refs still resolve to a bare ref").isNull();
    }

    @Test
    public void firstWarmUpMissesParsesAndUploads() {
        NSRService service = service(cache);

        service.warmUpCache();

        assertFixtureInstalled(service);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(expectedObject()).exists();
        assertThat(server.getCount()).isEqualTo(1);
    }

    @Test
    public void secondWarmUpHitsAndDoesNotDownload() {
        service(cache).warmUpCache();
        cache.lastUpload().join();

        NSRService second = service(cache);
        second.warmUpCache();

        assertThat(server.getCount()).isEqualTo(1);
        assertFixtureInstalled(second);
    }

    @Test
    public void aCorruptSnapshotFallsBackAndIsReplaced() throws Exception {
        Files.createDirectories(expectedObject().getParent());
        Files.writeString(expectedObject(), "garbage");
        NSRService service = service(cache);

        service.warmUpCache();

        assertFixtureInstalled(service);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(Files.size(expectedObject())).isGreaterThan("garbage".length());
    }

    @Test
    public void aDisabledCacheIsTodaysBehaviour() {
        NSRService service = service(SnapshotCache.disabled());

        service.warmUpCache();

        assertFixtureInstalled(service);
        assertThat(server.headCount()).isZero();
        assertThat(storeDir).doesNotExist();
    }

    @Test
    public void disabledLookupNeverFetches() {
        NSRService service = new NSRService(false, server.url(), new ExportDownloader(Duration.ofSeconds(5)), cache, metrics());

        service.warmUpCache();

        assertThat(server.headCount()).isZero();
        assertThat(server.getCount()).isZero();
        assertThat(service.getStop("NSR:Quay:21").getName()).isNull();
        assertThat(service.ancestorsOf("NSR:Quay:21")).isEmpty();
    }
}
