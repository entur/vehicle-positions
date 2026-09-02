package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.SnapshotFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlannedDataSnapshotTest {

    // ---- v2 writer ----

    @Test
    public void v2WriteStartsWithMagicAndVersionTwo(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        builder.addOperator("RUT:Operator:1", "One");
        builder.addOperator("RUT:Operator:2", "Two");
        builder.addLine("RUT:Line:1", "Line One", "1");
        builder.addLine("RUT:Line:2", "Line Two", "2");
        builder.addServiceLink("RUT:ServiceLink:known", new int[]{1, 2, 3});
        builder.addJourneyPattern("RUT:JourneyPattern:1", List.of("RUT:ServiceLink:known", "RUT:ServiceLink:dangling"));
        builder.addServiceJourney("RUT:ServiceJourney:withDanglingPattern", "RUT:JourneyPattern:missing", "RUT:Line:1");
        builder.addOperatingDay("RUT:OperatingDay:1", "2026-09-02");
        builder.addDatedServiceJourney("RUT:DatedServiceJourney:withDanglingDay", "RUT:ServiceJourney:withDanglingPattern", "RUT:OperatingDay:missing");

        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "etag-1");

        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).startsWith('V', 'P', 'P', '2');
        int version = ((bytes[4] & 0xFF) << 24) | ((bytes[5] & 0xFF) << 16) | ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertThat(version).isEqualTo(2);
        assertThat(PlannedDataSnapshot.FORMAT_VERSION).isEqualTo(2);
    }

    @Test
    public void aDanglingReferenceProducesALongerRecordThanAResolvableOne(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder resolvable = new PlannedDataset.Builder();
        resolvable.addJourneyPattern("RUT:JourneyPattern:1", List.of());
        resolvable.addServiceJourney("RUT:ServiceJourney:1", "RUT:JourneyPattern:1", null);
        Path resolvableFile = dir.resolve("resolvable.bin");
        PlannedDataSnapshot.write(resolvable, resolvableFile, "e");

        PlannedDataset.Builder dangling = new PlannedDataset.Builder();
        dangling.addServiceJourney("RUT:ServiceJourney:1", "RUT:JourneyPattern:missing", null);
        Path danglingFile = dir.resolve("dangling.bin");
        PlannedDataSnapshot.write(dangling, danglingFile, "e");

        assertThat(Files.size(danglingFile)).isGreaterThan(Files.size(resolvableFile));
    }

    @Test
    public void v2HeaderCarriesDuplicateCount(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        builder.addOperator("O:1", "One");
        builder.addOperator("O:1", "One again"); // duplicate id

        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "e");

        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            in.readInt(); // version
            in.readUTF(); // etag
            in.readLong(); // createdAt
            assertThat(in.readInt()).isEqualTo(1); // duplicateIds
        }
    }

    @Test
    public void v2HeaderCarriesZeroDuplicatesForACleanBuilder(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "e");

        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            in.readInt(); // version
            in.readUTF(); // etag
            in.readLong(); // createdAt
            assertThat(in.readInt()).isEqualTo(0); // duplicateIds
        }
    }

    @Test
    public void v2ExactBytesOfASmallFixture(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        builder.addOperator("O:1", "One");
        builder.addServiceLink("L:1", new int[]{10, 20, 5});

        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "e");
        byte[] bytes = Files.readAllBytes(file);

        // Header up to and including createdAt is variable-length (writeUTF) or time-based;
        // skip past it deterministically: magic(4) + version(4) + writeUTF("e")(2+1) + createdAt(8).
        int offset = 4 + 4 + (2 + 1) + 8;

        byte[] expectedTail = {
                // duplicateIds = 0
                0x00, 0x00, 0x00, 0x00,
                // prefix table: count=2, "O:", "L:"
                0x02,
                0x03, 'O', ':',
                0x03, 'L', ':',
                // section 1: operators, count=1
                0x01,
                // record: prefixIdx=0, kind=DIGITS(4), local="1" -> varint 1, name "One"
                0x00, 0x04, 0x01, 0x04, 'O', 'n', 'e',
                // section 2: lines, count=0
                0x00,
                // section 3: operatingDays, count=0
                0x00,
                // section 4: serviceLinks, count=1
                0x01,
                // record: prefixIdx=1, kind=DIGITS(4), local="1" -> varint 1,
                // intCount=3, then zigzag(10-0)=20, zigzag(20-0)=40, zigzag(5-10)=zigzag(-5)=9
                0x01, 0x04, 0x01, 0x03, 20, 40, 9,
                // section 5: journeyPatterns, count=0
                0x00,
                // section 6: serviceJourneys, count=0
                0x00,
                // section 7: datedServiceJourneys, count=0
                0x00,
                // trailer: 0xFF, total record count = 2
                (byte) 0xFF, 0x02,
        };

        byte[] actualTail = java.util.Arrays.copyOfRange(bytes, offset, bytes.length);
        assertThat(actualTail).containsExactly(expectedTail);
    }

    // ---- v2 reader ----

    @Test
    public void aReplayedV2SnapshotBuildsTheSameDatasetAsTheBuilder(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder original = new PlannedDataset.Builder();
        original.addOperator("RUT:Operator:1", "One");
        original.addOperator("RUT:Operator:1", "One again"); // duplicate id -> duplicateIds = 1

        original.addLine("RUT:Line:1", "Line One", "1");
        original.addLine("RUT:Line:2", null, null); // null name + null public code

        original.addServiceLink("RUT:ServiceLink:empty", new int[0]); // empty geometry
        original.addServiceLink("RUT:ServiceLink:odd", new int[]{10, 20, 5}); // odd-length geometry

        original.addJourneyPattern("RUT:JourneyPattern:1", List.of("RUT:ServiceLink:odd", "RUT:ServiceLink:dangling")); // dangling link ref
        original.addJourneyPattern("RUT:JourneyPattern:2", List.of()); // empty link list

        original.addServiceJourney("RUT:ServiceJourney:1", "RUT:JourneyPattern:missing", "RUT:Line:dangling"); // dangling pattern + dangling line
        original.addServiceJourney("RUT:ServiceJourney:2", null, "RUT:Line:1"); // null pattern -> "" placeholder
        original.addServiceJourney("RUT:ServiceJourney:3", "RUT:JourneyPattern:1", null); // resolvable pattern, null line

        original.addOperatingDay("RUT:OperatingDay:1", "2026-09-02");
        original.addOperatingDay("RUT:OperatingDay:2", null); // null calendar date

        original.addDatedServiceJourney("RUT:DatedServiceJourney:1", "RUT:ServiceJourney:missing", "RUT:OperatingDay:1"); // dangling journey ref
        original.addDatedServiceJourney("RUT:DatedServiceJourney:2", "RUT:ServiceJourney:2", "RUT:OperatingDay:missing"); // dangling operating-day ref
        original.addDatedServiceJourney("RUT:DatedServiceJourney:3", "RUT:ServiceJourney:3", "RUT:OperatingDay:2"); // resolvable both

        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(original, file, "etag-1");

        PlannedDataset.Builder replayed = new PlannedDataset.Builder();
        try (InputStream in = Files.newInputStream(file)) {
            PlannedDataSnapshot.replay(in, replayed);
        }

        PlannedDataset fromSnapshot = replayed.build();
        PlannedDataset fromOriginal = original.build();

        assertThat(fromSnapshot.stats()).isEqualTo(fromOriginal.stats());
        assertThat(fromSnapshot.stats().duplicateIds()).isEqualTo(1);

        assertThat(fromSnapshot.operator("RUT:Operator:1").getName())
                .isEqualTo(fromOriginal.operator("RUT:Operator:1").getName());
        assertThat(fromSnapshot.line("RUT:Line:1").getLineName())
                .isEqualTo(fromOriginal.line("RUT:Line:1").getLineName());
        assertThat(fromSnapshot.line("RUT:Line:1").getPublicCode())
                .isEqualTo(fromOriginal.line("RUT:Line:1").getPublicCode());
        assertThat(fromSnapshot.line("RUT:Line:2").getLineName()).isNull();
        assertThat(fromSnapshot.line("RUT:Line:2").getPublicCode()).isNull();
        assertThat(fromSnapshot.journeyPatternOf("RUT:ServiceJourney:1"))
                .isEqualTo(fromOriginal.journeyPatternOf("RUT:ServiceJourney:1"))
                .isEqualTo("RUT:JourneyPattern:missing"); // dangling ref kept
        assertThat(fromSnapshot.journeyPatternOf("RUT:ServiceJourney:2"))
                .isEqualTo(fromOriginal.journeyPatternOf("RUT:ServiceJourney:2"))
                .isEqualTo(""); // null pattern -> "" placeholder
        assertThat(fromSnapshot.lineOf("RUT:ServiceJourney:1"))
                .isEqualTo(fromOriginal.lineOf("RUT:ServiceJourney:1"))
                .isEqualTo("RUT:Line:dangling"); // dangling ref kept
        assertThat(fromSnapshot.datedServiceJourney("RUT:DatedServiceJourney:1"))
                .isEqualTo(fromOriginal.datedServiceJourney("RUT:DatedServiceJourney:1"));
        assertThat(fromSnapshot.datedServiceJourney("RUT:DatedServiceJourney:2"))
                .isEqualTo(fromOriginal.datedServiceJourney("RUT:DatedServiceJourney:2"));
        assertThat(fromSnapshot.datedServiceJourney("RUT:DatedServiceJourney:3"))
                .isEqualTo(fromOriginal.datedServiceJourney("RUT:DatedServiceJourney:3"))
                .isEqualTo(new DatedJourneyRef("RUT:ServiceJourney:3", null));

        assertThat(fromSnapshot.pointsOnLink("RUT:JourneyPattern:2"))
                .isEqualTo(fromOriginal.pointsOnLink("RUT:JourneyPattern:2"));
        var snapshotPoints = fromSnapshot.pointsOnLink("RUT:JourneyPattern:1");
        var originalPoints = fromOriginal.pointsOnLink("RUT:JourneyPattern:1");
        assertThat(snapshotPoints.getPoints()).isEqualTo(originalPoints.getPoints());
        assertThat(snapshotPoints.getLength()).isEqualTo(originalPoints.getLength());
    }

    @Test
    public void v2HeaderAndTruncationAreGuarded(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        builder.addOperator("O:1", "One");
        builder.addServiceLink("L:1", new int[]{10, 20, 5});
        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "e");
        byte[] good = Files.readAllBytes(file);

        byte[] wrongMagic = good.clone();
        wrongMagic[0] = 'X';
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongMagic), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("magic");

        byte[] wrongVersion = good.clone();
        wrongVersion[7] = (byte) (wrongVersion[7] + 1);
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongVersion), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("version");

        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3);
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(truncated), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class);

        byte[] wrongCount = good.clone();
        wrongCount[wrongCount.length - 1] = (byte) (wrongCount[wrongCount.length - 1] + 1);
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongCount), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("count");

        // Trailer marker byte (0xFF) is the one right before the record-count varint.
        byte[] wrongTrailer = good.clone();
        int trailerIndex = wrongTrailer.length - 2;
        assertThat(wrongTrailer[trailerIndex]).isEqualTo((byte) 0xFF);
        wrongTrailer[trailerIndex] = 0x00;
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongTrailer), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("trailer");

        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(new byte[0]), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class);
    }
}
