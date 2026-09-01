package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GOA export (three lines, one shared-data file, 1.3 MB) is the smallest real
 * aggregated NeTEx zip and is checked in as a fixture. Its counts were measured with grep
 * over the extracted XML when the fixture was added.
 */
public class PlannedDataLoaderTest {

    private static Path goaZip() throws URISyntaxException {
        return Path.of(PlannedDataLoaderTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI());
    }

    @Test
    public void loadsTheGoaExport() throws Exception {
        PlannedDataset dataset = new PlannedDataLoader().load(goaZip());

        PlannedDataset.Stats stats = dataset.stats();
        assertThat(stats.operators()).isEqualTo(1);
        assertThat(stats.lines()).isEqualTo(3);
        assertThat(stats.journeyPatterns()).isEqualTo(183);
        assertThat(stats.serviceJourneys()).isEqualTo(650);
        assertThat(stats.datedServiceJourneys()).isEqualTo(18599);
        assertThat(stats.serviceLinks()).isEqualTo(315);

        assertThat(dataset.operator("GOA:Operator:GOA").getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(dataset.line("GOA:Line:59").getLineName()).isEqualTo("Jærbanen");
        assertThat(dataset.line("GOA:Line:59").getPublicCode()).isEqualTo("L5");
        assertThat(dataset.journeyPatternOf("GOA:ServiceJourney:B3008-AA_30082-R")).isEqualTo("GOA:JourneyPattern:L59-153");
        assertThat(dataset.datedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20"))
                .isEqualTo(new DatedJourneyRef("GOA:ServiceJourney:B3008-AA_30082-R", "2024-01-20"));

        var points = dataset.pointsOnLink("GOA:JourneyPattern:L59-153");
        assertThat(points).isNotNull();
        assertThat(points.getLength()).isGreaterThan(10);
        assertThat(points.getPoints()).isNotEmpty();
    }

    @Test
    public void aBrokenEntryIsSkippedAndTheRestIsKept(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("mixed.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "_TST_shared_data.xml", resource("/netex/fragment-shared-data.xml"));
            put(out, "TST_TST-Line-broken.xml", resource("/netex/fragment-malformed.xml"));
            put(out, "TST_TST-Line-204.xml", resource("/netex/fragment-line-file.xml"));
            put(out, "README.txt", "not xml");
        }

        PlannedDataset dataset = new PlannedDataLoader().load(zip);

        assertThat(dataset.line("TST:Line:204")).isNotNull();
        assertThat(dataset.line("TST:Line:before"))
                .withFailMessage("elements parsed before the malformed point are kept")
                .isNotNull();
        assertThat(dataset.operator("TST:Operator:1")).isNotNull();
    }

    @Test
    public void zeroLineFilesIsAFailedLoad(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("shared-only.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "_TST_shared_data.xml", resource("/netex/fragment-shared-data.xml"));
        }

        assertThatThrownBy(() -> new PlannedDataLoader().load(zip))
                .isInstanceOf(PlannedDataLoadException.class)
                .hasMessageContaining("line file");
    }

    @Test
    public void unreadableZipIsAFailedLoad(@TempDir Path dir) throws Exception {
        Path notAZip = dir.resolve("garbage.zip");
        Files.writeString(notAZip, "this is not a zip");

        assertThatThrownBy(() -> new PlannedDataLoader().load(notAZip))
                .isInstanceOf(PlannedDataLoadException.class);
    }

    private static String resource(String name) throws IOException {
        try (var in = PlannedDataLoaderTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void put(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    @Test
    public void goaCatalogueCoversEveryJourneyOnItsThreeLines() throws Exception {
        PlannedDataset dataset = new PlannedDataLoader().load(goaZip());

        assertThat(dataset.codespaces()).extracting(c -> c.getCodespaceId()).containsExactly("GOA");
        assertThat(dataset.lines("GOA")).hasSize(3);
        assertThat(dataset.serviceJourneyIds(null, "GOA")).hasSize(650);
        int perLine = 0;
        for (var line : dataset.lines(null)) {
            perLine += dataset.serviceJourneyIds(line.getLineRef(), null).size();
        }
        assertThat(perLine).isEqualTo(650);
        assertThat(dataset.stats().unresolvedLineRefs()).isZero();
        assertThat(dataset.serviceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20").getDate())
                .isEqualTo("2024-01-20");
    }
}
