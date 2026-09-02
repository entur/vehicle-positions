package org.entur.vehicles.service.snapshot;

import java.util.Optional;

/**
 * Identity of one snapshot object: which dataset, which record format, and which export
 * (by its ETag) it was built from. The prefix is the store's concern, so it is applied when
 * the object name is asked for, not when the key is made.
 */
public record SnapshotKey(String dataset, int formatVersion, String etag) {

    public static Optional<SnapshotKey> of(String dataset, int formatVersion, String rawEtag) {
        if (rawEtag == null) {
            return Optional.empty();
        }
        String etag = normaliseEtag(rawEtag);
        if (etag.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SnapshotKey(dataset, formatVersion, etag));
    }

    /** Strips a weak-validator prefix and surrounding quotes, trims, and makes the rest safe in an object name. */
    static String normaliseEtag(String raw) {
        String s = raw.trim();
        if (s.startsWith("W/")) {
            s = s.substring(2);
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public String objectName(String prefix) {
        String path = dataset + "/v" + formatVersion + "/" + etag + ".bin.gz";
        if (prefix == null || prefix.isEmpty()) {
            return path;
        }
        return prefix + "/" + path;
    }

    @Override
    public String toString() {
        return objectName("");
    }
}
