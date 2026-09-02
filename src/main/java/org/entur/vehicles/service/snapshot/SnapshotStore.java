package org.entur.vehicles.service.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where snapshot objects live. Object names are relative paths such as
 * {@code planned-data/v1/abc.bin.gz}. Implementations are thin: they neither compress nor
 * interpret content.
 */
public interface SnapshotStore {

    /** The object's bytes, or empty if there is no such object. Throws on any other failure. */
    Optional<InputStream> open(String objectName) throws IOException;

    /** Stores the file under the name unless an object already exists there; returns whether it was stored. */
    boolean putIfAbsent(String objectName, Path file) throws IOException;

    /** Stores the file under the name, replacing whatever is there. */
    void put(String objectName, Path file) throws IOException;
}
