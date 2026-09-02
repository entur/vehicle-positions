package org.entur.vehicles.service.snapshot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Low-level encoding primitives for compact binary snapshot format.
 * Provides varint, zigzag, and string encoding suitable for compact serialization.
 * These methods are allocation-free except for strings themselves.
 */
final class SnapshotIo {

    private SnapshotIo() {
        // static-only utility
    }

    /**
     * Writes a long value as unsigned LEB128 varint.
     * Suitable for non-negative values; use writeZigZag for signed values.
     */
    static void writeVarInt(DataOutputStream out, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            out.writeByte((byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.writeByte((byte) v);
    }

    /**
     * Reads a long value encoded as unsigned LEB128 varint.
     */
    static long readVarInt(DataInputStream in) throws IOException {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = in.readUnsignedByte();
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * Writes a signed long value using zigzag encoding, then as varint.
     * ZigZag maps 0→0, -1→1, 1→2, -2→3, 2→4, ... Long.MIN_VALUE→Long.MAX_VALUE.
     */
    static void writeZigZag(DataOutputStream out, long v) throws IOException {
        long zigzag = (v << 1) ^ (v >> 63);
        writeVarInt(out, zigzag);
    }

    /**
     * Reads a zigzag-encoded signed long value.
     */
    static long readZigZag(DataInputStream in) throws IOException {
        long zigzag = readVarInt(in);
        return (zigzag >>> 1) ^ -(zigzag & 1);
    }

    /**
     * Writes a string value, null-safe.
     * Encodes null as varint 0, non-null string as varint (utf8_byte_length + 1) followed by UTF-8 bytes.
     * No 64 KB limit.
     */
    static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            writeVarInt(out, 0);
        } else {
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVarInt(out, (long) bytes.length + 1);
            out.write(bytes);
        }
    }

    /**
     * Reads a string value encoded by writeString.
     * Returns null if the length varint is 0.
     */
    static String readString(DataInputStream in) throws IOException {
        long length = readVarInt(in);
        if (length == 0) {
            return null;
        }
        int byteLength = (int) (length - 1);
        byte[] bytes = new byte[byteLength];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
