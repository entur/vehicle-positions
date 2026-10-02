package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.IdCodec;
import org.entur.vehicles.service.snapshot.SnapshotIo;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.entur.vehicles.service.planned.Stax.id;
import static org.entur.vehicles.service.planned.Stax.ref;
import static org.entur.vehicles.service.planned.Stax.scan;

/**
 * Everything that knows the fields of a {@link ServiceJourneyRecord}: how to read one from
 * NeTEx and how to write and read its snapshot record. A new field touches only the record and
 * this class, plus a {@link PlannedDataSnapshot#FORMAT_VERSION} bump.
 */
final class ServiceJourneyCodec {

    private ServiceJourneyCodec() {
    }

    /**
     * Reads a {@code ServiceJourney} from its START_ELEMENT to its END_ELEMENT. Null when the
     * element has no id.
     */
    static ServiceJourneyRecord read(XMLStreamReader r) throws XMLStreamException {
        String id = id(r);
        Fields f = new Fields();
        scan(r, (reader, localName, depth) -> {
            if (depth != 1) {
                return false;
            }
            switch (localName) {
                // Optional; when set it takes precedence over the line's, e.g. a replacement bus.
                case "TransportMode" -> { f.transportMode = reader.getElementText(); return true; }
                case "JourneyPatternRef" -> f.journeyPatternId = ref(reader);
                // Only the journey's own line ref: Route elements carry a LineRef too, but
                // they are never nested inside a ServiceJourney, and depth 1 excludes them anyway.
                case "LineRef", "FlexibleLineRef" -> f.lineId = ref(reader);
                default -> { /* ignore */ }
            }
            return false;
        });
        return id == null ? null : new ServiceJourneyRecord(id, f.journeyPatternId, f.lineId, shared(f.transportMode));
    }

    /** Registers every id the record will write, before the prefix table is written. */
    static void intern(IdCodec.Writer ids, ServiceJourneyRecord journey) {
        ids.intern(journey.id());
        if (journey.journeyPatternId() != null) {
            ids.intern(journey.journeyPatternId());
        }
        if (journey.lineId() != null) {
            ids.intern(journey.lineId());
        }
    }

    static void write(DataOutputStream out, IdCodec.Writer ids, ServiceJourneyRecord journey,
                      SectionIndex.Writer patterns, SectionIndex.Writer lines) throws IOException {
        ids.writeId(out, journey.id());
        patterns.writeRef(out, ids, journey.journeyPatternId());
        lines.writeRef(out, ids, journey.lineId());
        SnapshotIo.writeString(out, journey.transportMode());
    }

    static ServiceJourneyRecord read(DataInputStream in, IdCodec.Reader ids,
                                     SectionIndex.Reader patterns, SectionIndex.Reader lines) throws IOException {
        // Java evaluates arguments left to right, so this reads the fields in write order.
        return new ServiceJourneyRecord(ids.readId(in), patterns.readRef(in, ids), lines.readRef(in, ids),
                shared(SnapshotIo.readString(in)));
    }

    /** A handful of distinct modes across hundreds of thousands of journeys; share one instance each. */
    private static String shared(String transportMode) {
        return transportMode == null ? null : transportMode.intern();
    }

    /** Named slots the scan lambda can assign. */
    private static final class Fields {
        String journeyPatternId;
        String lineId;
        String transportMode;
    }
}
