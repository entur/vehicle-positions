package org.entur.vehicles.service.snapshot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Encodes and decodes NeTEx-style ids (<code>CODESPACE:Type:local</code>) for the compact
 * binary snapshot format.
 *
 * <p>Every id is split into a prefix (everything up to and including the last {@code ':'},
 * or the empty string if there is none) and a local part. The prefix is dictionary-coded:
 * a {@link Writer} instance serves exactly one snapshot file, and every id it will ever
 * write must be {@link Writer#intern(String) interned} first so the prefix table is
 * complete by the time {@link Writer#writeTable(DataOutputStream)} runs.
 *
 * <p>The local part is packed when it has one of a few recognisable shapes (a kind byte
 * says which); anything else falls back to a raw length-prefixed UTF-8 string. Packing is
 * only used when it is guaranteed to reproduce the original string exactly - e.g. a local
 * part with an upper-case hex digit or a leading zero on a bare number is never packed.
 */
public final class IdCodec {

    private static final int KIND_RAW = 0;
    private static final int KIND_DJJ_HEX32 = 1;
    private static final int KIND_HEX32 = 2;
    private static final int KIND_UUID = 3;
    private static final int KIND_DIGITS = 4;

    private static final String DJJ_PREFIX = "djj-";
    private static final int DJJ_PREFIX_LENGTH = DJJ_PREFIX.length();
    private static final int HEX32_LENGTH = 32;
    private static final int DJJ_LOCAL_LENGTH = DJJ_PREFIX_LENGTH + HEX32_LENGTH;
    private static final int UUID_LENGTH = 36;
    private static final int MAX_DIGITS_LENGTH = 18;

    private static final Pattern DJJ_HEX32_PATTERN = Pattern.compile("djj-[0-9a-f]{32}");
    private static final Pattern HEX32_PATTERN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("0|[1-9][0-9]{0,17}");

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private IdCodec() {
        // namespace for Writer and Reader
    }

    /**
     * Splits an id at its last {@code ':'} (inclusive of the colon), returning the empty
     * string as the prefix when there is no colon.
     */
    private static int prefixEnd(String id) {
        return id.lastIndexOf(':') + 1;
    }

    /** Writes ids for a single snapshot file, dictionary-coding their prefixes. */
    public static final class Writer {

        private final Map<String, Integer> prefixIndex = new LinkedHashMap<>();

        /**
         * Registers the prefix of {@code id} in the dictionary if it is not already
         * present. Must be called for every id that will later be passed to
         * {@link #writeId(DataOutputStream, String)}, before {@link #writeTable} runs.
         */
        public void intern(String id) {
            String prefix = id.substring(0, prefixEnd(id));
            prefixIndex.computeIfAbsent(prefix, p -> prefixIndex.size());
        }

        /**
         * Writes the prefix dictionary: a varint count followed by each prefix string, in
         * the order prefixes were first interned.
         */
        public void writeTable(DataOutputStream out) throws IOException {
            SnapshotIo.writeVarInt(out, prefixIndex.size());
            for (String prefix : prefixIndex.keySet()) {
                SnapshotIo.writeString(out, prefix);
            }
        }

        /**
         * Writes one id as a varint prefix index followed by a kind byte and the
         * kind-specific payload.
         *
         * @throws IllegalStateException if {@code id}'s prefix was never
         *     {@link #intern(String) interned} - this would silently corrupt the file, so
         *     it fails loudly instead.
         */
        public void writeId(DataOutputStream out, String id) throws IOException {
            int splitAt = prefixEnd(id);
            String prefix = id.substring(0, splitAt);
            String local = id.substring(splitAt);

            Integer index = prefixIndex.get(prefix);
            if (index == null) {
                throw new IllegalStateException(
                        "IdCodec.Writer.writeId: prefix \"" + prefix + "\" (from id \"" + id
                                + "\") was never interned - the prefix table is already written "
                                + "and cannot grow. Call intern() for every id before writeTable().");
            }
            SnapshotIo.writeVarInt(out, index);
            writeLocal(out, local);
        }

        private void writeLocal(DataOutputStream out, String local) throws IOException {
            int length = local.length();
            if (length == DJJ_LOCAL_LENGTH && DJJ_HEX32_PATTERN.matcher(local).matches()) {
                out.writeByte(KIND_DJJ_HEX32);
                writeHex16(out, local, DJJ_PREFIX_LENGTH);
                return;
            }
            if (length == HEX32_LENGTH && HEX32_PATTERN.matcher(local).matches()) {
                out.writeByte(KIND_HEX32);
                writeHex16(out, local, 0);
                return;
            }
            if (length == UUID_LENGTH && UUID_PATTERN.matcher(local).matches()) {
                out.writeByte(KIND_UUID);
                UUID uuid = UUID.fromString(local);
                out.writeLong(uuid.getMostSignificantBits());
                out.writeLong(uuid.getLeastSignificantBits());
                return;
            }
            if (length > 0 && length <= MAX_DIGITS_LENGTH && DIGITS_PATTERN.matcher(local).matches()) {
                out.writeByte(KIND_DIGITS);
                SnapshotIo.writeVarInt(out, Long.parseLong(local));
                return;
            }
            out.writeByte(KIND_RAW);
            SnapshotIo.writeString(out, local);
        }

        /** Packs 32 hex characters of {@code s} starting at {@code offset} into 16 bytes. */
        private static void writeHex16(DataOutputStream out, String s, int offset) throws IOException {
            for (int i = 0; i < 16; i++) {
                int hi = Character.digit(s.charAt(offset + 2 * i), 16);
                int lo = Character.digit(s.charAt(offset + 2 * i + 1), 16);
                out.writeByte((hi << 4) | lo);
            }
        }
    }

    /** Reads ids written by a matching {@link Writer}. */
    public static final class Reader {

        private String[] prefixes;

        /** Reads the prefix dictionary written by {@link Writer#writeTable}. */
        public void readTable(DataInputStream in) throws IOException {
            int count = (int) SnapshotIo.readVarInt(in);
            prefixes = new String[count];
            for (int i = 0; i < count; i++) {
                prefixes[i] = SnapshotIo.readString(in);
            }
        }

        /** Reads one id written by {@link Writer#writeId(DataOutputStream, String)}. */
        public String readId(DataInputStream in) throws IOException {
            int index = (int) SnapshotIo.readVarInt(in);
            String prefix = prefixes[index];
            int kind = in.readUnsignedByte();
            String local =
                    switch (kind) {
                        case KIND_DJJ_HEX32 -> DJJ_PREFIX + readHex32(in);
                        case KIND_HEX32 -> readHex32(in);
                        case KIND_UUID -> readUuid(in);
                        case KIND_DIGITS -> Long.toString(SnapshotIo.readVarInt(in));
                        case KIND_RAW -> SnapshotIo.readString(in);
                        default -> throw new IOException("IdCodec.Reader.readId: unknown kind byte " + kind);
                    };
            return prefix + local;
        }

        private static String readUuid(DataInputStream in) throws IOException {
            long msb = in.readLong();
            long lsb = in.readLong();
            return new UUID(msb, lsb).toString();
        }

        private static String readHex32(DataInputStream in) throws IOException {
            char[] chars = new char[HEX32_LENGTH];
            for (int i = 0; i < 16; i++) {
                int b = in.readUnsignedByte();
                chars[2 * i] = HEX_CHARS[(b >> 4) & 0xF];
                chars[2 * i + 1] = HEX_CHARS[b & 0xF];
            }
            return new String(chars);
        }
    }
}
