package org.entur.vehicles.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class NsrNetexParserTest {

    /** The fixture XML zipped the way the NSR export is: one XML entry. Shared with the service tests. */
    public static Path zip(Path dir) throws IOException {
        Path zip = dir.resolve("nsr.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
             InputStream xml = NsrNetexParserTest.class.getResourceAsStream("/nsr/fixture-site-frame.xml")) {
            out.putNextEntry(new ZipEntry("_stops.xml"));
            xml.transferTo(out);
            out.closeEntry();
        }
        return zip;
    }

    @Test
    public void parsesStopPlacesQuaysAndParents(@TempDir Path dir) throws Exception {
        NsrData data = new NsrNetexParser().parse(zip(dir));

        assertThat(data.stopPoints()).containsOnlyKeys(
                "NSR:StopPlace:1", "NSR:StopPlace:2", "NSR:StopPlace:3", "NSR:Quay:21", "NSR:Quay:22", "NSR:Quay:31");
        assertThat(data.stopPoints().get("NSR:StopPlace:2").getName()).isEqualTo("Oslo S");
        assertThat(data.stopPoints().get("NSR:StopPlace:2").getLocation().getLongitude()).isEqualTo(10.752245);
        assertThat(data.stopPoints().get("NSR:StopPlace:2").getLocation().getLatitude()).isEqualTo(59.910357);
        assertThat(data.stopPoints().get("NSR:Quay:21").getName()).isEqualTo("Spor 1");
        assertThat(data.stopPoints().get("NSR:Quay:22").getName())
                .withFailMessage("a quay without a name takes its stop place's name")
                .isEqualTo("Oslo S");
        assertThat(data.childToParent()).containsOnly(
                entry("NSR:Quay:21", "NSR:StopPlace:2"),
                entry("NSR:Quay:22", "NSR:StopPlace:2"),
                entry("NSR:Quay:31", "NSR:StopPlace:3"),
                entry("NSR:StopPlace:2", "NSR:StopPlace:1"));
    }
}
