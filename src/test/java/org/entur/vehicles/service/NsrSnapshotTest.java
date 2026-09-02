package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.snapshot.SnapshotFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NsrSnapshotTest {

    @Test
    public void theParsedFixtureRoundTrips(@TempDir Path dir) throws Exception {
        NsrData parsed = new NsrNetexParser().parse(NsrNetexParserTest.zip(dir));
        Path raw = dir.resolve("nsr.bin");

        NsrSnapshot.write(parsed, raw, "etag-1");
        NsrData replayed;
        try (InputStream in = Files.newInputStream(raw)) {
            replayed = NsrSnapshot.read(in);
        }

        assertThat(replayed.childToParent()).isEqualTo(parsed.childToParent());
        assertThat(replayed.stopPoints().keySet()).isEqualTo(parsed.stopPoints().keySet());
        for (String id : parsed.stopPoints().keySet()) {
            StopPoint a = parsed.stopPoints().get(id);
            StopPoint b = replayed.stopPoints().get(id);
            assertThat(b.getName()).as(id).isEqualTo(a.getName());
            assertThat(b.getLocation().getLongitude()).as(id).isEqualTo(a.getLocation().getLongitude());
            assertThat(b.getLocation().getLatitude()).as(id).isEqualTo(a.getLocation().getLatitude());
        }
    }

    @Test
    public void aNullNameRoundTrips(@TempDir Path dir) throws Exception {
        NsrData data = new NsrData(Map.of("NSR:Quay:1", new StopPoint("NSR:Quay:1", null, new Location(1.0, 2.0))), Map.of());
        Path raw = dir.resolve("nsr.bin");

        NsrSnapshot.write(data, raw, "e");
        NsrData replayed;
        try (InputStream in = Files.newInputStream(raw)) {
            replayed = NsrSnapshot.read(in);
        }

        assertThat(replayed.stopPoints().get("NSR:Quay:1").getName()).isNull();
        assertThat(replayed.stopPoints().get("NSR:Quay:1").getLocation().getLongitude()).isEqualTo(1.0);
    }

    @Test
    public void headerAndTruncationAreGuarded(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("nsr.bin");
        NsrSnapshot.write(new NsrData(Map.of("A", new StopPoint("A", "a", new Location(0, 0))), Map.of("A", "B")), raw, "e");
        byte[] good = Files.readAllBytes(raw);

        byte[] wrongMagic = good.clone();
        wrongMagic[0] = 'X';
        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(wrongMagic)))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("magic");

        byte[] wrongVersion = good.clone();
        wrongVersion[7] = (byte) (wrongVersion[7] + 1);
        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(wrongVersion)))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("version");

        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(Arrays.copyOf(good, good.length - 3))))
                .isInstanceOf(IOException.class);

        byte[] wrongCount = good.clone();
        wrongCount[wrongCount.length - 1] = (byte) (wrongCount[wrongCount.length - 1] + 1);
        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(wrongCount)))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("count");
    }
}
