package org.entur.vehicles.service.snapshot;

import java.io.IOException;

/** A snapshot whose bytes are not what this build writes: wrong magic, wrong version, or cut short. */
public class SnapshotFormatException extends IOException {
    public SnapshotFormatException(String message) {
        super(message);
    }
}
