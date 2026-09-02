package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class FileSnapshotStoreTest {

    @Test
    public void openReturnsEmptyForAMissingObject(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir);

        assertThat(store.open("planned-data/v1/abc.bin.gz")).isEmpty();
    }

    @Test
    public void putIfAbsentStoresOnceAndKeepsTheFirstContent(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir);
        Path first = Files.writeString(dir.resolve("first"), "first");
        Path second = Files.writeString(dir.resolve("second"), "second");

        assertThat(store.putIfAbsent("nsr/v1/abc.bin.gz", first)).isTrue();
        assertThat(store.putIfAbsent("nsr/v1/abc.bin.gz", second)).isFalse();

        try (InputStream in = store.open("nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("first");
        }
        assertThat(first).withFailMessage("the source file is left for the caller to delete").exists();
    }

    @Test
    public void putReplaces(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir);
        store.putIfAbsent("nsr/v1/abc.bin.gz", Files.writeString(dir.resolve("first"), "first"));

        store.put("nsr/v1/abc.bin.gz", Files.writeString(dir.resolve("second"), "second"));

        try (InputStream in = store.open("nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("second");
        }
    }

    @Test
    public void nestedDirectoriesAreCreated(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.resolve("deeper"));

        store.putIfAbsent("a/b/c.bin.gz", Files.writeString(dir.resolve("src"), "x"));

        assertThat(dir.resolve("deeper/a/b/c.bin.gz")).exists();
    }
}
