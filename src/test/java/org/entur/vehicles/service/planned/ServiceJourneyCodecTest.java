package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

public class ServiceJourneyCodecTest {

    private static final List<String> NO_IDS = List.of();

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        ServiceJourneyRecord journey = DistinctRecords.of(ServiceJourneyRecord.class);

        assertThat(roundTrip(journey, NO_IDS, NO_IDS).read()).isEqualTo(journey);
    }

    @Test
    public void nullRefsSurviveTheSnapshot() throws Exception {
        ServiceJourneyRecord journey = new ServiceJourneyRecord("RUT:ServiceJourney:1", null, null, null);

        assertThat(roundTrip(journey, NO_IDS, NO_IDS).read()).isEqualTo(journey);
    }

    @Test
    public void refsIntoTheirSectionsAreWrittenByPosition() throws Exception {
        ServiceJourneyRecord journey = new ServiceJourneyRecord(
                "RUT:ServiceJourney:1", "RUT:JourneyPattern:2", "RUT:Line:3", "bus");
        List<String> patterns = List.of("RUT:JourneyPattern:1", "RUT:JourneyPattern:2");
        List<String> lines = List.of("RUT:Line:3");

        CodecFixtures.Written<ServiceJourneyRecord> resolved = roundTrip(journey, patterns, lines);
        CodecFixtures.Written<ServiceJourneyRecord> dangling = roundTrip(journey, NO_IDS, NO_IDS);

        assertThat(resolved.read()).isEqualTo(journey);
        assertThat(resolved.recordBytes()).isLessThan(dangling.recordBytes());
    }

    @Test
    public void readsEveryFieldFromNetex() throws Exception {
        XMLStreamReader r = at("""
                <ServiceJourney xmlns="http://www.netex.org.uk/netex" id="RUT:ServiceJourney:1" version="1">
                  <TransportMode>bus</TransportMode>
                  <JourneyPatternRef ref="RUT:JourneyPattern:2" version="1"/>
                  <LineRef ref="RUT:Line:3" version="1"/>
                  <passingTimes>
                    <TimetabledPassingTime id="RUT:TimetabledPassingTime:1" version="1">
                      <StopPointInJourneyPatternRef ref="RUT:StopPointInJourneyPattern:1" version="1"/>
                      <DepartureTime>07:00:00</DepartureTime>
                    </TimetabledPassingTime>
                  </passingTimes>
                </ServiceJourney>""");

        assertThat(ServiceJourneyCodec.read(r)).isEqualTo(new ServiceJourneyRecord(
                "RUT:ServiceJourney:1", "RUT:JourneyPattern:2", "RUT:Line:3", "bus"));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("ServiceJourney");
    }

    @Test
    public void readsAFlexibleLineRef() throws Exception {
        XMLStreamReader r = at("""
                <ServiceJourney xmlns="http://www.netex.org.uk/netex" id="RUT:ServiceJourney:1">
                  <FlexibleLineRef ref="RUT:FlexibleLine:3"/>
                </ServiceJourney>""");

        assertThat(ServiceJourneyCodec.read(r).lineId()).isEqualTo("RUT:FlexibleLine:3");
    }

    @Test
    public void aJourneyWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<ServiceJourney xmlns=\"http://www.netex.org.uk/netex\"><LineRef ref=\"RUT:Line:3\"/></ServiceJourney>");

        assertThat(ServiceJourneyCodec.read(r)).isNull();
    }

    /** Hundreds of thousands of journeys carry a handful of modes; each must not keep its own copy. */
    @Test
    public void transportModesAreSharedOnBothReadPaths() throws Exception {
        String xml = "<ServiceJourney xmlns=\"http://www.netex.org.uk/netex\" id=\"RUT:ServiceJourney:%d\"><TransportMode>bus</TransportMode></ServiceJourney>";
        ServiceJourneyRecord first = ServiceJourneyCodec.read(at(xml.formatted(1)));
        ServiceJourneyRecord second = ServiceJourneyCodec.read(at(xml.formatted(2)));
        assertThat(second.transportMode()).isSameAs(first.transportMode());

        ServiceJourneyRecord replayed = roundTrip(new ServiceJourneyRecord("RUT:ServiceJourney:3", null, null, new String("bus")),
                NO_IDS, NO_IDS).read();
        assertThat(replayed.transportMode()).isSameAs(first.transportMode());
    }

    private static CodecFixtures.Written<ServiceJourneyRecord> roundTrip(ServiceJourneyRecord journey,
                                                                        List<String> patterns, List<String> lines) throws Exception {
        return CodecFixtures.roundTrip(journey, ServiceJourneyCodec::intern, ServiceJourneyCodec::write,
                ServiceJourneyCodec::read, patterns, lines);
    }
}
