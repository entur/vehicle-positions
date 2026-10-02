package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.planned.PlannedDataSink.StopDestinationDisplay;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

public class JourneyPatternCodecTest {

    private static final List<String> NO_IDS = List.of();

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        JourneyPatternRecord pattern = DistinctRecords.of(JourneyPatternRecord.class);

        assertThat(roundTrip(pattern, NO_IDS, NO_IDS).read()).isEqualTo(pattern);
    }

    @Test
    public void refsIntoTheirSectionsAreWrittenByPosition() throws Exception {
        JourneyPatternRecord pattern = new JourneyPatternRecord("RUT:JourneyPattern:1",
                new String[]{"RUT:ServiceLink:1", "RUT:ServiceLink:2"},
                new StopDestinationDisplay[]{new StopDestinationDisplay(1, "RUT:DestinationDisplay:1")});
        List<String> links = List.of("RUT:ServiceLink:1", "RUT:ServiceLink:2");
        List<String> displays = List.of("RUT:DestinationDisplay:1");

        CodecFixtures.Written<JourneyPatternRecord> resolved = roundTrip(pattern, links, displays);
        CodecFixtures.Written<JourneyPatternRecord> dangling = roundTrip(pattern, NO_IDS, NO_IDS);

        assertThat(resolved.read()).isEqualTo(pattern);
        assertThat(resolved.recordBytes()).isLessThan(dangling.recordBytes());
    }

    @Test
    public void aPatternWithoutLinksOrDisplaysSurvivesTheSnapshot() throws Exception {
        JourneyPatternRecord pattern = new JourneyPatternRecord("RUT:JourneyPattern:1", null, null);

        assertThat(pattern.serviceLinkIds()).isEmpty();
        assertThat(pattern.destinationDisplays()).isEmpty();
        assertThat(roundTrip(pattern, NO_IDS, NO_IDS).read()).isEqualTo(pattern);
    }

    /** Record equality on an array is by reference unless overridden; every equality check here relies on content. */
    @Test
    public void recordsCompareLinksAndDisplaysByContent() {
        JourneyPatternRecord pattern = new JourneyPatternRecord("RUT:JourneyPattern:1",
                new String[]{"RUT:ServiceLink:1"}, new StopDestinationDisplay[]{new StopDestinationDisplay(1, "A")});

        assertThat(pattern)
                .isEqualTo(new JourneyPatternRecord("RUT:JourneyPattern:1",
                        new String[]{"RUT:ServiceLink:1"}, new StopDestinationDisplay[]{new StopDestinationDisplay(1, "A")}))
                .hasSameHashCodeAs(new JourneyPatternRecord("RUT:JourneyPattern:1",
                        new String[]{"RUT:ServiceLink:1"}, new StopDestinationDisplay[]{new StopDestinationDisplay(1, "A")}))
                .isNotEqualTo(new JourneyPatternRecord("RUT:JourneyPattern:1",
                        new String[]{"RUT:ServiceLink:1"}, new StopDestinationDisplay[]{new StopDestinationDisplay(2, "A")}));
    }

    /** Most producers repeat the display on every stop; only the stops where it changes are kept. */
    @Test
    public void keepsOnlyTheStopsWhereTheDisplayChanges() {
        JourneyPatternRecord pattern = new JourneyPatternRecord("RUT:JourneyPattern:1", new String[0],
                new StopDestinationDisplay[]{stop(1, "A"), stop(2, "A"), stop(3, "B"), stop(4, "B"), stop(5, "A")});

        assertThat(pattern.destinationDisplays()).containsExactly(stop(1, "A"), stop(3, "B"), stop(5, "A"));
    }

    @Test
    public void readsLinksAndStopDisplaysFromNetex() throws Exception {
        XMLStreamReader r = at("""
                <ServiceJourneyPattern xmlns="http://www.netex.org.uk/netex" id="RUT:ServiceJourneyPattern:1" version="1">
                  <RouteRef ref="RUT:Route:1" version="1"/>
                  <pointsInSequence>
                    <StopPointInJourneyPattern id="RUT:StopPointInJourneyPattern:1" order="1" version="1">
                      <ScheduledStopPointRef ref="RUT:ScheduledStopPoint:1"/>
                      <DestinationDisplayRef ref="RUT:DestinationDisplay:1"/>
                    </StopPointInJourneyPattern>
                    <StopPointInJourneyPattern id="RUT:StopPointInJourneyPattern:2" order="2" version="1">
                      <ScheduledStopPointRef ref="RUT:ScheduledStopPoint:2"/>
                      <DestinationDisplayRef ref="RUT:DestinationDisplay:1"/>
                    </StopPointInJourneyPattern>
                    <StopPointInJourneyPattern id="RUT:StopPointInJourneyPattern:3" order="7" version="1">
                      <ScheduledStopPointRef ref="RUT:ScheduledStopPoint:3"/>
                      <DestinationDisplayRef ref="RUT:DestinationDisplay:2"/>
                    </StopPointInJourneyPattern>
                  </pointsInSequence>
                  <linksInSequence>
                    <ServiceLinkInJourneyPattern id="RUT:ServiceLinkInJourneyPattern:1" order="1" version="1">
                      <ServiceLinkRef ref="RUT:ServiceLink:1" version="1"/>
                    </ServiceLinkInJourneyPattern>
                    <ServiceLinkInJourneyPattern id="RUT:ServiceLinkInJourneyPattern:2" order="2" version="1">
                      <ServiceLinkRef ref="RUT:ServiceLink:2" version="1"/>
                    </ServiceLinkInJourneyPattern>
                  </linksInSequence>
                </ServiceJourneyPattern>""");

        assertThat(JourneyPatternCodec.read(r)).isEqualTo(new JourneyPatternRecord("RUT:ServiceJourneyPattern:1",
                new String[]{"RUT:ServiceLink:1", "RUT:ServiceLink:2"},
                new StopDestinationDisplay[]{stop(1, "RUT:DestinationDisplay:1"), stop(7, "RUT:DestinationDisplay:2")}));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("ServiceJourneyPattern");
    }

    @Test
    public void aStopWithoutAnOrderTakesItsPositionInTheSequence() throws Exception {
        XMLStreamReader r = at("""
                <JourneyPattern xmlns="http://www.netex.org.uk/netex" id="RUT:JourneyPattern:1">
                  <pointsInSequence>
                    <StopPointInJourneyPattern id="RUT:StopPointInJourneyPattern:1"/>
                    <StopPointInJourneyPattern id="RUT:StopPointInJourneyPattern:2">
                      <DestinationDisplayRef ref="RUT:DestinationDisplay:1"/>
                    </StopPointInJourneyPattern>
                  </pointsInSequence>
                </JourneyPattern>""");

        assertThat(JourneyPatternCodec.read(r).destinationDisplays()).containsExactly(stop(2, "RUT:DestinationDisplay:1"));
    }

    @Test
    public void aPatternWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<JourneyPattern xmlns=\"http://www.netex.org.uk/netex\"/>");

        assertThat(JourneyPatternCodec.read(r)).isNull();
    }

    private static StopDestinationDisplay stop(int order, String destinationDisplayId) {
        return new StopDestinationDisplay(order, destinationDisplayId);
    }

    private static CodecFixtures.Written<JourneyPatternRecord> roundTrip(JourneyPatternRecord pattern,
                                                                        List<String> links, List<String> displays) throws Exception {
        return CodecFixtures.roundTrip(pattern, JourneyPatternCodec::intern, JourneyPatternCodec::write,
                JourneyPatternCodec::read, links, displays);
    }
}
