package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class SnapshotIoTest {

    @Test
    void varIntRoundTripsBoundaries() throws Exception {
        for (long v : new long[]{0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                SnapshotIo.writeVarInt(out, v);
            }
            long result = SnapshotIo.readVarInt(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            assertThat(result).as("varint roundtrip for value %d", v).isEqualTo(v);
        }
    }

    @Test
    void zigZagRoundTripsNegatives() throws Exception {
        for (long v : new long[]{-1, -128, Long.MIN_VALUE, 0, 1}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                SnapshotIo.writeZigZag(out, v);
            }
            long result = SnapshotIo.readZigZag(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            assertThat(result).as("zigzag roundtrip for value %d", v).isEqualTo(v);
        }
    }

    @Test
    void stringRoundTripsNullAndNonAscii() throws Exception {
        String longString = "x".repeat(3000);
        for (String s : new String[]{null, "", "Bodø", longString}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                SnapshotIo.writeString(out, s);
            }
            String result = SnapshotIo.readString(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            assertThat(result).as("string roundtrip for value %s", s == null ? "null" : s.substring(0, Math.min(20, s.length()))).isEqualTo(s);
        }
    }
}
