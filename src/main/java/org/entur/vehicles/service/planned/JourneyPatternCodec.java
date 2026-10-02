package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.planned.PlannedDataSink.StopDestinationDisplay;
import org.entur.vehicles.service.snapshot.IdCodec;
import org.entur.vehicles.service.snapshot.SnapshotIo;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.entur.vehicles.service.planned.Stax.id;
import static org.entur.vehicles.service.planned.Stax.order;
import static org.entur.vehicles.service.planned.Stax.ref;
import static org.entur.vehicles.service.planned.Stax.scan;

/**
 * Everything that knows the fields of a {@link JourneyPatternRecord}: how to read one from
 * NeTEx and how to write and read its snapshot record. A new field touches only the record and
 * this class, plus a {@link PlannedDataSnapshot#FORMAT_VERSION} bump.
 */
final class JourneyPatternCodec {

    private JourneyPatternCodec() {
    }

    /**
     * Reads a {@code JourneyPattern} or {@code ServiceJourneyPattern} from its START_ELEMENT to
     * its END_ELEMENT. Null when the element has no id.
     */
    static JourneyPatternRecord read(XMLStreamReader r) throws XMLStreamException {
        String id = id(r);
        List<String> links = new ArrayList<>();
        List<StopDestinationDisplay> destinationDisplays = new ArrayList<>();
        String[] point = new String[1]; // the pointsInSequence entry the scan is currently inside
        int[] stop = new int[2]; // order of the current StopPointInJourneyPattern, stops seen so far
        scan(r, (reader, localName, depth) -> {
            if (depth == 2) {
                point[0] = localName;
                if (localName.equals("StopPointInJourneyPattern")) {
                    stop[1]++;
                    stop[0] = order(reader, stop[1]);
                }
            }
            if (localName.equals("ServiceLinkRef")) {
                String ref = ref(reader);
                if (ref != null) {
                    links.add(ref);
                }
            } else if (depth == 3 && localName.equals("DestinationDisplayRef")
                    && "StopPointInJourneyPattern".equals(point[0])) {
                String ref = ref(reader);
                if (ref != null) {
                    destinationDisplays.add(new StopDestinationDisplay(stop[0], ref));
                }
            }
            return false;
        });
        return id == null ? null : new JourneyPatternRecord(id, links.toArray(new String[0]),
                destinationDisplays.toArray(new StopDestinationDisplay[0]));
    }

    /** Registers every id the record will write, before the prefix table is written. */
    static void intern(IdCodec.Writer ids, JourneyPatternRecord pattern) {
        ids.intern(pattern.id());
        for (String linkId : pattern.serviceLinkIds()) {
            ids.intern(linkId);
        }
        for (StopDestinationDisplay display : pattern.destinationDisplays()) {
            ids.intern(display.destinationDisplayId());
        }
    }

    static void write(DataOutputStream out, IdCodec.Writer ids, JourneyPatternRecord pattern,
                      SectionIndex.Writer links, SectionIndex.Writer displays) throws IOException {
        ids.writeId(out, pattern.id());
        SnapshotIo.writeVarInt(out, pattern.serviceLinkIds().length);
        for (String linkId : pattern.serviceLinkIds()) {
            links.writeRef(out, ids, linkId);
        }
        SnapshotIo.writeVarInt(out, pattern.destinationDisplays().length);
        for (StopDestinationDisplay display : pattern.destinationDisplays()) {
            SnapshotIo.writeZigZag(out, display.order());
            displays.writeRef(out, ids, display.destinationDisplayId());
        }
    }

    static JourneyPatternRecord read(DataInputStream in, IdCodec.Reader ids,
                                     SectionIndex.Reader links, SectionIndex.Reader displays) throws IOException {
        String id = ids.readId(in);
        String[] linkIds = new String[(int) SnapshotIo.readVarInt(in)];
        for (int i = 0; i < linkIds.length; i++) {
            linkIds[i] = links.readRef(in, ids);
        }
        StopDestinationDisplay[] stopDisplays = new StopDestinationDisplay[(int) SnapshotIo.readVarInt(in)];
        for (int i = 0; i < stopDisplays.length; i++) {
            int order = (int) SnapshotIo.readZigZag(in);
            stopDisplays[i] = new StopDestinationDisplay(order, displays.readRef(in, ids));
        }
        return new JourneyPatternRecord(id, linkIds, stopDisplays);
    }
}
