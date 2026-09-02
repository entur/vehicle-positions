package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IdCodecTest {

    /**
     * Interns every id, writes the table then every id, and returns a Reader
     * primed with the table so ids can be read back in the same order.
     */
    private Reader roundTripSetup(List<String> ids) throws Exception {
        IdCodec.Writer writer = new IdCodec.Writer();
        for (String id : ids) {
            writer.intern(id);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.writeTable(out);
            for (String id : ids) {
                writer.writeId(out, id);
            }
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        IdCodec.Reader reader = new IdCodec.Reader();
        reader.readTable(in);
        return new Reader(in, reader);
    }

    private record Reader(DataInputStream in, IdCodec.Reader reader) {
        String next() throws Exception {
            return reader.readId(in);
        }
    }

    @Test
    void roundTripsDatedServiceJourneyDjjHexId() throws Exception {
        String id = "RUT:DatedServiceJourney:djj-0933f40da2fc97b867577a5489601564";
        Reader reader = roundTripSetup(List.of(id));
        assertThat(reader.next()).isEqualTo(id);
    }

    @Test
    void roundTripsServiceJourneyIdWithUnderscores() throws Exception {
        String id = "ATB:ServiceJourney:18_251215112551387_10";
        Reader reader = roundTripSetup(List.of(id));
        assertThat(reader.next()).isEqualTo(id);
    }

    @Test
    void roundTripsQuayIdWithPlainDigits() throws Exception {
        String id = "NSR:Quay:20388";
        Reader reader = roundTripSetup(List.of(id));
        assertThat(reader.next()).isEqualTo(id);
    }

    @Test
    void roundTripsServiceLinkUuid() throws Exception {
        String id = "TTS:ServiceLink:75896eeb-9a40-4411-aaa9-b66353c8c2fd";
        Reader reader = roundTripSetup(List.of(id));
        assertThat(reader.next()).isEqualTo(id);
    }

    @Test
    void quayIdWithLeadingZeroTakesKindZeroNotFour() throws Exception {
        String id = "NSR:Quay:007";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IdCodec.Writer writer = new IdCodec.Writer();
        writer.intern(id);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.writeTable(out);
            writer.writeId(out, id);
        }
        // Peek at the raw bytes: after the table (1 prefix entry) and the varint prefix
        // index, the next byte is the kind byte. It must be 0 (raw), not 4 (digits).
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        long tableCount = SnapshotIo.readVarInt(in);
        assertThat(tableCount).isEqualTo(1);
        SnapshotIo.readString(in); // the prefix itself
        SnapshotIo.readVarInt(in); // prefix index for this id
        int kind = in.readUnsignedByte();
        assertThat(kind).as("kind byte for a local part with a leading zero").isEqualTo(0);

        // And it must still round-trip correctly end to end.
        Reader reader = roundTripSetup(List.of(id));
        assertThat(reader.next()).isEqualTo(id);
    }

    @Test
    void roundTripsIdWithNoColon() throws Exception {
        String id = "no-colon-here";
        Reader reader = roundTripSetup(List.of(id));
        assertThat(reader.next()).isEqualTo(id);
    }

    @Test
    void tableHoldsOnlyDistinctPrefixes() throws Exception {
        List<String> ids = new ArrayList<>();
        String[] prefixes = {"RUT:DatedServiceJourney:", "ATB:ServiceJourney:", "NSR:Quay:"};
        for (int i = 0; i < 400; i++) {
            ids.add(prefixes[i % 3] + i);
        }

        IdCodec.Writer writer = new IdCodec.Writer();
        for (String id : ids) {
            writer.intern(id);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.writeTable(out);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        long tableCount = SnapshotIo.readVarInt(in);
        assertThat(tableCount).isEqualTo(3);
    }

    @Test
    void allIdsRoundTripInBulk() throws Exception {
        List<String> ids = new ArrayList<>();
        String[] prefixes = {"RUT:DatedServiceJourney:", "ATB:ServiceJourney:", "NSR:Quay:"};
        for (int i = 0; i < 400; i++) {
            ids.add(prefixes[i % 3] + i);
        }

        Reader reader = roundTripSetup(ids);
        for (String id : ids) {
            assertThat(reader.next()).isEqualTo(id);
        }
    }

    @Test
    void writeIdFailsLoudlyWhenPrefixWasNotInterned() throws Exception {
        IdCodec.Writer writer = new IdCodec.Writer();
        // Deliberately skip intern().
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            assertThatThrownBy(() -> writer.writeId(out, "NSR:Quay:20388"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
