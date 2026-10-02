package org.entur.vehicles.service.planned;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** The StAX walking helpers shared by {@link NetexPlannedDataExtractor} and the element codecs. */
final class Stax {

    private Stax() {
    }

    /**
     * Invoked at every START_ELEMENT below the element being read, with the depth relative
     * to it (direct children are depth 1). Return true if the handler consumed the child
     * (i.e. called {@code getElementText()}, which leaves the reader on the child's
     * END_ELEMENT); return false if the reader is still positioned on the START_ELEMENT.
     */
    @FunctionalInterface
    interface ChildHandler {
        boolean handle(XMLStreamReader reader, String localName, int depth) throws XMLStreamException;
    }

    /**
     * Walks from the current START_ELEMENT to its matching END_ELEMENT, calling the handler
     * for every nested START_ELEMENT. Leaves the reader on the matching END_ELEMENT.
     */
    static void scan(XMLStreamReader r, ChildHandler handler) throws XMLStreamException {
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

    static String id(XMLStreamReader r) {
        return r.getAttributeValue(null, "id");
    }

    static String ref(XMLStreamReader r) {
        return r.getAttributeValue(null, "ref");
    }

    /** The element's {@code order} attribute, or its position in the sequence when that is absent or not a number. */
    static int order(XMLStreamReader r, int position) {
        String order = r.getAttributeValue(null, "order");
        if (order != null) {
            try {
                return Integer.parseInt(order.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return position;
    }
}
