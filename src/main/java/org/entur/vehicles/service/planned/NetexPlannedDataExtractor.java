package org.entur.vehicles.service.planned;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;

/**
 * One StAX pass over a NeTEx XML stream, feeding the eight element types the service needs
 * into a {@link PlannedDataSink}. Everything else is skipped at the token level, so memory
 * is bounded by what is kept, not by the size of the file.
 * <p>
 * Each handled element is read by its codec (e.g. {@link LineCodec}), which consumes exactly
 * that element (from its START_ELEMENT to its END_ELEMENT) and only looks at the children it
 * needs, tracking depth so a nested {@code <Name>} several levels down never masquerades as
 * the element's own.
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
        OperatorRecord operator = OperatorCodec.read(r);
        if (operator != null) {
            sink.addOperator(operator);
        }
    }

    private void readLine(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        LineRecord line = LineCodec.read(r);
        if (line != null) {
            sink.addLine(line);
        }
    }

    private void readServiceLink(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        ServiceLinkRecord link = ServiceLinkCodec.read(r);
        if (link != null) {
            sink.addServiceLink(link);
        }
    }

    private void readJourneyPattern(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        JourneyPatternRecord pattern = JourneyPatternCodec.read(r);
        if (pattern != null) {
            sink.addJourneyPattern(pattern);
        }
    }

    private void readDestinationDisplay(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        DestinationDisplayRecord display = DestinationDisplayCodec.read(r);
        if (display != null) {
            sink.addDestinationDisplay(display);
        }
    }

    private void readServiceJourney(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        ServiceJourneyRecord journey = ServiceJourneyCodec.read(r);
        if (journey != null) {
            sink.addServiceJourney(journey);
        }
    }

    private void readDatedServiceJourney(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        DatedServiceJourneyRecord dated = DatedServiceJourneyCodec.read(r);
        if (dated != null) {
            sink.addDatedServiceJourney(dated);
        }
    }

    private void readOperatingDay(XMLStreamReader r, PlannedDataSink sink) throws XMLStreamException {
        OperatingDayRecord day = OperatingDayCodec.read(r);
        if (day != null) {
            sink.addOperatingDay(day);
        }
    }
}
