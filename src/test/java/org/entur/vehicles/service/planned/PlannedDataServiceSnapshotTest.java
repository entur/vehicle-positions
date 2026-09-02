package org.entur.vehicles.service.planned;

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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class PlannedDataServiceSnapshotTest {

    @TempDir
    Path dir;
    private Path storeDir;
    private EtagHttpServer server;
    private SnapshotCache cache;

    @BeforeEach
    public void setUp() throws Exception {
        Path zip = dir.resolve("goa.zip");
        Files.copy(Path.of(getClass().getResource("/netex/rb_goa-aggregated-netex.zip").toURI()), zip);
        server = new EtagHttpServer(zip, "\"etag-1\"");
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

    private PlannedDataService service(SnapshotCache snapshots) {
        return new PlannedDataService(true, server.url(), new PlannedDataLoader(), metrics(), 0,
                new ExportDownloader(Duration.ofSeconds(5)), snapshots);
    }

    private Path expectedObject() {
        return storeDir.resolve("planned-data/v" + PlannedDataSnapshot.FORMAT_VERSION + "/etag-1.bin.gz");
    }

    @Test
    public void firstLoadMissesParsesAndUploads() {
        PlannedDataService service = service(cache);

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(expectedObject()).exists();
        assertThat(server.getCount()).isEqualTo(1);
    }

    @Test
    public void secondLoadHitsAndDoesNotDownload() {
        PlannedDataService first = service(cache);
        first.initialLoad();
        cache.lastUpload().join();
        PlannedDataset parsed = first.current();

        PlannedDataService second = service(cache);
        second.initialLoad();

        assertThat(server.getCount()).withFailMessage("a hit never downloads the export").isEqualTo(1);
        assertThat(second.current().stats()).isEqualTo(parsed.stats());
        assertThat(second.current().line("GOA:Line:59").getPublicCode()).isEqualTo("L5");
    }

    @Test
    public void aCorruptSnapshotFallsBackAndIsReplaced() throws Exception {
        Files.createDirectories(expectedObject().getParent());
        Files.writeString(expectedObject(), "garbage");
        PlannedDataService service = service(cache);

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(Files.size(expectedObject())).isGreaterThan("garbage".length());
    }

    @Test
    public void aChangedEtagMisses() {
        PlannedDataService first = service(cache);
        first.initialLoad();
        cache.lastUpload().join();
        server.setEtag("\"etag-2\"");

        PlannedDataService second = service(cache);
        second.initialLoad();
        cache.lastUpload().join();

        assertThat(server.getCount()).isEqualTo(2);
        assertThat(storeDir.resolve("planned-data/v" + PlannedDataSnapshot.FORMAT_VERSION + "/etag-2.bin.gz")).exists();
    }

    @Test
    public void noEtagMeansNoSnapshotButStillALoad() {
        server.setEtag(null);
        PlannedDataService service = service(cache);

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(cache.lastUpload().join()).isNull();
        assertThat(storeDir).doesNotExist();
    }

    @Test
    public void aDisabledCacheIsTodaysBehaviour() {
        PlannedDataService service = service(SnapshotCache.disabled());

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(server.headCount()).isZero();
        assertThat(storeDir).doesNotExist();
    }

    /** The bucket is a cache, never a dependency: a snapshot file we cannot even create is not fatal. */
    @Test
    public void aSnapshotFileThatCannotBeWrittenStillLoadsTheDataset() {
        PlannedDataService service = service(cache);
        service.snapshotTempDir(dir.resolve("missing"));

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(cache.lastUpload().join()).isNull();
        assertThat(server.getCount()).isEqualTo(1);
    }

    /** A dataset missing whatever the skipped entries held must not become this export's snapshot. */
    @Test
    public void aPartialParseIsNotSnapshotted() throws Exception {
        Path partial = dir.resolve("partial.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(partial))) {
            put(out, "_TST_shared_data.xml", resource("/netex/fragment-shared-data.xml"));
            put(out, "TST_TST-Line-broken.xml", resource("/netex/fragment-malformed.xml"));
            put(out, "TST_TST-Line-204.xml", resource("/netex/fragment-line-file.xml"));
        }
        server.setFile(partial);
        PlannedDataService service = service(cache);

        service.initialLoad();

        assertThat(service.current().line("TST:Line:204")).isNotNull();
        assertThat(cache.lastUpload().join()).isNull();
        assertThat(storeDir).doesNotExist();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = PlannedDataServiceSnapshotTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void put(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
