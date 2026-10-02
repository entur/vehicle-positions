package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

public class DestinationDisplayCodecTest {

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        DestinationDisplayRecord display = DistinctRecords.of(DestinationDisplayRecord.class);

        assertThat(roundTrip(display)).isEqualTo(display);
    }

    @Test
    public void nullFieldsSurviveTheSnapshot() throws Exception {
        DestinationDisplayRecord display = new DestinationDisplayRecord("RUT:DestinationDisplay:1", null);

        assertThat(roundTrip(display)).isEqualTo(display);
    }

    @Test
    public void readsTheDisplaysOwnFrontTextNotAVariants() throws Exception {
        XMLStreamReader r = at("""
                <DestinationDisplay xmlns="http://www.netex.org.uk/netex" id="RUT:DestinationDisplay:1" version="1">
                  <FrontText>Sentrum</FrontText>
                  <variants>
                    <DestinationDisplayVariant id="RUT:DestinationDisplayVariant:1" version="1">
                      <FrontText>Sentrum via Majorstuen</FrontText>
                    </DestinationDisplayVariant>
                  </variants>
                </DestinationDisplay>""");

        assertThat(DestinationDisplayCodec.read(r)).isEqualTo(new DestinationDisplayRecord("RUT:DestinationDisplay:1", "Sentrum"));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("DestinationDisplay");
    }

    @Test
    public void aDisplayWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<DestinationDisplay xmlns=\"http://www.netex.org.uk/netex\"><FrontText>Sentrum</FrontText></DestinationDisplay>");

        assertThat(DestinationDisplayCodec.read(r)).isNull();
    }

    private static DestinationDisplayRecord roundTrip(DestinationDisplayRecord display) throws Exception {
        return CodecFixtures.roundTrip(display, DestinationDisplayCodec::intern, DestinationDisplayCodec::write, DestinationDisplayCodec::read);
    }
}
