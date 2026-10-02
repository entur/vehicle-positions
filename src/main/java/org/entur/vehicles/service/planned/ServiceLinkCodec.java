package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.IdCodec;
import org.entur.vehicles.service.snapshot.SnapshotIo;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.entur.vehicles.service.planned.Stax.id;
import static org.entur.vehicles.service.planned.Stax.scan;

/**
 * Everything that knows the fields of a {@link ServiceLinkRecord}: how to read one from NeTEx
 * and how to write and read its snapshot record. A new field touches only the record and this
 * class, plus a {@link PlannedDataSnapshot#FORMAT_VERSION} bump.
 */
final class ServiceLinkCodec {

    private ServiceLinkCodec() {
    }

    /**
     * Reads a {@code ServiceLink} from its START_ELEMENT to its END_ELEMENT, taking the
     * {@code gis:posList} under its projections. Null when the element has no id.
     */
    static ServiceLinkRecord read(XMLStreamReader r) throws XMLStreamException {
        String id = id(r);
        int[][] geometry = new int[1][];
        scan(r, (reader, localName, depth) -> {
            if (localName.equals("posList")) {
                geometry[0] = PosListParser.parse(reader.getElementText());
                return true;
            }
            return false;
        });
        return id == null ? null : new ServiceLinkRecord(id, geometry[0]);
    }

    /** Registers every id the record will write, before the prefix table is written. */
    static void intern(IdCodec.Writer ids, ServiceLinkRecord link) {
        ids.intern(link.id());
    }

    /**
     * Writes the geometry as a count followed by one zigzag varint per value, each
     * delta-encoded against the value two positions back (0 for the first two entries) - the
     * two-back rule needs no special case for an odd-length array.
     */
    static void write(DataOutputStream out, IdCodec.Writer ids, ServiceLinkRecord link) throws IOException {
        ids.writeId(out, link.id());
        int[] geometry = link.geometry();
        SnapshotIo.writeVarInt(out, geometry.length);
        for (int i = 0; i < geometry.length; i++) {
            long previous = i >= 2 ? geometry[i - 2] : 0;
            SnapshotIo.writeZigZag(out, geometry[i] - previous);
        }
    }

    static ServiceLinkRecord read(DataInputStream in, IdCodec.Reader ids) throws IOException {
        String id = ids.readId(in);
        int length = (int) SnapshotIo.readVarInt(in);
        int[] geometry = new int[length];
        for (int i = 0; i < length; i++) {
            long delta = SnapshotIo.readZigZag(in);
            long previous = i >= 2 ? geometry[i - 2] : 0;
            geometry[i] = (int) (delta + previous);
        }
        return new ServiceLinkRecord(id, geometry);
    }
}
