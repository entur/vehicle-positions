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
 * Everything that knows the fields of a {@link LineRecord}: how to read one from NeTEx and how
 * to write and read its snapshot record. A new field touches only the record and this class,
 * plus a {@link PlannedDataSnapshot#FORMAT_VERSION} bump.
 */
final class LineCodec {

    private LineCodec() {
    }

    /**
     * Reads a {@code Line} or {@code FlexibleLine} from its START_ELEMENT to its END_ELEMENT.
     * Null when the element has no id.
     */
    static LineRecord read(XMLStreamReader r) throws XMLStreamException {
        String id = id(r);
        Fields f = new Fields();
        scan(r, (reader, localName, depth) -> {
            if (depth == 1) {
                f.child = localName;
                switch (localName) {
                    case "Name" -> { f.name = reader.getElementText(); return true; }
                    case "PublicCode" -> { f.publicCode = reader.getElementText(); return true; }
                    case "TransportMode" -> { f.transportMode = reader.getElementText(); return true; }
                    default -> { return false; }
                }
            }
            // AlternativePresentation has Colour and TextColour children too; only Presentation counts.
            if (depth == 2 && "Presentation".equals(f.child)) {
                switch (localName) {
                    case "Colour" -> { f.colour = reader.getElementText(); return true; }
                    case "TextColour" -> { f.textColour = reader.getElementText(); return true; }
                    default -> { return false; }
                }
            }
            return false;
        });
        return id == null ? null : new LineRecord(id, f.name, f.publicCode, f.colour, f.textColour, f.transportMode);
    }

    static void write(DataOutputStream out, IdCodec.Writer ids, LineRecord line) throws IOException {
        ids.writeId(out, line.id());
        SnapshotIo.writeString(out, line.name());
        SnapshotIo.writeString(out, line.publicCode());
        SnapshotIo.writeString(out, line.colour());
        SnapshotIo.writeString(out, line.textColour());
        SnapshotIo.writeString(out, line.transportMode());
    }

    static LineRecord read(DataInputStream in, IdCodec.Reader ids) throws IOException {
        // Java evaluates arguments left to right, so this reads the fields in write order.
        return new LineRecord(ids.readId(in), SnapshotIo.readString(in), SnapshotIo.readString(in),
                SnapshotIo.readString(in), SnapshotIo.readString(in), SnapshotIo.readString(in));
    }

    /** Named slots the scan lambda can assign. */
    private static final class Fields {
        String child; // the direct child the scan is currently inside
        String name;
        String publicCode;
        String colour;
        String textColour;
        String transportMode;
    }
}
