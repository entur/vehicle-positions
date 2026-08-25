package org.entur.vehicles.service.planned;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One StAX pass over a NeTEx XML stream, keeping only the seven element types the service
 * needs. Everything else is skipped at the token level, so memory is bounded by what is
 * kept, not by the size of the file.
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

    public void extract(InputStream in, PlannedDataset.Builder builder) throws XMLStreamException {
        XMLStreamReader r = FACTORY.createXMLStreamReader(in);
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "Operator" -> readOperator(r, builder);
                    case "Line", "FlexibleLine" -> readLine(r, builder);
                    case "ServiceLink" -> readServiceLink(r, builder);
                    case "JourneyPattern", "ServiceJourneyPattern" -> readJourneyPattern(r, builder);
                    case "ServiceJourney" -> readServiceJourney(r, builder);
                    case "DatedServiceJourney" -> readDatedServiceJourney(r, builder);
                    case "OperatingDay" -> readOperatingDay(r, builder);
                    default -> { /* skip */ }
                }
            }
        } finally {
            r.close();
        }
    }

    private void readOperator(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
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
            builder.addOperator(id, name[0]);
        }
    }

    private void readLine(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        String[] fields = new String[2]; // name, publicCode
        scan(r, (reader, localName, depth) -> {
            if (depth != 1) {
                return false;
            }
            switch (localName) {
                case "Name" -> { fields[0] = reader.getElementText(); return true; }
                case "PublicCode" -> { fields[1] = reader.getElementText(); return true; }
                default -> { return false; }
            }
        });
        if (id != null) {
            builder.addLine(id, fields[0], fields[1]);
        }
    }

    private void readServiceLink(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
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
            builder.addServiceLink(id, geometry[0]);
        }
    }

    private void readJourneyPattern(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        List<String> links = new ArrayList<>();
        scan(r, (reader, localName, depth) -> {
            if (localName.equals("ServiceLinkRef")) {
                String ref = ref(reader);
                if (ref != null) {
                    links.add(ref);
                }
            }
            return false;
        });
        if (id != null) {
            builder.addJourneyPattern(id, links);
        }
    }

    private void readServiceJourney(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        String[] pattern = new String[1];
        scan(r, (reader, localName, depth) -> {
            if (depth == 1 && localName.equals("JourneyPatternRef")) {
                pattern[0] = ref(reader);
            }
            return false;
        });
        if (id != null) {
            builder.addServiceJourney(id, pattern[0]);
        }
    }

    private void readDatedServiceJourney(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
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
            builder.addDatedServiceJourney(id, refs[0], refs[1]);
        }
    }

    private void readOperatingDay(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
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
            builder.addOperatingDay(id, date[0]);
        }
    }

    /**
     * Invoked at every START_ELEMENT below the element being read, with the depth relative
     * to it (direct children are depth 1). Return true if the handler consumed the child
     * (i.e. called {@code getElementText()}, which leaves the reader on the child's
     * END_ELEMENT); return false if the reader is still positioned on the START_ELEMENT.
     */
    @FunctionalInterface
    private interface ChildHandler {
        boolean handle(XMLStreamReader reader, String localName, int depth) throws XMLStreamException;
    }

    /**
     * Walks from the current START_ELEMENT to its matching END_ELEMENT, calling the handler
     * for every nested START_ELEMENT. Leaves the reader on the matching END_ELEMENT.
     */
    private static void scan(XMLStreamReader r, ChildHandler handler) throws XMLStreamException {
        int depth = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (handler.handle(r, r.getLocalName(), depth)) {
                    depth--; // handler consumed through the child's END_ELEMENT
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) {
                    return;
                }
                depth--;
            }
        }
    }

    private static String id(XMLStreamReader r) {
        return r.getAttributeValue(null, "id");
    }

    private static String ref(XMLStreamReader r) {
        return r.getAttributeValue(null, "ref");
    }
}
