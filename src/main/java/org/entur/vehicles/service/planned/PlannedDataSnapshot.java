package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.SnapshotFormatException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    public static final String DATASET = "planned-data";
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'V', 'P', 'P', 'D'};
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
        return new Writer(file, etag);
    }

    /** A {@link PlannedDataSink} that appends each record to the file. Write failures surface as {@link UncheckedIOException}. */
    public static final class Writer implements PlannedDataSink, Closeable {

        private final DataOutputStream out;
        private int count = 0;
        private boolean closed = false;

        private Writer(Path file, String etag) throws IOException {
            this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(etag);
            out.writeLong(System.currentTimeMillis());
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
                throw new UncheckedIOException(e);
            }
        }

        private void nullable(String s) throws IOException {
            out.writeBoolean(s != null);
            if (s != null) {
                out.writeUTF(s);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.writeByte(TAG_END);
                out.writeInt(count);
            } finally {
                out.close();
            }
        }
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
