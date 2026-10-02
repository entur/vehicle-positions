package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entur.vehicles.service.planned.CodecFixtures.at;

public class OperatingDayCodecTest {

    @Test
    public void everyFieldSurvivesTheSnapshot() throws Exception {
        OperatingDayRecord day = DistinctRecords.of(OperatingDayRecord.class);

        assertThat(roundTrip(day)).isEqualTo(day);
    }

    @Test
    public void nullFieldsSurviveTheSnapshot() throws Exception {
        OperatingDayRecord day = new OperatingDayRecord("RUT:OperatingDay:1", null);

        assertThat(roundTrip(day)).isEqualTo(day);
    }

    @Test
    public void readsTheCalendarDateFromNetex() throws Exception {
        XMLStreamReader r = at("""
                <OperatingDay xmlns="http://www.netex.org.uk/netex" id="RUT:OperatingDay:2026-10-02" version="1">
                  <CalendarDate>2026-10-02</CalendarDate>
                  <DayType>weekday</DayType>
                </OperatingDay>""");

        assertThat(OperatingDayCodec.read(r)).isEqualTo(new OperatingDayRecord("RUT:OperatingDay:2026-10-02", "2026-10-02"));
        assertThat(r.getEventType()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(r.getLocalName()).isEqualTo("OperatingDay");
    }

    @Test
    public void anOperatingDayWithoutAnIdReadsAsNull() throws Exception {
        XMLStreamReader r = at("<OperatingDay xmlns=\"http://www.netex.org.uk/netex\"><CalendarDate>2026-10-02</CalendarDate></OperatingDay>");

        assertThat(OperatingDayCodec.read(r)).isNull();
    }

    private static OperatingDayRecord roundTrip(OperatingDayRecord day) throws Exception {
        return CodecFixtures.roundTrip(day, OperatingDayCodec::intern, OperatingDayCodec::write, OperatingDayCodec::read);
    }
}
