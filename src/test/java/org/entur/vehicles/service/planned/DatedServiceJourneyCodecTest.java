package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

public class DatedServiceJourneyCodecTest {

    private static final List<String> NO_IDS = List.of();

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        DatedServiceJourneyRecord dated = DistinctRecords.of(DatedServiceJourneyRecord.class);

        assertThat(roundTrip(dated, NO_IDS, NO_IDS).read()).isEqualTo(dated);
    }

    @Test
    public void nullRefsSurviveTheSnapshot() throws Exception {
        DatedServiceJourneyRecord dated = new DatedServiceJourneyRecord("RUT:DatedServiceJourney:1", null, null);

        assertThat(roundTrip(dated, NO_IDS, NO_IDS).read()).isEqualTo(dated);
    }

    @Test
    public void refsIntoTheirSectionsAreWrittenByPosition() throws Exception {
        DatedServiceJourneyRecord dated = new DatedServiceJourneyRecord(
                "RUT:DatedServiceJourney:1", "RUT:ServiceJourney:2", "RUT:OperatingDay:3");
        List<String> journeys = List.of("RUT:ServiceJourney:1", "RUT:ServiceJourney:2");
        List<String> days = List.of("RUT:OperatingDay:3");

        CodecFixtures.Written<DatedServiceJourneyRecord> resolved = roundTrip(dated, journeys, days);
        CodecFixtures.Written<DatedServiceJourneyRecord> dangling = roundTrip(dated, NO_IDS, NO_IDS);

        assertThat(resolved.read()).isEqualTo(dated);
        assertThat(resolved.recordBytes()).isLessThan(dangling.recordBytes());
    }

    @Test
    public void readsTheJourneyAndDayFromNetexNotTheReplacedJourney() throws Exception {
        XMLStreamReader r = at("""
                <DatedServiceJourney xmlns="http://www.netex.org.uk/netex" id="RUT:DatedServiceJourney:1" version="1">
                  <ServiceJourneyRef ref="RUT:ServiceJourney:2" version="1"/>
                  <OperatingDayRef ref="RUT:OperatingDay:3"/>
                  <DatedServiceJourneyRef ref="RUT:DatedServiceJourney:0" version="1"/>
                </DatedServiceJourney>""");

        assertThat(DatedServiceJourneyCodec.read(r)).isEqualTo(new DatedServiceJourneyRecord(
                "RUT:DatedServiceJourney:1", "RUT:ServiceJourney:2", "RUT:OperatingDay:3"));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("DatedServiceJourney");
    }

    @Test
    public void aDatedJourneyWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<DatedServiceJourney xmlns=\"http://www.netex.org.uk/netex\"><ServiceJourneyRef ref=\"RUT:ServiceJourney:2\"/></DatedServiceJourney>");

        assertThat(DatedServiceJourneyCodec.read(r)).isNull();
    }

    private static CodecFixtures.Written<DatedServiceJourneyRecord> roundTrip(DatedServiceJourneyRecord dated,
                                                                             List<String> journeys, List<String> days) throws Exception {
        return CodecFixtures.roundTrip(dated, DatedServiceJourneyCodec::intern, DatedServiceJourneyCodec::write,
                DatedServiceJourneyCodec::read, journeys, days);
    }
}
