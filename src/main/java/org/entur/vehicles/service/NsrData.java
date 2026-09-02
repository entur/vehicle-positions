package org.entur.vehicles.service;

import org.entur.vehicles.data.model.StopPoint;

import java.util.Map;

/**
 * What {@link NSRService} needs from the stop-place export: a stop point per stop place and
 * quay, and every child-to-parent ref (quay to stop place, stop place to multimodal parent).
 * Produced by {@link NsrNetexParser} or read back from an {@link NsrSnapshot}; installed by
 * the service, which flattens the parent refs into ancestor sets.
 */
public record NsrData(Map<String, StopPoint> stopPoints, Map<String, String> childToParent) {

    public static final NsrData EMPTY = new NsrData(Map.of(), Map.of());

    public NsrData {
        stopPoints = Map.copyOf(stopPoints);
        childToParent = Map.copyOf(childToParent);
    }
}
