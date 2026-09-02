package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.snapshot.SnapshotFormatException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The NSR snapshot format: the two maps of {@link NsrData}, written from the finished parse
 * and read straight back. Header, then tagged records, then an end marker and the record
 * count. Bump {@link #FORMAT_VERSION} whenever the layout changes.
 */
public final class NsrSnapshot {

    public static final String DATASET = "nsr";
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'V', 'N', 'S', 'R'};
    private static final byte TAG_STOP_POINT = 1;
    private static final byte TAG_PARENT = 2;
    private static final byte TAG_END = (byte) 0xFF;

    private NsrSnapshot() {
    }

    public static void write(NsrData data, Path file, String etag) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(etag);
            out.writeLong(System.currentTimeMillis());
            int count = 0;
            for (StopPoint stop : data.stopPoints().values()) {
                out.writeByte(TAG_STOP_POINT);
                out.writeUTF(stop.getId());
                out.writeBoolean(stop.getName() != null);
                if (stop.getName() != null) {
                    out.writeUTF(stop.getName());
                }
                out.writeDouble(stop.getLocation().getLongitude());
                out.writeDouble(stop.getLocation().getLatitude());
                count++;
            }
            for (Map.Entry<String, String> e : data.childToParent().entrySet()) {
                out.writeByte(TAG_PARENT);
                out.writeUTF(e.getKey());
                out.writeUTF(e.getValue());
                count++;
            }
            out.writeByte(TAG_END);
            out.writeInt(count);
        }
    }

    public static NsrData read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 1 << 16));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new SnapshotFormatException("Not an NSR snapshot (bad magic)");
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new SnapshotFormatException("NSR snapshot version " + version + ", expected " + FORMAT_VERSION);
        }
        in.readUTF(); // etag, informational
        in.readLong(); // createdAt, informational

        Map<String, StopPoint> stopPoints = new HashMap<>();
        Map<String, String> childToParent = new HashMap<>();
        int count = 0;
        while (true) {
            byte tag = in.readByte();
            if (tag == TAG_END) {
                break;
            }
            switch (tag) {
                case TAG_STOP_POINT -> {
                    String id = in.readUTF();
                    String name = in.readBoolean() ? in.readUTF() : null;
                    double longitude = in.readDouble();
                    double latitude = in.readDouble();
                    stopPoints.put(id, new StopPoint(id, name, new Location(longitude, latitude)));
                }
                case TAG_PARENT -> childToParent.put(in.readUTF(), in.readUTF());
                default -> throw new SnapshotFormatException("Unknown NSR record tag " + tag);
            }
            count++;
        }
        int expected = in.readInt();
        if (expected != count) {
            throw new SnapshotFormatException("NSR snapshot record count " + count + ", header says " + expected);
        }
        return new NsrData(stopPoints, childToParent);
    }
}
