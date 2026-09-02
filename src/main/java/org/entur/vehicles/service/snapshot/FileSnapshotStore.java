package org.entur.vehicles.service.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * A directory as a snapshot store. For tests, and for local runs where parsing once and
 * restarting fast is worth a {@code file:///} URI in the config.
 */
public final class FileSnapshotStore implements SnapshotStore {

    private final Path dir;

    public FileSnapshotStore(Path dir) {
        this.dir = dir;
    }

    @Override
    public Optional<InputStream> open(String objectName) throws IOException {
        Path target = dir.resolve(objectName);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(Files.newInputStream(target));
    }

    @Override
    public boolean putIfAbsent(String objectName, Path file) throws IOException {
        Path target = dir.resolve(objectName);
        Files.createDirectories(target.getParent());
        Path staged = stage(target, file);
        try {
            Files.move(staged, target);
            return true;
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(staged);
            return false;
        }
    }

    @Override
    public void put(String objectName, Path file) throws IOException {
        Path target = dir.resolve(objectName);
        Files.createDirectories(target.getParent());
        Path staged = stage(target, file);
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Copies next to the target so the final move is a same-filesystem rename. */
    private static Path stage(Path target, Path file) throws IOException {
        Path staged = target.resolveSibling(target.getFileName() + ".part-" + System.nanoTime());
        Files.copy(file, staged);
        return staged;
    }
}
