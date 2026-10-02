package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

public class OperatorCodecTest {

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        OperatorRecord operator = DistinctRecords.of(OperatorRecord.class);

        assertThat(roundTrip(operator)).isEqualTo(operator);
    }

    @Test
    public void nullFieldsSurviveTheSnapshot() throws Exception {
        OperatorRecord operator = new OperatorRecord("RUT:Operator:1", null);

        assertThat(roundTrip(operator)).isEqualTo(operator);
    }

    @Test
    public void readsTheOperatorsOwnNameFromNetex() throws Exception {
        XMLStreamReader r = at("""
                <Operator xmlns="http://www.netex.org.uk/netex" id="RUT:Operator:1" version="1">
                  <CompanyNumber>123</CompanyNumber>
                  <Name>Ruter</Name>
                  <LegalName>Ruter AS</LegalName>
                  <ContactDetails><Name>Customer service</Name></ContactDetails>
                </Operator>""");

        assertThat(OperatorCodec.read(r)).isEqualTo(new OperatorRecord("RUT:Operator:1", "Ruter"));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("Operator");
    }

    @Test
    public void anOperatorWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<Operator xmlns=\"http://www.netex.org.uk/netex\"><Name>Ruter</Name></Operator>");

        assertThat(OperatorCodec.read(r)).isNull();
    }

    private static OperatorRecord roundTrip(OperatorRecord operator) throws Exception {
        return CodecFixtures.roundTrip(operator, OperatorCodec::intern, OperatorCodec::write, OperatorCodec::read);
    }
}
