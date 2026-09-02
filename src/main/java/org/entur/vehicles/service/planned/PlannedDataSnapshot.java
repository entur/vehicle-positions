package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.service.snapshot.IdCodec;
import org.entur.vehicles.service.snapshot.SnapshotFormatException;
import org.entur.vehicles.service.snapshot.SnapshotIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The planned-data snapshot format: the raw records the NeTEx extractor emits, in order, so
 * a replay through {@link PlannedDataset.Builder} builds exactly what a parse would. Header,
 * then tagged records, then an end marker and the record count.
 * <p>
 * Bump {@link #FORMAT_VERSION} whenever a record's layout or the set of extracted fields
 * changes; the version is part of the object name, so old and new images never read each
 * other's snapshots.
 */
public final class PlannedDataSnapshot {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataSnapshot.class);

    public static final String DATASET = "planned-data";
    public static final int FORMAT_VERSION = 2;

    private static final byte[] MAGIC = {'V', 'P', 'P', 'D'};
    private static final byte[] MAGIC_V2 = {'V', 'P', 'P', '2'};
    private static final byte TAG_OPERATOR = 1;
    private static final byte TAG_LINE = 2;
    private static final byte TAG_SERVICE_LINK = 3;
    private static final byte TAG_JOURNEY_PATTERN = 4;
    private static final byte TAG_SERVICE_JOURNEY = 5;
    private static final byte TAG_DATED_SERVICE_JOURNEY = 6;
    private static final byte TAG_OPERATING_DAY = 7;
    private static final byte TAG_END = (byte) 0xFF;

    private PlannedDataSnapshot() {
    }

    public static Writer writer(Path file, String etag) throws IOException {
        return writer(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16), etag);
    }

    /** Package-private for tests: builds a writer directly over any stream, buffered or not. */
    static Writer writer(OutputStream out, String etag) throws IOException {
        return new Writer(out, etag);
    }

    /** A {@link PlannedDataSink} that appends each record to the file. Write failures surface as {@link UncheckedIOException}. */
    public static final class Writer implements PlannedDataSink, Closeable {

        private final DataOutputStream out;
        private int count = 0;
        private boolean closed = false;
        private boolean failed = false;

        private Writer(OutputStream stream, String etag) throws IOException {
            this.out = new DataOutputStream(stream);
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(etag);
            out.writeLong(System.currentTimeMillis());
        }

        /** True once a write to the underlying stream has failed; the writer is poisoned and no longer used. */
        public boolean failed() {
            return failed;
        }

        @Override
        public Writer addOperator(String id, String name) {
            return record(TAG_OPERATOR, () -> {
                out.writeUTF(id);
                nullable(name);
            });
        }

        @Override
        public Writer addLine(String id, String name, String publicCode) {
            return record(TAG_LINE, () -> {
                out.writeUTF(id);
                nullable(name);
                nullable(publicCode);
            });
        }

        @Override
        public Writer addServiceLink(String id, int[] geometry) {
            return record(TAG_SERVICE_LINK, () -> {
                out.writeUTF(id);
                if (geometry == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(geometry.length);
                    for (int v : geometry) {
                        out.writeInt(v);
                    }
                }
            });
        }

        @Override
        public Writer addJourneyPattern(String id, List<String> serviceLinkIds) {
            return record(TAG_JOURNEY_PATTERN, () -> {
                out.writeUTF(id);
                out.writeInt(serviceLinkIds.size());
                for (String link : serviceLinkIds) {
                    out.writeUTF(link);
                }
            });
        }

        @Override
        public Writer addServiceJourney(String id, String journeyPatternId, String lineId) {
            return record(TAG_SERVICE_JOURNEY, () -> {
                out.writeUTF(id);
                nullable(journeyPatternId);
                nullable(lineId);
            });
        }

        @Override
        public Writer addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId) {
            return record(TAG_DATED_SERVICE_JOURNEY, () -> {
                out.writeUTF(id);
                nullable(serviceJourneyId);
                nullable(operatingDayId);
            });
        }

        @Override
        public Writer addOperatingDay(String id, String calendarDate) {
            return record(TAG_OPERATING_DAY, () -> {
                out.writeUTF(id);
                nullable(calendarDate);
            });
        }

        private interface Body {
            void write() throws IOException;
        }

        private Writer record(byte tag, Body body) {
            if (closed) {
                // The buffered stream would silently absorb writes after close until its
                // buffer filled; fail at once instead so the tee drops the writer immediately.
                throw new UncheckedIOException(new IOException("snapshot writer is closed"));
            }
            try {
                out.writeByte(tag);
                body.write();
                count++;
                return this;
            } catch (IOException e) {
                failed = true;
                throw new UncheckedIOException(e);
            }
        }

        private void nullable(String s) throws IOException {
            out.writeBoolean(s != null);
            if (s != null) {
                out.writeUTF(s);
            }
        }

        /**
         * Never throws: the snapshot is a cache, not a dependency, so a write failure here is
         * logged and latched into {@link #failed()} rather than escaping to the caller.
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!failed) {
                try {
                    out.writeByte(TAG_END);
                    out.writeInt(count);
                } catch (IOException e) {
                    failed = true;
                    LOG.warn("Failed to write the planned-data snapshot trailer", e);
                }
            }
            try {
                out.close();
            } catch (IOException e) {
                failed = true;
                LOG.warn("Failed to close the planned-data snapshot file", e);
            }
        }
    }

    /**
     * Writes the v2 snapshot from the builder's completed state (see the format doc:
     * {@code docs/superpowers/specs/2026-09-03-snapshot-v2-encoding-design.md}, "Snapshot
     * format v2"). Unlike {@link Writer}, which tees raw records as the extractor emits them,
     * this reads the builder's maps directly, so it must run only after the parse (or a
     * replay) has finished populating them.
     */
    public static void write(PlannedDataset.Builder builder, Path file, String etag) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
            out.write(MAGIC_V2);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(etag);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(builder.duplicateIds());

            Map<String, Operator> operators = builder.operators();
            Map<String, Line> lines = builder.lines();
            Map<String, String> operatingDays = builder.operatingDays();
            Map<String, int[]> linkGeometry = builder.linkGeometry();
            Map<String, String[]> patternLinks = builder.patternLinks();
            Map<String, String> serviceJourneyPattern = builder.serviceJourneyPattern();
            Map<String, String> serviceJourneyLine = builder.serviceJourneyLine();
            Map<String, PlannedDataset.Builder.RawDatedServiceJourney> rawDatedServiceJourneys = builder.rawDatedServiceJourneys();

            IdCodec.Writer ids = new IdCodec.Writer();
            internIds(ids, operators, lines, operatingDays, linkGeometry, patternLinks, serviceJourneyPattern,
                    serviceJourneyLine, rawDatedServiceJourneys);
            ids.writeTable(out);

            int totalRecords = 0;

            // 1. operators - no references
            SnapshotIo.writeVarInt(out, operators.size());
            for (Map.Entry<String, Operator> e : operators.entrySet()) {
                ids.writeId(out, e.getKey());
                SnapshotIo.writeString(out, e.getValue().getName());
                totalRecords++;
            }

            // 2. lines - no references
            Map<String, Integer> lineIndex = new HashMap<>(lines.size() * 2);
            SnapshotIo.writeVarInt(out, lines.size());
            for (Map.Entry<String, Line> e : lines.entrySet()) {
                lineIndex.put(e.getKey(), lineIndex.size());
                ids.writeId(out, e.getKey());
                SnapshotIo.writeString(out, e.getValue().getLineName());
                SnapshotIo.writeString(out, e.getValue().getPublicCode());
                totalRecords++;
            }

            // 3. operatingDays - no references
            Map<String, Integer> operatingDayIndex = new HashMap<>(operatingDays.size() * 2);
            SnapshotIo.writeVarInt(out, operatingDays.size());
            for (Map.Entry<String, String> e : operatingDays.entrySet()) {
                operatingDayIndex.put(e.getKey(), operatingDayIndex.size());
                ids.writeId(out, e.getKey());
                writeOperatingDayDate(out, e.getValue());
                totalRecords++;
            }

            // 4. serviceLinks - no references, delta-encoded geometry
            Map<String, Integer> linkIndex = new HashMap<>(linkGeometry.size() * 2);
            SnapshotIo.writeVarInt(out, linkGeometry.size());
            for (Map.Entry<String, int[]> e : linkGeometry.entrySet()) {
                linkIndex.put(e.getKey(), linkIndex.size());
                ids.writeId(out, e.getKey());
                writeGeometry(out, e.getValue());
                totalRecords++;
            }

            // 5. journeyPatterns - refs into serviceLinks
            Map<String, Integer> patternIndex = new HashMap<>(patternLinks.size() * 2);
            SnapshotIo.writeVarInt(out, patternLinks.size());
            for (Map.Entry<String, String[]> e : patternLinks.entrySet()) {
                patternIndex.put(e.getKey(), patternIndex.size());
                ids.writeId(out, e.getKey());
                String[] linkIds = e.getValue();
                SnapshotIo.writeVarInt(out, linkIds.length);
                for (String linkId : linkIds) {
                    writeRef(out, ids, linkId, linkIndex, linkGeometry.size());
                }
                totalRecords++;
            }

            // 6. serviceJourneys - refs into journeyPatterns and lines
            Map<String, Integer> journeyIndex = new HashMap<>(serviceJourneyPattern.size() * 2);
            SnapshotIo.writeVarInt(out, serviceJourneyPattern.size());
            for (Map.Entry<String, String> e : serviceJourneyPattern.entrySet()) {
                String id = e.getKey();
                journeyIndex.put(id, journeyIndex.size());
                ids.writeId(out, id);
                // The builder stores "" (not null) for an absent pattern ref; write that as a
                // null reference rather than interning and writing the empty string.
                String patternId = e.getValue().isEmpty() ? null : e.getValue();
                writeRef(out, ids, patternId, patternIndex, patternLinks.size());
                writeRef(out, ids, serviceJourneyLine.get(id), lineIndex, lines.size());
                totalRecords++;
            }

            // 7. datedServiceJourneys - refs into serviceJourneys and operatingDays.
            SnapshotIo.writeVarInt(out, rawDatedServiceJourneys.size());
            for (Map.Entry<String, PlannedDataset.Builder.RawDatedServiceJourney> e : rawDatedServiceJourneys.entrySet()) {
                ids.writeId(out, e.getKey());
                PlannedDataset.Builder.RawDatedServiceJourney raw = e.getValue();
                writeRef(out, ids, raw.serviceJourneyId(), journeyIndex, serviceJourneyPattern.size());
                writeRef(out, ids, raw.operatingDayId(), operatingDayIndex, operatingDays.size());
                totalRecords++;
            }

            out.writeByte(TAG_END);
            SnapshotIo.writeVarInt(out, totalRecords);
        }
    }

    /**
     * Populates the id-codec prefix dictionary with every id the builder holds - owner ids
     * and reference values alike - so it is complete before any record (and therefore any
     * reference, resolvable or dangling) is written. One traversal, no retained memory beyond
     * the resulting prefix table.
     */
    private static void internIds(IdCodec.Writer ids,
                                   Map<String, Operator> operators,
                                   Map<String, Line> lines,
                                   Map<String, String> operatingDays,
                                   Map<String, int[]> linkGeometry,
                                   Map<String, String[]> patternLinks,
                                   Map<String, String> serviceJourneyPattern,
                                   Map<String, String> serviceJourneyLine,
                                   Map<String, PlannedDataset.Builder.RawDatedServiceJourney> rawDatedServiceJourneys) {
        internAll(ids, operators.keySet());
        internAll(ids, lines.keySet());
        internAll(ids, operatingDays.keySet());
        internAll(ids, linkGeometry.keySet());
        for (Map.Entry<String, String[]> e : patternLinks.entrySet()) {
            ids.intern(e.getKey());
            for (String linkId : e.getValue()) {
                ids.intern(linkId);
            }
        }
        for (Map.Entry<String, String> e : serviceJourneyPattern.entrySet()) {
            ids.intern(e.getKey());
            if (!e.getValue().isEmpty()) {
                ids.intern(e.getValue());
            }
        }
        internAll(ids, serviceJourneyLine.values());
        for (Map.Entry<String, PlannedDataset.Builder.RawDatedServiceJourney> e : rawDatedServiceJourneys.entrySet()) {
            ids.intern(e.getKey());
            PlannedDataset.Builder.RawDatedServiceJourney raw = e.getValue();
            if (raw.serviceJourneyId() != null) {
                ids.intern(raw.serviceJourneyId());
            }
            if (raw.operatingDayId() != null) {
                ids.intern(raw.operatingDayId());
            }
        }
    }

    private static void internAll(IdCodec.Writer ids, Collection<String> values) {
        for (String id : values) {
            ids.intern(id);
        }
    }

    /**
     * Writes a reference: varint 0 for null, {@code index + 1} for a hit in {@code index},
     * or {@code sectionSize + 1} followed by the literal id when {@code id} is not in
     * {@code index} - a dangling reference, kept instead of dropped so it survives the round
     * trip exactly as {@code Stats.unresolved*Refs} found it.
     */
    private static void writeRef(DataOutputStream out, IdCodec.Writer ids, String id, Map<String, Integer> index, int sectionSize) throws IOException {
        if (id == null) {
            SnapshotIo.writeVarInt(out, 0);
            return;
        }
        Integer position = index.get(id);
        if (position != null) {
            SnapshotIo.writeVarInt(out, position + 1L);
        } else {
            SnapshotIo.writeVarInt(out, sectionSize + 1L);
            ids.writeId(out, id);
        }
    }

    /**
     * Writes interleaved lat/lon microdegrees as a count followed by one zigzag varint per
     * value, each delta-encoded against the value two positions back (0 for the first two
     * entries) - the two-back rule needs no special case for an odd-length array.
     */
    private static void writeGeometry(DataOutputStream out, int[] geometry) throws IOException {
        SnapshotIo.writeVarInt(out, geometry.length);
        for (int i = 0; i < geometry.length; i++) {
            long previous = i >= 2 ? geometry[i - 2] : 0;
            SnapshotIo.writeZigZag(out, geometry[i] - previous);
        }
    }

    /**
     * Writes an operating day's calendar date as varint 0 for null (or an unparseable date -
     * kept rather than failing the whole snapshot over one bad record), else the zigzag
     * encoding of its epoch day, offset by one so a real date can never collide with the null
     * sentinel.
     */
    private static void writeOperatingDayDate(DataOutputStream out, String calendarDate) throws IOException {
        LocalDate date = null;
        if (calendarDate != null) {
            try {
                date = LocalDate.parse(calendarDate);
            } catch (DateTimeParseException e) {
                date = null;
            }
        }
        if (date == null) {
            SnapshotIo.writeVarInt(out, 0);
            return;
        }
        long epochDay = date.toEpochDay();
        long zigzag = (epochDay << 1) ^ (epochDay >> 63);
        SnapshotIo.writeVarInt(out, zigzag + 1);
    }

    /**
     * Reads a v2 snapshot (see {@link #write}) and feeds its records into {@code sink} in
     * section order, so refs resolve against sections already read. Throws {@link
     * SnapshotFormatException} on bad magic, wrong version, a truncated file or a record-count
     * mismatch.
     */
    public static void replayV2(InputStream stream, PlannedDataSink sink) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 1 << 16));
        try {
            byte[] magic = new byte[MAGIC_V2.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V2)) {
                throw new SnapshotFormatException("Not a v2 planned-data snapshot (bad magic)");
            }
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new SnapshotFormatException("Planned-data v2 snapshot version " + version + ", expected " + FORMAT_VERSION);
            }
            in.readUTF(); // etag, informational
            in.readLong(); // createdAt, informational
            sink.seedDuplicateIds(in.readInt());

            IdCodec.Reader ids = new IdCodec.Reader();
            ids.readTable(in);

            int totalRecords = 0;

            // 1. operators - no references
            int operatorCount = (int) SnapshotIo.readVarInt(in);
            for (int i = 0; i < operatorCount; i++) {
                String id = ids.readId(in);
                sink.addOperator(id, SnapshotIo.readString(in));
                totalRecords++;
            }

            // 2. lines - no references
            int lineCount = (int) SnapshotIo.readVarInt(in);
            String[] lineIds = new String[lineCount];
            for (int i = 0; i < lineCount; i++) {
                String id = ids.readId(in);
                lineIds[i] = id;
                String name = SnapshotIo.readString(in);
                String publicCode = SnapshotIo.readString(in);
                sink.addLine(id, name, publicCode);
                totalRecords++;
            }

            // 3. operatingDays - no references
            int operatingDayCount = (int) SnapshotIo.readVarInt(in);
            String[] operatingDayIds = new String[operatingDayCount];
            for (int i = 0; i < operatingDayCount; i++) {
                String id = ids.readId(in);
                operatingDayIds[i] = id;
                sink.addOperatingDay(id, readOperatingDayDate(in));
                totalRecords++;
            }

            // 4. serviceLinks - no references, delta-encoded geometry
            int linkCount = (int) SnapshotIo.readVarInt(in);
            String[] linkIds = new String[linkCount];
            for (int i = 0; i < linkCount; i++) {
                String id = ids.readId(in);
                linkIds[i] = id;
                sink.addServiceLink(id, readGeometry(in));
                totalRecords++;
            }

            // 5. journeyPatterns - refs into serviceLinks
            int patternCount = (int) SnapshotIo.readVarInt(in);
            String[] patternIds = new String[patternCount];
            for (int i = 0; i < patternCount; i++) {
                String id = ids.readId(in);
                patternIds[i] = id;
                int linkRefCount = (int) SnapshotIo.readVarInt(in);
                List<String> links = new ArrayList<>(linkRefCount);
                for (int j = 0; j < linkRefCount; j++) {
                    links.add(readRef(in, ids, linkIds));
                }
                sink.addJourneyPattern(id, links);
                totalRecords++;
            }

            // 6. serviceJourneys - refs into journeyPatterns and lines
            int journeyCount = (int) SnapshotIo.readVarInt(in);
            String[] journeyIds = new String[journeyCount];
            for (int i = 0; i < journeyCount; i++) {
                String id = ids.readId(in);
                journeyIds[i] = id;
                String patternRef = readRef(in, ids, patternIds);
                String lineRef = readRef(in, ids, lineIds);
                sink.addServiceJourney(id, patternRef, lineRef);
                totalRecords++;
            }

            // 7. datedServiceJourneys - refs into serviceJourneys and operatingDays
            int datedCount = (int) SnapshotIo.readVarInt(in);
            for (int i = 0; i < datedCount; i++) {
                String id = ids.readId(in);
                String journeyRef = readRef(in, ids, journeyIds);
                String operatingDayRef = readRef(in, ids, operatingDayIds);
                sink.addDatedServiceJourney(id, journeyRef, operatingDayRef);
                totalRecords++;
            }

            byte trailer = in.readByte();
            if (trailer != TAG_END) {
                throw new SnapshotFormatException("Planned-data v2 snapshot trailer marker " + (trailer & 0xFF) + ", expected " + (TAG_END & 0xFF));
            }
            int expected = (int) SnapshotIo.readVarInt(in);
            if (expected != totalRecords) {
                throw new SnapshotFormatException("Planned-data v2 snapshot record count " + totalRecords + ", header says " + expected);
            }
        } catch (java.io.EOFException e) {
            throw new SnapshotFormatException("Truncated planned-data v2 snapshot");
        }
    }

    /**
     * Reads a reference written by the v2 writer's {@code writeRef}: varint 0 is null,
     * {@code 1..N} is a 1-based index into {@code section} (already fully read), and
     * {@code N+1} introduces a literal id - a dangling reference, kept as-is so it survives
     * the round trip exactly as {@code Stats.unresolved*Refs} found it.
     */
    private static String readRef(DataInputStream in, IdCodec.Reader ids, String[] section) throws IOException {
        long v = SnapshotIo.readVarInt(in);
        if (v == 0) {
            return null;
        }
        int index = (int) (v - 1);
        return index < section.length ? section[index] : ids.readId(in);
    }

    /**
     * Reads geometry written by the v2 writer's {@code writeGeometry}: a count followed by
     * one zigzag varint per value, each delta-decoded against the value two positions back
     * (0 for the first two entries).
     */
    private static int[] readGeometry(DataInputStream in) throws IOException {
        int length = (int) SnapshotIo.readVarInt(in);
        int[] geometry = new int[length];
        for (int i = 0; i < length; i++) {
            long delta = SnapshotIo.readZigZag(in);
            long previous = i >= 2 ? geometry[i - 2] : 0;
            geometry[i] = (int) (delta + previous);
        }
        return geometry;
    }

    /**
     * Reads an operating day's calendar date written by the v2 writer's
     * {@code writeOperatingDayDate}: varint 0 is null, else the value minus one is the zigzag
     * encoding of the date's epoch day.
     */
    private static String readOperatingDayDate(DataInputStream in) throws IOException {
        long v = SnapshotIo.readVarInt(in);
        if (v == 0) {
            return null;
        }
        long zigzag = v - 1;
        long epochDay = (zigzag >>> 1) ^ -(zigzag & 1);
        return LocalDate.ofEpochDay(epochDay).toString();
    }

    /** Feeds every record of a snapshot into the sink. Throws {@link SnapshotFormatException} on a header or count mismatch and {@link IOException} on truncation. */
    public static void replay(InputStream stream, PlannedDataSink sink) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 1 << 16));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new SnapshotFormatException("Not a planned-data snapshot (bad magic)");
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new SnapshotFormatException("Planned-data snapshot version " + version + ", expected " + FORMAT_VERSION);
        }
        in.readUTF(); // etag, informational
        in.readLong(); // createdAt, informational

        int count = 0;
        while (true) {
            byte tag = in.readByte();
            if (tag == TAG_END) {
                break;
            }
            String id = in.readUTF();
            switch (tag) {
                case TAG_OPERATOR -> sink.addOperator(id, nullable(in));
                case TAG_LINE -> sink.addLine(id, nullable(in), nullable(in));
                case TAG_SERVICE_LINK -> {
                    int length = in.readInt();
                    int[] geometry = null;
                    if (length >= 0) {
                        geometry = new int[length];
                        for (int i = 0; i < length; i++) {
                            geometry[i] = in.readInt();
                        }
                    }
                    sink.addServiceLink(id, geometry);
                }
                case TAG_JOURNEY_PATTERN -> {
                    int size = in.readInt();
                    List<String> links = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        links.add(in.readUTF());
                    }
                    sink.addJourneyPattern(id, links);
                }
                case TAG_SERVICE_JOURNEY -> sink.addServiceJourney(id, nullable(in), nullable(in));
                case TAG_DATED_SERVICE_JOURNEY -> sink.addDatedServiceJourney(id, nullable(in), nullable(in));
                case TAG_OPERATING_DAY -> sink.addOperatingDay(id, nullable(in));
                default -> throw new SnapshotFormatException("Unknown planned-data record tag " + tag);
            }
            count++;
        }
        int expected = in.readInt();
        if (expected != count) {
            throw new SnapshotFormatException("Planned-data snapshot record count " + count + ", header says " + expected);
        }
    }

    private static String nullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
