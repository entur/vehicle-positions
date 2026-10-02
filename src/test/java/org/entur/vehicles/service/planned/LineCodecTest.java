package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.IdCodec;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

public class LineCodecTest {

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        LineRecord line = DistinctRecords.of(LineRecord.class);

        assertThat(roundTrip(line)).isEqualTo(line);
    }

    @Test
    public void nullFieldsSurviveTheSnapshot() throws Exception {
        LineRecord line = new LineRecord("RUT:Line:1", null, null, null, null, null);

        assertThat(roundTrip(line)).isEqualTo(line);
    }

    @Test
    public void readsEveryFieldFromNetex() throws Exception {
        XMLStreamReader r = at("""
                <Line xmlns="http://www.netex.org.uk/netex" id="RUT:Line:1" version="1">
                  <Name>Ring</Name>
                  <TransportMode>bus</TransportMode>
                  <PublicCode>31</PublicCode>
                  <Presentation><Colour>76A300</Colour><TextColour>FFFFFF</TextColour></Presentation>
                  <AlternativePresentation><Colour>000000</Colour><TextColour>000000</TextColour></AlternativePresentation>
                </Line>""");

        assertThat(LineCodec.read(r)).isEqualTo(new LineRecord("RUT:Line:1", "Ring", "31", "76A300", "FFFFFF", "bus"));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("Line");
    }

    @Test
    public void aLineWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<Line xmlns=\"http://www.netex.org.uk/netex\"><Name>Ring</Name></Line>");

        assertThat(LineCodec.read(r)).isNull();
    }

    private static LineRecord roundTrip(LineRecord line) throws Exception {
        IdCodec.Writer ids = new IdCodec.Writer();
        ids.intern(line.id());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            ids.writeTable(out);
            LineCodec.write(out, ids, line);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            IdCodec.Reader readIds = new IdCodec.Reader();
            readIds.readTable(in);
            LineRecord read = LineCodec.read(in, readIds);
            assertThat(in.available()).as("bytes left after the record").isZero();
            return read;
        }
    }

    /** A reader positioned on the document's root START_ELEMENT. */
    private static XMLStreamReader at(String xml) throws Exception {
        XMLStreamReader r = XMLInputFactory.newFactory().createXMLStreamReader(new StringReader(xml));
        r.nextTag();
        return r;
    }
}
