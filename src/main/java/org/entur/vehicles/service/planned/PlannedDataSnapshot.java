package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.planned.PlannedDataSink.StopDestinationDisplay;
import org.entur.vehicles.service.snapshot.IdCodec;
import org.entur.vehicles.service.snapshot.SnapshotFormatException;
import org.entur.vehicles.service.snapshot.SnapshotIo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The planned-data snapshot format: the builder's completed state, written after the parse
 * has finished, so a replay through {@link PlannedDataset.Builder} builds exactly what a
 * parse would. Header, dictionary of id prefixes, sections in dependency order, then an end
 * marker and the record count.
 * <p>
 * Bump {@link #FORMAT_VERSION} whenever a record's layout or the set of extracted fields
 * changes; the version is part of the object name, so old and new images never read each
 * other's snapshots.
 */
public final class PlannedDataSnapshot {

    public static final String DATASET = "planned-data";
    /**
     * 3: a line record carries its presentation colour and text colour after the public code.
     * 4: a line record then carries its transport mode, and a service journey record its own.
     * 5: a destination display section after the operating days, and a journey pattern record
     *    carries its stops' destination display refs after its links.
     */
    public static final int FORMAT_VERSION = 5;

    private static final byte[] MAGIC = {'V', 'P', 'P', '2'};
    private static final byte TAG_END = (byte) 0xFF;
    private static final StopDestinationDisplay[] NO_DISPLAYS = new StopDestinationDisplay[0];

    private PlannedDataSnapshot() {
    }

    /**
     * Writes the snapshot from the builder's completed state (see the format doc:
     * {@code docs/superpowers/specs/2026-09-03-snapshot-v2-encoding-design.md}, "Snapshot
     * format v2"; format versions 3 to 5 keep that encoding and only add a section and append
     * fields to records, see {@link #FORMAT_VERSION}). Reads the builder's maps
     * directly, so it must run only after the parse (or a replay) has finished populating them.
     */
    public static void write(PlannedDataset.Builder builder, Path file, String etag) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(etag);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(builder.duplicateIds());

            Map<String, OperatorRecord> operators = builder.operators();
            Map<String, LineRecord> lines = builder.lines();
            Map<String, OperatingDayRecord> operatingDays = builder.operatingDays();
            Map<String, int[]> linkGeometry = builder.linkGeometry();
            Map<String, String[]> patternLinks = builder.patternLinks();
            Map<String, ServiceJourneyRecord> serviceJourneys = builder.serviceJourneys();
            Map<String, DestinationDisplayRecord> destinationDisplays = builder.destinationDisplays();
            Map<String, StopDestinationDisplay[]> patternDestinationDisplays = builder.patternDestinationDisplays();
            Map<String, PlannedDataset.Builder.RawDatedServiceJourney> rawDatedServiceJourneys = builder.rawDatedServiceJourneys();

            IdCodec.Writer ids = new IdCodec.Writer();
            internIds(ids, operators, lines, operatingDays, destinationDisplays, linkGeometry, patternLinks,
                    patternDestinationDisplays, serviceJourneys, rawDatedServiceJourneys);
            ids.writeTable(out);

            int totalRecords = 0;

            // 1. operators - no references
            SnapshotIo.writeVarInt(out, operators.size());
            for (OperatorRecord operator : operators.values()) {
                OperatorCodec.write(out, ids, operator);
                totalRecords++;
            }

            // 2. lines - no references
            SectionIndex.Writer lineIndex = new SectionIndex.Writer();
            SnapshotIo.writeVarInt(out, lines.size());
            for (LineRecord line : lines.values()) {
                lineIndex.add(line.id());
                LineCodec.write(out, ids, line);
                totalRecords++;
            }

            // 3. operatingDays - no references
            SectionIndex.Writer operatingDayIndex = new SectionIndex.Writer();
            SnapshotIo.writeVarInt(out, operatingDays.size());
            for (OperatingDayRecord day : operatingDays.values()) {
                operatingDayIndex.add(day.id());
                OperatingDayCodec.write(out, ids, day);
                totalRecords++;
            }

            // 4. destinationDisplays - no references
            SectionIndex.Writer destinationDisplayIndex = new SectionIndex.Writer();
            SnapshotIo.writeVarInt(out, destinationDisplays.size());
            for (DestinationDisplayRecord display : destinationDisplays.values()) {
                destinationDisplayIndex.add(display.id());
                DestinationDisplayCodec.write(out, ids, display);
                totalRecords++;
            }

            // 5. serviceLinks - no references, delta-encoded geometry
            SectionIndex.Writer linkIndex = new SectionIndex.Writer();
            SnapshotIo.writeVarInt(out, linkGeometry.size());
            for (Map.Entry<String, int[]> e : linkGeometry.entrySet()) {
                linkIndex.add(e.getKey());
                ids.writeId(out, e.getKey());
                writeGeometry(out, e.getValue());
                totalRecords++;
            }

            // 6. journeyPatterns - refs into serviceLinks and destinationDisplays
            SectionIndex.Writer patternIndex = new SectionIndex.Writer();
            SnapshotIo.writeVarInt(out, patternLinks.size());
            for (Map.Entry<String, String[]> e : patternLinks.entrySet()) {
                patternIndex.add(e.getKey());
                ids.writeId(out, e.getKey());
                String[] linkIds = e.getValue();
                SnapshotIo.writeVarInt(out, linkIds.length);
                for (String linkId : linkIds) {
                    linkIndex.writeRef(out, ids, linkId);
                }
                StopDestinationDisplay[] displays = patternDestinationDisplays.getOrDefault(e.getKey(), NO_DISPLAYS);
                SnapshotIo.writeVarInt(out, displays.length);
                for (StopDestinationDisplay display : displays) {
                    SnapshotIo.writeZigZag(out, display.order());
                    destinationDisplayIndex.writeRef(out, ids, display.destinationDisplayId());
                }
                totalRecords++;
            }

            // 7. serviceJourneys - refs into journeyPatterns and lines
            SectionIndex.Writer journeyIndex = new SectionIndex.Writer();
            SnapshotIo.writeVarInt(out, serviceJourneys.size());
            for (ServiceJourneyRecord journey : serviceJourneys.values()) {
                journeyIndex.add(journey.id());
                ServiceJourneyCodec.write(out, ids, journey, patternIndex, lineIndex);
                totalRecords++;
            }

            // 8. datedServiceJourneys - refs into serviceJourneys and operatingDays.
            SnapshotIo.writeVarInt(out, rawDatedServiceJourneys.size());
            for (Map.Entry<String, PlannedDataset.Builder.RawDatedServiceJourney> e : rawDatedServiceJourneys.entrySet()) {
                ids.writeId(out, e.getKey());
                PlannedDataset.Builder.RawDatedServiceJourney raw = e.getValue();
                journeyIndex.writeRef(out, ids, raw.serviceJourneyId());
                operatingDayIndex.writeRef(out, ids, raw.operatingDayId());
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
                                   Map<String, OperatorRecord> operators,
                                   Map<String, LineRecord> lines,
                                   Map<String, OperatingDayRecord> operatingDays,
                                   Map<String, DestinationDisplayRecord> destinationDisplays,
                                   Map<String, int[]> linkGeometry,
                                   Map<String, String[]> patternLinks,
                                   Map<String, StopDestinationDisplay[]> patternDestinationDisplays,
                                   Map<String, ServiceJourneyRecord> serviceJourneys,
                                   Map<String, PlannedDataset.Builder.RawDatedServiceJourney> rawDatedServiceJourneys) {
        for (OperatorRecord operator : operators.values()) {
            OperatorCodec.intern(ids, operator);
        }
        for (LineRecord line : lines.values()) {
            LineCodec.intern(ids, line);
        }
        for (OperatingDayRecord day : operatingDays.values()) {
            OperatingDayCodec.intern(ids, day);
        }
        for (DestinationDisplayRecord display : destinationDisplays.values()) {
            DestinationDisplayCodec.intern(ids, display);
        }
        internAll(ids, linkGeometry.keySet());
        for (Map.Entry<String, String[]> e : patternLinks.entrySet()) {
            ids.intern(e.getKey());
            for (String linkId : e.getValue()) {
                ids.intern(linkId);
            }
        }
        for (StopDestinationDisplay[] displays : patternDestinationDisplays.values()) {
            for (StopDestinationDisplay display : displays) {
                ids.intern(display.destinationDisplayId());
            }
        }
        for (ServiceJourneyRecord journey : serviceJourneys.values()) {
            ServiceJourneyCodec.intern(ids, journey);
        }
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
     * Reads a snapshot (see {@link #write}) and feeds its records into {@code sink} in section
     * order, so refs resolve against sections already read. Throws {@link
     * SnapshotFormatException} on bad magic, wrong version, a truncated file or a record-count
     * mismatch.
     */
    public static void replay(InputStream stream, PlannedDataSink sink) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 1 << 16));
        try {
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
            sink.seedDuplicateIds(in.readInt());

            IdCodec.Reader ids = new IdCodec.Reader();
            ids.readTable(in);

            int totalRecords = 0;

            // 1. operators - no references
            int operatorCount = (int) SnapshotIo.readVarInt(in);
            for (int i = 0; i < operatorCount; i++) {
                sink.addOperator(OperatorCodec.read(in, ids));
                totalRecords++;
            }

            // 2. lines - no references
            int lineCount = (int) SnapshotIo.readVarInt(in);
            SectionIndex.Reader lineIds = new SectionIndex.Reader(lineCount);
            for (int i = 0; i < lineCount; i++) {
                LineRecord line = LineCodec.read(in, ids);
                lineIds.add(line.id());
                sink.addLine(line);
                totalRecords++;
            }

            // 3. operatingDays - no references
            int operatingDayCount = (int) SnapshotIo.readVarInt(in);
            SectionIndex.Reader operatingDayIds = new SectionIndex.Reader(operatingDayCount);
            for (int i = 0; i < operatingDayCount; i++) {
                OperatingDayRecord day = OperatingDayCodec.read(in, ids);
                operatingDayIds.add(day.id());
                sink.addOperatingDay(day);
                totalRecords++;
            }

            // 4. destinationDisplays - no references
            int destinationDisplayCount = (int) SnapshotIo.readVarInt(in);
            SectionIndex.Reader destinationDisplayIds = new SectionIndex.Reader(destinationDisplayCount);
            for (int i = 0; i < destinationDisplayCount; i++) {
                DestinationDisplayRecord display = DestinationDisplayCodec.read(in, ids);
                destinationDisplayIds.add(display.id());
                sink.addDestinationDisplay(display);
                totalRecords++;
            }

            // 5. serviceLinks - no references, delta-encoded geometry
            int linkCount = (int) SnapshotIo.readVarInt(in);
            SectionIndex.Reader linkIds = new SectionIndex.Reader(linkCount);
            for (int i = 0; i < linkCount; i++) {
                String id = ids.readId(in);
                linkIds.add(id);
                sink.addServiceLink(id, readGeometry(in));
                totalRecords++;
            }

            // 6. journeyPatterns - refs into serviceLinks and destinationDisplays
            int patternCount = (int) SnapshotIo.readVarInt(in);
            SectionIndex.Reader patternIds = new SectionIndex.Reader(patternCount);
            for (int i = 0; i < patternCount; i++) {
                String id = ids.readId(in);
                patternIds.add(id);
                int linkRefCount = (int) SnapshotIo.readVarInt(in);
                List<String> links = new ArrayList<>(linkRefCount);
                for (int j = 0; j < linkRefCount; j++) {
                    links.add(linkIds.readRef(in, ids));
                }
                int displayCount = (int) SnapshotIo.readVarInt(in);
                List<StopDestinationDisplay> displays = new ArrayList<>(displayCount);
                for (int j = 0; j < displayCount; j++) {
                    int order = (int) SnapshotIo.readZigZag(in);
                    displays.add(new StopDestinationDisplay(order, destinationDisplayIds.readRef(in, ids)));
                }
                sink.addJourneyPattern(id, links, displays);
                totalRecords++;
            }

            // 7. serviceJourneys - refs into journeyPatterns and lines
            int journeyCount = (int) SnapshotIo.readVarInt(in);
            SectionIndex.Reader journeyIds = new SectionIndex.Reader(journeyCount);
            for (int i = 0; i < journeyCount; i++) {
                ServiceJourneyRecord journey = ServiceJourneyCodec.read(in, ids, patternIds, lineIds);
                journeyIds.add(journey.id());
                sink.addServiceJourney(journey);
                totalRecords++;
            }

            // 8. datedServiceJourneys - refs into serviceJourneys and operatingDays
            int datedCount = (int) SnapshotIo.readVarInt(in);
            for (int i = 0; i < datedCount; i++) {
                String id = ids.readId(in);
                String journeyRef = journeyIds.readRef(in, ids);
                String operatingDayRef = operatingDayIds.readRef(in, ids);
                sink.addDatedServiceJourney(id, journeyRef, operatingDayRef);
                totalRecords++;
            }

            byte trailer = in.readByte();
            if (trailer != TAG_END) {
                throw new SnapshotFormatException("Planned-data snapshot trailer marker " + (trailer & 0xFF) + ", expected " + (TAG_END & 0xFF));
            }
            int expected = (int) SnapshotIo.readVarInt(in);
            if (expected != totalRecords) {
                throw new SnapshotFormatException("Planned-data snapshot record count " + totalRecords + ", header says " + expected);
            }
        } catch (java.io.EOFException e) {
            throw new SnapshotFormatException("Truncated planned-data snapshot");
        }
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

}
