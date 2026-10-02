package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

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
        return CodecFixtures.roundTrip(line, LineCodec::intern, LineCodec::write, LineCodec::read);
    }
}
