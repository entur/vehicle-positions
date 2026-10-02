package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.IdCodec;
import org.entur.vehicles.service.snapshot.SnapshotIo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * References between planned-data snapshot sections. A record refers to one in an earlier
 * section by its position there: varint 0 is null, {@code 1..N} is a 1-based position, and
 * {@code N+1} introduces a literal id - a dangling reference, kept instead of dropped so it
 * survives the round trip exactly as {@code Stats.unresolved*Refs} found it.
 */
final class SectionIndex {

    private SectionIndex() {
    }

    /** The positions of the records written to one section, so later sections can refer to them. */
    static final class Writer {

        private final Map<String, Integer> positions = new HashMap<>();

        /** Registers the next record written to the section. */
        void add(String id) {
            positions.put(id, positions.size());
        }

        void writeRef(DataOutputStream out, IdCodec.Writer ids, String id) throws IOException {
            if (id == null) {
                SnapshotIo.writeVarInt(out, 0);
                return;
            }
            Integer position = positions.get(id);
            if (position != null) {
                SnapshotIo.writeVarInt(out, position + 1L);
            } else {
                SnapshotIo.writeVarInt(out, positions.size() + 1L);
                ids.writeId(out, id);
            }
        }
    }

    /** The ids of one section's records in the order they were read. */
    static final class Reader {

        private final String[] ids;
        private int size;

        Reader(int sectionSize) {
            this.ids = new String[sectionSize];
        }

        /** Registers the next record read from the section. */
        void add(String id) {
            ids[size++] = id;
        }

        /** Must run only after the whole section has been read. */
        String readRef(DataInputStream in, IdCodec.Reader idReader) throws IOException {
            long v = SnapshotIo.readVarInt(in);
            if (v == 0) {
                return null;
            }
            int position = (int) (v - 1);
            return position < ids.length ? ids[position] : idReader.readId(in);
        }
    }
}
