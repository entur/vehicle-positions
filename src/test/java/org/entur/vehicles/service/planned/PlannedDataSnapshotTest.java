package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.SnapshotFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlannedDataSnapshotTest {

    private static Path goaZip() throws URISyntaxException {
        return Path.of(PlannedDataSnapshotTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI());
    }

    @Test
    public void aReplayedSnapshotBuildsTheSameDatasetAsTheParse(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        PlannedDataset.Builder parsed = new PlannedDataset.Builder();
        try (PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "etag-1")) {
            TeeSink tee = new TeeSink(parsed, writer);
            new PlannedDataLoader().load(goaZip(), tee);
            assertThat(tee.writerFailed()).isFalse();
        }
        PlannedDataset fromParse = parsed.build();

        PlannedDataset.Builder replayed = new PlannedDataset.Builder();
        try (InputStream in = Files.newInputStream(raw)) {
            PlannedDataSnapshot.replay(in, replayed);
        }
        PlannedDataset fromSnapshot = replayed.build();

        assertThat(fromSnapshot.stats()).isEqualTo(fromParse.stats());
        assertThat(fromSnapshot.operator("GOA:Operator:GOA").getName()).isEqualTo(fromParse.operator("GOA:Operator:GOA").getName());
        assertThat(fromSnapshot.line("GOA:Line:59").getLineName()).isEqualTo(fromParse.line("GOA:Line:59").getLineName());
        assertThat(fromSnapshot.line("GOA:Line:59").getPublicCode()).isEqualTo(fromParse.line("GOA:Line:59").getPublicCode());
        assertThat(fromSnapshot.journeyPatternOf("GOA:ServiceJourney:B3008-AA_30082-R"))
                .isEqualTo(fromParse.journeyPatternOf("GOA:ServiceJourney:B3008-AA_30082-R"));
        assertThat(fromSnapshot.lineOf("GOA:ServiceJourney:B3008-AA_30082-R"))
                .isEqualTo(fromParse.lineOf("GOA:ServiceJourney:B3008-AA_30082-R"));
        assertThat(fromSnapshot.datedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20"))
                .isEqualTo(fromParse.datedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20"));
        assertThat(fromSnapshot.pointsOnLink("GOA:JourneyPattern:L59-153").getPoints())
                .isEqualTo(fromParse.pointsOnLink("GOA:JourneyPattern:L59-153").getPoints());
        // ObjectRef caches its hashCode lazily in a field; ignore it so an incidental hashCode()
        // call on one side does not make two equal lines compare unequal.
        assertThat(fromSnapshot.lines(null)).usingRecursiveComparison().ignoringFields("hashCode").isEqualTo(fromParse.lines(null));
        assertThat(fromSnapshot.operators(null)).usingRecursiveComparison().ignoringFields("hashCode").isEqualTo(fromParse.operators(null));
        assertThat(fromSnapshot.codespaces()).isEqualTo(fromParse.codespaces()); // Codespace instances are interned
    }

    @Test
    public void nullsAndEmptyGeometryRoundTrip(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        try (PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "e")) {
            writer.addOperator("O:1", null);
            writer.addLine("L:1", null, null);
            writer.addServiceLink("SL:1", null);
            writer.addServiceLink("SL:2", new int[0]);
            writer.addServiceLink("SL:3", new int[]{1, 2, 3, 4});
            writer.addJourneyPattern("JP:1", List.of());
            writer.addJourneyPattern("JP:2", List.of("SL:1", "SL:3"));
            writer.addServiceJourney("SJ:1", null, null);
            writer.addServiceJourney("SJ:2", "JP:2", "L:1");
            writer.addDatedServiceJourney("DSJ:1", null, null);
            writer.addDatedServiceJourney("DSJ:2", "SJ:2", "OD:1");
            writer.addOperatingDay("OD:1", "2026-09-02");
            writer.addOperatingDay("OD:2", null);
        }

        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        try (InputStream in = Files.newInputStream(raw)) {
            PlannedDataSnapshot.replay(in, builder);
        }
        PlannedDataset dataset = builder.build();

        assertThat(dataset.operator("O:1").getName()).isNull();
        assertThat(dataset.line("L:1").getPublicCode()).isNull();
        assertThat(dataset.stats().serviceLinks()).isEqualTo(3);
        assertThat(dataset.stats().serviceJourneys()).isEqualTo(2);
        assertThat(dataset.journeyPatternOf("SJ:1")).isEqualTo("");
        assertThat(dataset.datedServiceJourney("DSJ:2")).isEqualTo(new DatedJourneyRef("SJ:2", "2026-09-02"));
        assertThat(dataset.datedServiceJourney("DSJ:1")).isEqualTo(new DatedJourneyRef(null, null));
        assertThat(dataset.pointsOnLink("JP:2").getLength()).isEqualTo(2);
    }

    @Test
    public void headerAndTruncationAreGuarded(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        try (PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "e")) {
            writer.addOperator("O:1", "One");
        }
        byte[] good = Files.readAllBytes(raw);

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
                .isInstanceOf(IOException.class);

        byte[] wrongCount = good.clone();
        wrongCount[wrongCount.length - 1] = (byte) (wrongCount[wrongCount.length - 1] + 1);
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongCount), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("count");

        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(new byte[0]), new PlannedDataset.Builder()))
                .isInstanceOf(EOFException.class);
    }

    @Test
    public void aFailingWriterDoesNotDisturbThePrimarySink(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "e");
        writer.close(); // any further write fails
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        TeeSink tee = new TeeSink(builder, writer);

        tee.addOperator("O:1", "One");
        tee.addLine("L:1", "Line", "1");

        assertThat(tee.writerFailed()).isTrue();
        PlannedDataset dataset = builder.build();
        assertThat(dataset.operator("O:1")).isNotNull();
        assertThat(dataset.line("L:1")).isNotNull();
    }

    @Test
    public void aFailingStreamPoisonsTheWriterAndCloseDoesNotThrow() throws Exception {
        FailingAfterHeaderStream stream = new FailingAfterHeaderStream();
        PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer((OutputStream) stream, "e");
        stream.fail = true; // header already written; every write from here on fails

        assertThatThrownBy(() -> writer.addOperator("O:1", "One")).isInstanceOf(UncheckedIOException.class);
        assertThat(writer.failed()).isTrue();
        assertThatCode(writer::close).doesNotThrowAnyException();

        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        TeeSink tee = new TeeSink(builder, writer);
        assertThat(tee.writerFailed()).isTrue();
    }

    /** Accepts every byte until {@link #fail} is flipped, then fails every subsequent write. */
    private static final class FailingAfterHeaderStream extends OutputStream {
        volatile boolean fail = false;
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

        @Override
        public void write(int b) throws IOException {
            if (fail) {
                throw new IOException("disk full");
            }
            sink.write(b);
        }
    }

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
        PlannedDataSnapshot.write(builder, file, "etag-1", null, null);

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
        PlannedDataSnapshot.write(resolvable, resolvableFile, "e", null, null);

        PlannedDataset.Builder dangling = new PlannedDataset.Builder();
        dangling.addServiceJourney("RUT:ServiceJourney:1", "RUT:JourneyPattern:missing", null);
        Path danglingFile = dir.resolve("dangling.bin");
        PlannedDataSnapshot.write(dangling, danglingFile, "e", null, null);

        assertThat(Files.size(danglingFile)).isGreaterThan(Files.size(resolvableFile));
    }

    @Test
    public void v2HeaderCarriesWindowAndDuplicateCount(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        builder.addOperator("O:1", "One");
        builder.addOperator("O:1", "One again"); // duplicate id

        LocalDate asOf = LocalDate.of(2026, 9, 2);
        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "e", 7, asOf);

        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            in.readInt(); // version
            in.readUTF(); // etag
            in.readLong(); // createdAt
            assertThat(in.readInt()).isEqualTo(7); // futureDays
            assertThat(in.readLong()).isEqualTo(asOf.toEpochDay()); // asOfEpochDay
            assertThat(in.readInt()).isEqualTo(1); // duplicateIds
        }
    }

    @Test
    public void v2HeaderMarksUnlimitedWindowAsMinusOne(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "e", null, null);

        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            in.readInt(); // version
            in.readUTF(); // etag
            in.readLong(); // createdAt
            assertThat(in.readInt()).isEqualTo(-1); // futureDays
            assertThat(in.readLong()).isEqualTo(-1L); // asOfEpochDay
            assertThat(in.readInt()).isEqualTo(0); // duplicateIds
        }
    }

    @Test
    public void v2ExactBytesOfASmallFixture(@TempDir Path dir) throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        builder.addOperator("O:1", "One");
        builder.addServiceLink("L:1", new int[]{10, 20, 5});

        Path file = dir.resolve("planned-v2.bin");
        PlannedDataSnapshot.write(builder, file, "e", null, null);
        byte[] bytes = Files.readAllBytes(file);

        // Header up to and including createdAt is variable-length (writeUTF) or time-based;
        // skip past it deterministically: magic(4) + version(4) + writeUTF("e")(2+1) + createdAt(8).
        int offset = 4 + 4 + (2 + 1) + 8;

        byte[] expectedTail = {
                // futureDays = -1 (unlimited)
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                // asOfEpochDay = -1 (unlimited)
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
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
}
