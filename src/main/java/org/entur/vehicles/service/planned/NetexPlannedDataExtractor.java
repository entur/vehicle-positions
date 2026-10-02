package org.entur.vehicles.service.planned;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.entur.vehicles.service.planned.Stax.id;
import static org.entur.vehicles.service.planned.Stax.order;
import static org.entur.vehicles.service.planned.Stax.ref;
import static org.entur.vehicles.service.planned.Stax.scan;

/**
 * One StAX pass over a NeTEx XML stream, feeding the eight element types the service needs
 * into a {@link PlannedDataSink}. Everything else is skipped at the token level, so memory
 * is bounded by what is kept, not by the size of the file.
 * <p>
 * Each handled element is read by a method that consumes exactly that element (from its
 * START_ELEMENT to its END_ELEMENT) and only looks at the children it needs, tracking depth
 * so a nested {@code <Name>} several levels down never masquerades as the element's own.
 */
public final class NetexPlannedDataExtractor {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    static {
        FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        FACTORY.setProperty(XMLInputFactory.IS_COALESCING, true);
        FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    }

    public void extract(InputStream in, PlannedDataSink sink) throws XMLStreamException {
        XMLStreamReader r = FACTORY.createXMLStreamReader(in);
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "Operator" -> readOperator(r, sink);
                    case "Line", "FlexibleLine" -> readLine(r, sink);
                    case "ServiceLink" -> readServiceLink(r, sink);
                    case "JourneyPattern", "ServiceJourneyPattern" -> readJourneyPattern(r, sink);
                    case "DestinationDisplay" -> readDestinationDisplay(r, sink);
                    case "ServiceJourney" -> readServiceJourney(r, sink);
                    case "DatedServiceJourney" -> readDatedServiceJourney(r, sink);
                    case "OperatingDay" -> readOperatingDay(r, sink);
                    default -> { /* skip */ }
                }
            }
        } finally {
            r.close();
        }
    }

    private void readOperator(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        String id = id(r);
        String[] name = new String[1];
        scan(r, (reader, localName, depth) -> {
            if (depth == 1 && localName.equals("Name")) {
                name[0] = reader.getElementText();
                return true;
            }
            return false;
        });
        if (id != null) {
            sink.addOperator(id, name[0]);
        }
    }

    private void readLine(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        LineRecord line = LineCodec.read(r);
        if (line != null) {
            sink.addLine(line);
        }
    }

    private void readServiceLink(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        String id = id(r);
        int[][] geometry = new int[1][];
        scan(r, (reader, localName, depth) -> {
            if (localName.equals("posList")) {
                geometry[0] = PosListParser.parse(reader.getElementText());
                return true;
            }
            return false;
        });
        if (id != null) {
            sink.addServiceLink(id, geometry[0]);
        }
    }

    private void readJourneyPattern(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        String id = id(r);
        List<String> links = new ArrayList<>();
        List<PlannedDataSink.StopDestinationDisplay> destinationDisplays = new ArrayList<>();
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
                    destinationDisplays.add(new PlannedDataSink.StopDestinationDisplay(stop[0], ref));
                }
            }
            return false;
        });
        if (id != null) {
            sink.addJourneyPattern(id, links, destinationDisplays);
        }
    }

    private void readDestinationDisplay(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        String id = id(r);
        String[] frontText = new String[1];
        scan(r, (reader, localName, depth) -> {
            // Variants carry a FrontText of their own further down; only the display's counts.
            if (depth == 1 && localName.equals("FrontText")) {
                frontText[0] = reader.getElementText();
                return true;
            }
            return false;
        });
        if (id != null) {
            sink.addDestinationDisplay(id, frontText[0]);
        }
    }

    private void readServiceJourney(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        String id = id(r);
        String[] refs = new String[3]; // journeyPatternId, lineId, transportMode
        scan(r, (reader, localName, depth) -> {
            if (depth != 1) {
                return false;
            }
            switch (localName) {
                // Optional; when set it takes precedence over the line's, e.g. a replacement bus.
                case "TransportMode" -> { refs[2] = reader.getElementText(); return true; }
                case "JourneyPatternRef" -> refs[0] = ref(reader);
                // Only the journey's own line ref: Route elements carry a LineRef too, but
                // they are never nested inside a ServiceJourney, and depth 1 excludes them anyway.
                case "LineRef", "FlexibleLineRef" -> refs[1] = ref(reader);
                default -> { /* ignore */ }
            }
            return false;
        });
        if (id != null) {
            sink.addServiceJourney(id, refs[0], refs[1], refs[2]);
        }
    }

    private void readDatedServiceJourney(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
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
        if (id != null) {
            sink.addDatedServiceJourney(id, refs[0], refs[1]);
        }
    }

    private void readOperatingDay(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        String id = id(r);
        String[] date = new String[1];
        scan(r, (reader, localName, depth) -> {
            if (depth == 1 && localName.equals("CalendarDate")) {
                date[0] = reader.getElementText();
                return true;
            }
            return false;
        });
        if (id != null) {
            sink.addOperatingDay(id, date[0]);
        }
    }
}
