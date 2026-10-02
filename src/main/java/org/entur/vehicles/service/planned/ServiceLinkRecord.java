package org.entur.vehicles.service.planned;

import java.util.Arrays;
import java.util.Objects;

/**
 * A NeTEx {@code ServiceLink} as published, from the parse to the builder and through the
 * snapshot unchanged. Read and written by {@link ServiceLinkCodec}.
 * <p>
 * Compares its geometry by content: a record's own {@code equals} compares arrays by reference.
 *
 * @param geometry interleaved lat/lon microdegrees from its {@code gis:posList}; never null,
 *                 empty when the link has none
 */
public record ServiceLinkRecord(String id, int[] geometry) {

    private static final int[] NO_GEOMETRY = new int[0];

    public ServiceLinkRecord {
        if (geometry == null) {
            geometry = NO_GEOMETRY;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ServiceLinkRecord other
                && Objects.equals(id, other.id)
                && Arrays.equals(geometry, other.geometry);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(id) + Arrays.hashCode(geometry);
    }

    @Override
    public String toString() {
        return "ServiceLinkRecord[id=" + id + ", geometry=" + Arrays.toString(geometry) + "]";
    }
}
