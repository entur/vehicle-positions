package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

public class ServiceLinkCodecTest {

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        ServiceLinkRecord link = DistinctRecords.of(ServiceLinkRecord.class);

        assertThat(roundTrip(link)).isEqualTo(link);
    }

    @Test
    public void negativeCoordinatesSurviveTheSnapshot() throws Exception {
        ServiceLinkRecord link = new ServiceLinkRecord("RUT:ServiceLink:1", new int[]{-33_868_820, 151_209_290, -33_870_000, 151_210_000});

        assertThat(roundTrip(link)).isEqualTo(link);
    }

    /** Record equality on an array is by reference unless overridden; every equality check here relies on content. */
    @Test
    public void recordsCompareGeometryByContent() {
        assertThat(new ServiceLinkRecord("RUT:ServiceLink:1", new int[]{1, 2}))
                .isEqualTo(new ServiceLinkRecord("RUT:ServiceLink:1", new int[]{1, 2}))
                .hasSameHashCodeAs(new ServiceLinkRecord("RUT:ServiceLink:1", new int[]{1, 2}))
                .isNotEqualTo(new ServiceLinkRecord("RUT:ServiceLink:1", new int[]{1, 3}));
    }

    @Test
    public void aLinkWithoutGeometryHoldsAnEmptyOne() throws Exception {
        ServiceLinkRecord link = new ServiceLinkRecord("RUT:ServiceLink:1", null);

        assertThat(link.geometry()).isEmpty();
        assertThat(roundTrip(link)).isEqualTo(link);
    }

    @Test
    public void readsTheProjectedGeometryFromNetex() throws Exception {
        XMLStreamReader r = at("""
                <ServiceLink xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2"
                             id="RUT:ServiceLink:1" version="1">
                  <FromPointRef ref="RUT:ScheduledStopPoint:1" version="1"/>
                  <ToPointRef ref="RUT:ScheduledStopPoint:2" version="1"/>
                  <projections>
                    <LinkSequenceProjection id="RUT:LinkSequenceProjection:1" version="1">
                      <gml:LineString gml:id="LS_1">
                        <gml:posList srsDimension="2" count="2">59.9 10.7 59.91 10.71</gml:posList>
                      </gml:LineString>
                    </LinkSequenceProjection>
                  </projections>
                </ServiceLink>""");

        assertThat(ServiceLinkCodec.read(r)).isEqualTo(new ServiceLinkRecord("RUT:ServiceLink:1",
                new int[]{59_900_000, 10_700_000, 59_910_000, 10_710_000}));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("ServiceLink");
    }

    @Test
    public void readsALinkWithoutAPosListAsEmptyGeometry() throws Exception {
        XMLStreamReader r = at("""
                <ServiceLink xmlns="http://www.netex.org.uk/netex" id="RUT:ServiceLink:1" version="1">
                  <FromPointRef ref="RUT:ScheduledStopPoint:1" version="1"/>
                </ServiceLink>""");

        assertThat(ServiceLinkCodec.read(r).geometry()).isEmpty();
    }

    @Test
    public void aLinkWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<ServiceLink xmlns=\"http://www.netex.org.uk/netex\"/>");

        assertThat(ServiceLinkCodec.read(r)).isNull();
    }

    private static ServiceLinkRecord roundTrip(ServiceLinkRecord link) throws Exception {
        return CodecFixtures.roundTrip(link, ServiceLinkCodec::intern, ServiceLinkCodec::write, ServiceLinkCodec::read);
    }
}
