package org.entur.vehicles.service.planned;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlannedDataServiceTest {

    private static String goaUrl() throws URISyntaxException {
        return PlannedDataServiceTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI().toString();
    }

    private static PrometheusMetricsService metrics() {
        return new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    @Test
    public void disabledServiceServesTheEmptyDatasetAndNeverLoads() {
        PlannedDataService service = PlannedDataService.disabled();

        service.initialLoad();
        service.scheduledReload();

        assertThat(service.current()).isSameAs(PlannedDataset.EMPTY);
        assertThat(service.findLine("GOA:Line:59")).isNull();
        assertThat(service.findOperator("GOA:Operator:GOA")).isNull();
        assertThat(service.hasServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R")).isFalse();
        assertThat(service.findPointsOnLink("GOA:ServiceJourney:B3008-AA_30082-R")).isNull();
        assertThat(service.findDatedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20")).isNull();
    }

    @Test
    public void initialLoadFromAFileUrlPopulatesTheDataset() throws Exception {
        PlannedDataService service = new PlannedDataService(true, goaUrl(), new PlannedDataLoader(), metrics(), 0);

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(service.findLine("GOA:Line:59").getPublicCode()).isEqualTo("L5");
        assertThat(service.findOperator("GOA:Operator:GOA").getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(service.hasServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R")).isTrue();
        assertThat(service.findPointsOnLink("GOA:ServiceJourney:B3008-AA_30082-R")).isNotNull();
        assertThat(service.findDatedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20").operatingDate())
                .isEqualTo("2024-01-20");
    }

    @Test
    public void initialLoadFailureThrows(@TempDir Path dir) {
        String missing = dir.resolve("missing.zip").toUri().toString();
        PlannedDataService service = new PlannedDataService(true, missing, new PlannedDataLoader(), metrics(), 0);

        assertThatThrownBy(service::initialLoad).isInstanceOf(IllegalStateException.class);
        assertThat(service.current()).isSameAs(PlannedDataset.EMPTY);
    }

    @Test
    public void datasetBelowAbsoluteFloorIsRejectedEvenOnFirstLoad() throws Exception {
        PlannedDataService service = new PlannedDataService(true, goaUrl(), new PlannedDataLoader(), metrics(), 1000);

        assertThatThrownBy(service::initialLoad).isInstanceOf(IllegalStateException.class);
        assertThat(service.current()).isSameAs(PlannedDataset.EMPTY);
    }

    @Test
    public void scheduledReloadFailureKeepsTheCurrentDataset(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("data.zip");
        Files.copy(Path.of(PlannedDataServiceTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI()), zip);
        PlannedDataService service = new PlannedDataService(true, zip.toUri().toString(), new PlannedDataLoader(), metrics(), 0);
        service.initialLoad();
        PlannedDataset loaded = service.current();

        Files.writeString(zip, "no longer a zip");
        service.scheduledReload(); // must not throw

        assertThat(service.current()).isSameAs(loaded);
    }

    @Test
    public void scheduledReloadSwapsInAFreshDataset(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("data.zip");
        Files.copy(Path.of(PlannedDataServiceTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI()), zip);
        PlannedDataService service = new PlannedDataService(true, zip.toUri().toString(), new PlannedDataLoader(), metrics(), 0);
        service.initialLoad();
        PlannedDataset first = service.current();

        service.scheduledReload();

        assertThat(service.current()).isNotSameAs(first);
        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
    }

    @Test
    public void aDatasetThatShrankByMoreThanHalfIsRejected() {
        PlannedDataset big = withServiceJourneys(10);
        PlannedDataset small = withServiceJourneys(4);
        PlannedDataset okay = withServiceJourneys(5);

        assertThat(PlannedDataService.isSuspiciouslySmall(small, big)).isTrue();
        assertThat(PlannedDataService.isSuspiciouslySmall(okay, big)).isFalse();
        assertThat(PlannedDataService.isSuspiciouslySmall(small, PlannedDataset.EMPTY))
                .withFailMessage("the first load has nothing to compare against")
                .isFalse();
    }

    private static PlannedDataset withServiceJourneys(int n) {
        PlannedDataset.Builder builder = new PlannedDataset.Builder().addJourneyPattern("JP", java.util.List.of());
        for (int i = 0; i < n; i++) {
            builder.addServiceJourney("SJ:" + i, "JP");
        }
        return builder.build();
    }

    @Test
    public void failedDownloadLeavesNoTempFile(@TempDir Path dir) {
        String missing = dir.resolve("missing.zip").toUri().toString();
        PlannedDataService service = new PlannedDataService(true, missing, new PlannedDataLoader(), metrics(), 0);

        int before = countPlannedNetexTempFiles();
        assertThatThrownBy(service::initialLoad).isInstanceOf(IllegalStateException.class);
        int after = countPlannedNetexTempFiles();

        assertThat(after).isEqualTo(before);
    }

    private static int countPlannedNetexTempFiles() {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, "planned-netex*")) {
            for (Path ignored : stream) {
                count++;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return count;
    }

    @Test
    public void plannedDataGaugesAreRegisteredOnce() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMetricsService metrics = new PrometheusMetricsService(registry);
        PlannedDataService service = new PlannedDataService(true, goaUrl(), new PlannedDataLoader(), metrics, 0);

        service.initialLoad();
        service.scheduledReload();

        assertThat(registry.find("app.vehicles.planned.data.load.duration").gauges()).hasSize(1);
        assertThat(registry.find("app.vehicles.planned.data.load.duration").gauges().iterator().next().value())
                .isGreaterThan(0);
        assertThat(registry.find("app.vehicles.planned.data.entities").tag("type", "serviceJourney").gauge().value())
                .isEqualTo(650);
    }
}
