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
 * Everything that knows the fields of a {@link OperatingDayRecord}: how to read one from NeTEx and how to
 * write and read its snapshot record. A new field touches only the record and this class,
 * plus a {@link PlannedDataSnapshot#FORMAT_VERSION} bump.
 */
final class OperatingDayCodec {

    private OperatingDayCodec() {
    }

    /**
     * Reads an {@code OperatingDay} from its START_ELEMENT to its END_ELEMENT. Null when the element
     * has no id.
     */
    static OperatingDayRecord read(XMLStreamReader r) throws XMLStreamException {
        String id = id(r);
        String[] calendarDate = new String[1];
        scan(r, (reader, localName, depth) -> {
            if (depth == 1 && localName.equals("CalendarDate")) {
                calendarDate[0] = reader.getElementText();
                return true;
            }
            return false;
        });
        return id == null ? null : new OperatingDayRecord(id, calendarDate[0]);
    }

    /** Registers every id the record will write, before the prefix table is written. */
    static void intern(IdCodec.Writer ids, OperatingDayRecord record) {
        ids.intern(record.id());
    }

    static void write(DataOutputStream out, IdCodec.Writer ids, OperatingDayRecord record) throws IOException {
        ids.writeId(out, record.id());
        SnapshotIo.writeString(out, record.calendarDate());
    }

    static OperatingDayRecord read(DataInputStream in, IdCodec.Reader ids) throws IOException {
        // Java evaluates arguments left to right, so this reads the fields in write order.
        return new OperatingDayRecord(ids.readId(in), SnapshotIo.readString(in));
    }
}
