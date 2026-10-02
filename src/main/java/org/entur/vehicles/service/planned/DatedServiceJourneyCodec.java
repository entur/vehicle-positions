package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.IdCodec;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.entur.vehicles.service.planned.Stax.id;
import static org.entur.vehicles.service.planned.Stax.ref;
import static org.entur.vehicles.service.planned.Stax.scan;

/**
 * Everything that knows the fields of a {@link DatedServiceJourneyRecord}: how to read one
 * from NeTEx and how to write and read its snapshot record. A new field touches only the record
 * and this class, plus a {@link PlannedDataSnapshot#FORMAT_VERSION} bump.
 */
final class DatedServiceJourneyCodec {

    private DatedServiceJourneyCodec() {
    }

    /**
     * Reads a {@code DatedServiceJourney} from its START_ELEMENT to its END_ELEMENT. Null when
     * the element has no id.
     */
    static DatedServiceJourneyRecord read(XMLStreamReader r) throws XMLStreamException {
        String id = id(r);
        String[] refs = new String[2]; // serviceJourneyId, operatingDayId
        scan(r, (reader, localName, depth) -> {
            if (depth != 1) {
                return false;
            }
            switch (localName) {
                case "ServiceJourneyRef" -> refs[0] = ref(reader);
                case "OperatingDayRef" -> refs[1] = ref(reader);
                default -> { /* DatedServiceJourneyRef and others are ignored */ }
            }
            return false;
        });
        return id == null ? null : new DatedServiceJourneyRecord(id, refs[0], refs[1]);
    }

    /** Registers every id the record will write, before the prefix table is written. */
    static void intern(IdCodec.Writer ids, DatedServiceJourneyRecord dated) {
        ids.intern(dated.id());
        if (dated.serviceJourneyId() != null) {
            ids.intern(dated.serviceJourneyId());
        }
        if (dated.operatingDayId() != null) {
            ids.intern(dated.operatingDayId());
        }
    }

    static void write(DataOutputStream out, IdCodec.Writer ids, DatedServiceJourneyRecord dated,
                      SectionIndex.Writer journeys, SectionIndex.Writer operatingDays) throws IOException {
        ids.writeId(out, dated.id());
        journeys.writeRef(out, ids, dated.serviceJourneyId());
        operatingDays.writeRef(out, ids, dated.operatingDayId());
    }

    static DatedServiceJourneyRecord read(DataInputStream in, IdCodec.Reader ids,
                                          SectionIndex.Reader journeys, SectionIndex.Reader operatingDays) throws IOException {
        // Java evaluates arguments left to right, so this reads the fields in write order.
        return new DatedServiceJourneyRecord(ids.readId(in), journeys.readRef(in, ids), operatingDays.readRef(in, ids));
    }
}
