# Startup Snapshots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a starting pod read the planned-data and NSR datasets from a per-export snapshot in a bucket instead of parsing the NeTEx exports, falling back to the full parse (and uploading a snapshot) whenever no usable snapshot exists.

**Architecture:** A small `service.snapshot` package provides the shared pieces: a `SnapshotStore` (GCS or a local directory), an `ExportDownloader` that captures ETags, a `SnapshotCache` facade that builds object names, opens gunzipped snapshots and uploads new ones on a background thread. `PlannedDataService` and `NSRService` each gain a snapshot-first `load()`: HEAD the export, try the snapshot keyed by the export's ETag, otherwise download, parse, install, and hand the raw snapshot file to the uploader. Planned data snapshots are the extractor's raw records replayed through the existing builder; NSR snapshots are the two finished maps.

**Tech Stack:** Java 25, Spring Boot, `java.net.http.HttpClient`, `java.io.DataOutputStream` + `GZIPOutputStream`, `com.google.cloud:google-cloud-storage` (version from the existing `libraries-bom`), JUnit 5 + AssertJ, `com.sun.net.httpserver.HttpServer` for tests.

**Spec:** `docs/superpowers/specs/2026-09-02-planned-data-snapshot-design.md`

## Global Constraints

- Build and test with JDK 25: every Maven command is `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn ...`. The shell default is JDK 17 and will not compile this project.
- Run a single test class with `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`.
- No new serialisation library. Snapshot formats are hand-written `DataOutputStream` record streams.
- The bucket is a cache, never a dependency: every snapshot-path failure logs and falls back to the full parse. Readiness still waits for the datasets.
- Snapshot object names: `<prefix>/<dataset>/v<formatVersion>/<etag>.bin.gz`, ETag normalised by stripping a `W/` prefix and surrounding quotes.
- One property, `vehicle.snapshot.uri`: `gs://bucket/prefix`, `file:///dir`, or empty (disabled). Default empty everywhere.
- Uploads use a does-not-exist precondition, except when replacing a snapshot that was found but could not be read.
- Gzip at `Deflater.BEST_SPEED`, on the uploader thread, never on the readiness path.
- Commit messages: imperative sentence, capitalised, no prefix, no Claude attribution (see `git log`: "Look up multiple service journeys by id").
- No test may download from the network. Tests use the checked-in GOA zip, an in-test NSR fixture, and an in-process HTTP server.
- Existing tests keep passing after every task.

## File Structure

New package `src/main/java/org/entur/vehicles/service/snapshot/`:

| File | Responsibility |
|---|---|
| `SnapshotStore.java` | Interface: `open`, `putIfAbsent`, `put` by object name |
| `FileSnapshotStore.java` | Directory-backed store for tests and local runs |
| `GcsSnapshotStore.java` | GCS-backed store, ADC credentials |
| `SnapshotKey.java` | Record `(dataset, formatVersion, etag)` and ETag normalisation |
| `SnapshotFormatException.java` | `IOException` subclass for bad magic, version, truncation |
| `ExportDownloader.java` | HEAD and GET of an export URL with ETag capture; `file:` fallback |
| `SnapshotUploader.java` | Gzip + store on a single-thread executor, returns a future with an outcome |
| `SnapshotCache.java` | Facade both services use: enabled, open (gunzipped), upload; parses the URI |
| `SnapshotConfiguration.java` | Spring beans for `ExportDownloader` and `SnapshotCache` |

Planned data, in `src/main/java/org/entur/vehicles/service/planned/`:

| File | Change |
|---|---|
| `PlannedDataSink.java` | New interface with the seven `add*` methods |
| `PlannedDataset.java` | `Builder implements PlannedDataSink` |
| `NetexPlannedDataExtractor.java` | `extract(InputStream, PlannedDataSink)` |
| `PlannedDataLoader.java` | `load(Path zip, PlannedDataSink sink)` |
| `TeeSink.java` | New: forwards to builder and writer, fail-soft on writer errors |
| `PlannedDataSnapshot.java` | New: `Writer implements PlannedDataSink`, static `replay` |
| `PlannedDataService.java` | Snapshot-first `load()` |

NSR, in `src/main/java/org/entur/vehicles/service/`:

| File | Change |
|---|---|
| `NsrData.java` | New record `(stopPoints, childToParent)` |
| `NsrNetexParser.java` | New: the JAXB parse moved out of `NSRService`, returns `NsrData` |
| `NsrSnapshot.java` | New: `write(NsrData, Path, etag)` and `read(InputStream)` |
| `NSRService.java` | Snapshot-first warm-up, `install(NsrData)` |

Other:

| File | Change |
|---|---|
| `metrics/PrometheusMetricsService.java` | `markSnapshotSource`, `markSnapshotUpload`, `markNsrLoaded` |
| `pom.xml` | `google-cloud-storage` dependency |
| `src/main/resources/application.properties` | `vehicle.snapshot.uri=` |
| `helm/vehicle-positions-2/values.yaml`, `templates/configmap.yaml`, `templates/deployment.yaml` | `snapshotUri`, ephemeral-storage request |
| `CLAUDE.md` | Document the snapshot path |

Tests, under `src/test/java/org/entur/vehicles/`:

| File | Covers |
|---|---|
| `service/snapshot/SnapshotKeyTest.java` | Object naming and ETag normalisation |
| `service/snapshot/FileSnapshotStoreTest.java` | Store semantics |
| `service/snapshot/EtagHttpServer.java` | Test support: serves one file with HEAD/GET and an ETag |
| `service/snapshot/ExportDownloaderTest.java` | HEAD, GET, `file:` fallback |
| `service/snapshot/SnapshotUploaderTest.java` | Outcomes, gzip content, replace |
| `service/snapshot/SnapshotCacheTest.java` | URI parsing, disabled, open/upload through a directory |
| `service/planned/PlannedDataSnapshotTest.java` | Round trip on GOA, format guards, tee fail-soft |
| `service/planned/PlannedDataServiceSnapshotTest.java` | Service behaviour hit/miss/corrupt/changed ETag/store failure |
| `service/NsrNetexParserTest.java` | Fixture parse |
| `service/NsrSnapshotTest.java` | Round trip, format guards |
| `service/NSRServiceSnapshotTest.java` | Service behaviour hit/miss/corrupt |
| `src/test/resources/nsr/fixture-site-frame.xml` | Minimal NSR NeTEx file |

---

### Task 1: SnapshotStore interface, SnapshotKey and FileSnapshotStore

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotStore.java`
- Create: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotKey.java`
- Create: `src/main/java/org/entur/vehicles/service/snapshot/FileSnapshotStore.java`
- Test: `src/test/java/org/entur/vehicles/service/snapshot/SnapshotKeyTest.java`
- Test: `src/test/java/org/entur/vehicles/service/snapshot/FileSnapshotStoreTest.java`

**Interfaces:**
- Produces:
  - `record SnapshotKey(String dataset, int formatVersion, String etag)` with `static Optional<SnapshotKey> of(String dataset, int formatVersion, String rawEtag)` (empty when the ETag is null or blank after normalisation), `String objectName(String prefix)`, `static String normaliseEtag(String raw)`.
  - `interface SnapshotStore { Optional<InputStream> open(String objectName) throws IOException; boolean putIfAbsent(String objectName, Path file) throws IOException; void put(String objectName, Path file) throws IOException; }`
  - `FileSnapshotStore(Path dir)`.

- [ ] **Step 1: Write the failing SnapshotKey test**

```java
package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SnapshotKeyTest {

    @Test
    public void objectNameCarriesPrefixDatasetVersionAndEtag() {
        SnapshotKey key = SnapshotKey.of("planned-data", 3, "\"abc123\"").orElseThrow();

        assertThat(key.objectName("snapshots")).isEqualTo("snapshots/planned-data/v3/abc123.bin.gz");
        assertThat(key.objectName("")).isEqualTo("planned-data/v3/abc123.bin.gz");
        assertThat(key.objectName(null)).isEqualTo("planned-data/v3/abc123.bin.gz");
    }

    @Test
    public void etagIsNormalised() {
        assertThat(SnapshotKey.normaliseEtag("\"abc\"")).isEqualTo("abc");
        assertThat(SnapshotKey.normaliseEtag("W/\"abc\"")).isEqualTo("abc");
        assertThat(SnapshotKey.normaliseEtag("  abc ")).isEqualTo("abc");
        assertThat(SnapshotKey.normaliseEtag("abc")).isEqualTo("abc");
    }

    @Test
    public void aMissingOrBlankEtagYieldsNoKey() {
        assertThat(SnapshotKey.of("nsr", 1, null)).isEmpty();
        assertThat(SnapshotKey.of("nsr", 1, "")).isEmpty();
        assertThat(SnapshotKey.of("nsr", 1, "\"\"")).isEmpty();
    }

    @Test
    public void unsafeCharactersInAnEtagAreReplaced() {
        SnapshotKey key = SnapshotKey.of("nsr", 1, "a/b c").orElseThrow();

        assertThat(key.objectName("")).isEqualTo("nsr/v1/a_b_c.bin.gz");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=SnapshotKeyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `SnapshotKey` does not exist.

- [ ] **Step 3: Write SnapshotKey and the SnapshotStore interface**

```java
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
```

```java
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
```

- [ ] **Step 4: Run the SnapshotKey test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=SnapshotKeyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing FileSnapshotStore test**

```java
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
```

- [ ] **Step 6: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=FileSnapshotStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `FileSnapshotStore` does not exist.

- [ ] **Step 7: Write FileSnapshotStore**

```java
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
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
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
```

- [ ] **Step 8: Run both tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='SnapshotKeyTest,FileSnapshotStoreTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 8 tests.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/snapshot src/test/java/org/entur/vehicles/service/snapshot
git commit -m "Add snapshot store interface, key naming and a directory-backed store"
```

---

### Task 2: ExportDownloader with ETag capture and an in-test HTTP server

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/snapshot/ExportDownloader.java`
- Create: `src/test/java/org/entur/vehicles/service/snapshot/EtagHttpServer.java`
- Test: `src/test/java/org/entur/vehicles/service/snapshot/ExportDownloaderTest.java`

**Interfaces:**
- Produces:
  - `ExportDownloader(Duration timeout)` and `ExportDownloader()` (60 s).
  - `Optional<String> head(String url)` returns the raw ETag header, empty on any failure or for non-HTTP URLs; never throws.
  - `Optional<String> download(String url, Path target) throws IOException` writes the body to `target` and returns the raw ETag header; `file:` URLs are copied and return empty; non-2xx status throws `IOException`.
  - Test support `EtagHttpServer(Path file, String etag)` implementing `AutoCloseable`, with `String url()`, `void setEtag(String)`, `void setFile(Path)`, `int headCount()`, `int getCount()`.

- [ ] **Step 1: Write the test support server**

```java
package org.entur.vehicles.service.snapshot;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves one file at {@code /export.zip} with an ETag header, answering HEAD and GET, so
 * tests can drive the snapshot path without touching the network. The {@code file:} scheme
 * carries no headers, which is why this exists.
 */
public final class EtagHttpServer implements AutoCloseable {

    private final HttpServer server;
    private volatile Path file;
    private volatile String etag;
    private final AtomicInteger headCount = new AtomicInteger();
    private final AtomicInteger getCount = new AtomicInteger();

    public EtagHttpServer(Path file, String etag) throws IOException {
        this.file = file;
        this.etag = etag;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/export.zip", exchange -> {
            byte[] body = Files.readAllBytes(this.file);
            if (this.etag != null) {
                exchange.getResponseHeaders().add("ETag", this.etag);
            }
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headCount.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            getCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/export.zip";
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public void setFile(Path file) {
        this.file = file;
    }

    public int headCount() {
        return headCount.get();
    }

    public int getCount() {
        return getCount.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: Write the failing downloader test**

```java
package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ExportDownloaderTest {

    @Test
    public void headReturnsTheEtag(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        try (EtagHttpServer server = new EtagHttpServer(export, "\"etag-1\"")) {
            ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

            assertThat(downloader.head(server.url())).contains("\"etag-1\"");
            assertThat(server.headCount()).isEqualTo(1);
            assertThat(server.getCount()).isZero();
        }
    }

    @Test
    public void headIsEmptyWithoutAnEtagOrOnFailure(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        try (EtagHttpServer server = new EtagHttpServer(export, null)) {
            ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

            assertThat(downloader.head(server.url())).isEmpty();
            assertThat(downloader.head("http://127.0.0.1:1/nothing")).isEmpty();
            assertThat(downloader.head(export.toUri().toString())).isEmpty();
        }
    }

    @Test
    public void downloadWritesTheBodyAndReturnsTheEtag(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        try (EtagHttpServer server = new EtagHttpServer(export, "\"etag-1\"")) {
            ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));
            Path target = dir.resolve("downloaded.zip");

            assertThat(downloader.download(server.url(), target)).contains("\"etag-1\"");
            assertThat(Files.readString(target)).isEqualTo("payload");
        }
    }

    @Test
    public void downloadOfAFileUrlCopiesWithoutAnEtag(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));
        Path target = dir.resolve("downloaded.zip");

        assertThat(downloader.download(export.toUri().toString(), target)).isEmpty();
        assertThat(Files.readString(target)).isEqualTo("payload");
    }

    @Test
    public void downloadFailuresThrow(@TempDir Path dir) {
        ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

        assertThatThrownBy(() -> downloader.download("http://127.0.0.1:1/nothing", dir.resolve("x")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> downloader.download(dir.resolve("missing.zip").toUri().toString(), dir.resolve("y")))
                .isInstanceOf(IOException.class);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=ExportDownloaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `ExportDownloader` does not exist.

- [ ] **Step 4: Write ExportDownloader**

```java
package org.entur.vehicles.service.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Fetches an export and tells the caller which version it got. The ETag from the GET that
 * produced the bytes is what snapshot keys are built from, so a HEAD-then-GET race with a
 * replaced export can never mislabel a snapshot. {@code file:} URLs are copied and carry
 * no ETag, which keeps tests and local runs on the same code path.
 */
public final class ExportDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(ExportDownloader.class);

    private final HttpClient client;
    private final Duration timeout;

    public ExportDownloader() {
        this(Duration.ofSeconds(60));
    }

    public ExportDownloader(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** The export's ETag, or empty when the URL is not HTTP, the request fails, or there is no ETag. Never throws. */
    public Optional<String> head(String url) {
        if (!isHttp(url)) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(timeout)
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                LOG.info("HEAD {} returned {}", url, response.statusCode());
                return Optional.empty();
            }
            return response.headers().firstValue("ETag");
        } catch (IOException e) {
            LOG.info("HEAD {} failed: {}", url, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Downloads the export to {@code target} and returns the ETag of what was downloaded, if the server sent one. */
    public Optional<String> download(String url, Path target) throws IOException {
        if (!isHttp(url)) {
            Files.copy(Path.of(URI.create(url)), target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(timeout).build();
        try {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("GET " + url + " returned " + response.statusCode());
            }
            return response.headers().firstValue("ETag");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
    }

    private static boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }
}
```

Note: `BodyHandlers.ofFile` opens the target with `CREATE, TRUNCATE_EXISTING, WRITE`, so an existing temp file from `Files.createTempFile` is fine.

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=ExportDownloaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/snapshot/ExportDownloader.java src/test/java/org/entur/vehicles/service/snapshot/EtagHttpServer.java src/test/java/org/entur/vehicles/service/snapshot/ExportDownloaderTest.java
git commit -m "Add an export downloader that captures the ETag of what it fetched"
```

---

### Task 3: Metrics for snapshots and SnapshotUploader

**Files:**
- Modify: `src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java`
- Create: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotUploader.java`
- Test: `src/test/java/org/entur/vehicles/service/snapshot/SnapshotUploaderTest.java`

**Interfaces:**
- Produces:
  - `PrometheusMetricsService.markSnapshotSource(String dataset, boolean fromSnapshot)`, `markSnapshotUpload(String dataset, String outcome)`, `markNsrLoaded(long durationMillis)`.
  - `SnapshotUploader(SnapshotStore store, String prefix, PrometheusMetricsService metrics)` (`metrics` may be null).
  - `enum SnapshotUploader.Outcome { UPLOADED, EXISTS, FAILED }`.
  - `CompletableFuture<Outcome> upload(SnapshotKey key, Path rawFile, boolean replaceExisting)`: gzips `rawFile`, stores it, deletes `rawFile` and the gzip temp file, never completes exceptionally.
  - `void shutdown()`.

- [ ] **Step 1: Add the metrics methods**

In `PrometheusMetricsService`, next to the planned-data constants (around line 62):

```java
    private static final String SNAPSHOT_SOURCE_NAME = METRICS_PREFIX + "snapshot.source";
    private static final String SNAPSHOT_UPLOAD_COUNTER_NAME = METRICS_PREFIX + "snapshot.upload";
    private static final String NSR_LOAD_DURATION_NAME = METRICS_PREFIX + "nsr.load.duration.millis";
```

Next to `plannedDataLoadDurationMillis` (around line 75):

```java
    private final AtomicLong nsrLoadDurationMillis = new AtomicLong(0);
```

Where `PLANNED_DATA_LOAD_DURATION_NAME` is registered (around line 101):

```java
        prometheusMeterRegistry.gauge(NSR_LOAD_DURATION_NAME, nsrLoadDurationMillis);
```

After `markPlannedDataLookupMiss`:

```java
    /** Which path the last load of a dataset took. Both label values are always present so a dashboard can plot either. */
    public void markSnapshotSource(String dataset, boolean fromSnapshot) {
        gauge(SNAPSHOT_SOURCE_NAME, List.of(new ImmutableTag("dataset", dataset), new ImmutableTag("source", "snapshot")), fromSnapshot ? 1 : 0);
        gauge(SNAPSHOT_SOURCE_NAME, List.of(new ImmutableTag("dataset", dataset), new ImmutableTag("source", "export")), fromSnapshot ? 0 : 1);
    }

    /** @param outcome uploaded, exists or failed */
    public void markSnapshotUpload(String dataset, String outcome) {
        prometheusMeterRegistry.counter(SNAPSHOT_UPLOAD_COUNTER_NAME,
                List.of(new ImmutableTag("dataset", dataset), new ImmutableTag("outcome", outcome))).increment();
    }

    public void markNsrLoaded(long durationMillis) {
        nsrLoadDurationMillis.set(durationMillis);
    }

    private void gauge(String name, List<ImmutableTag> tags, long value) {
        StringBuilder id = new StringBuilder(name);
        for (ImmutableTag tag : tags) {
            id.append('|').append(tag.getKey()).append('=').append(tag.getValue());
        }
        AtomicLong holder = plannedDataGauges.computeIfAbsent(id.toString(), k -> {
            AtomicLong a = new AtomicLong();
            prometheusMeterRegistry.gauge(name, List.copyOf(tags), a);
            return a;
        });
        holder.set(value);
    }
```

Keep the existing single-tag `gauge(String, String, String, long)` as is.

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q compile`
Expected: BUILD SUCCESS (no output with `-q`).

- [ ] **Step 3: Write the failing uploader test**

```java
package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class SnapshotUploaderTest {

    private SnapshotUploader uploader;

    @AfterEach
    public void shutdown() {
        if (uploader != null) {
            uploader.shutdown();
        }
    }

    private static String gunzip(InputStream in) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(in)) {
            return new String(gz.readAllBytes());
        }
    }

    @Test
    public void uploadsAGzippedCopyAndDeletesTheRawFile(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.resolve("store"));
        uploader = new SnapshotUploader(store, "pfx", null);
        SnapshotKey key = SnapshotKey.of("nsr", 1, "abc").orElseThrow();
        Path raw = Files.writeString(dir.resolve("raw.bin"), "raw bytes");

        SnapshotUploader.Outcome outcome = uploader.upload(key, raw, false).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        try (InputStream in = store.open("pfx/nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(gunzip(in)).isEqualTo("raw bytes");
        }
        assertThat(raw).doesNotExist();
    }

    @Test
    public void aSecondUploadReportsExists(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.resolve("store"));
        uploader = new SnapshotUploader(store, "", null);
        SnapshotKey key = SnapshotKey.of("nsr", 1, "abc").orElseThrow();
        uploader.upload(key, Files.writeString(dir.resolve("one"), "one"), false).join();

        SnapshotUploader.Outcome outcome = uploader.upload(key, Files.writeString(dir.resolve("two"), "two"), false).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.EXISTS);
        try (InputStream in = store.open("nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(gunzip(in)).isEqualTo("one");
        }
    }

    @Test
    public void replaceExistingOverwrites(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.resolve("store"));
        uploader = new SnapshotUploader(store, "", null);
        SnapshotKey key = SnapshotKey.of("nsr", 1, "abc").orElseThrow();
        uploader.upload(key, Files.writeString(dir.resolve("one"), "one"), false).join();

        SnapshotUploader.Outcome outcome = uploader.upload(key, Files.writeString(dir.resolve("two"), "two"), true).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        try (InputStream in = store.open("nsr/v1/abc.bin.gz").orElseThrow()) {
            assertThat(gunzip(in)).isEqualTo("two");
        }
    }

    @Test
    public void aFailingStoreReportsFailedAndStillCleansUp(@TempDir Path dir) throws Exception {
        SnapshotStore broken = new SnapshotStore() {
            @Override
            public Optional<InputStream> open(String objectName) {
                return Optional.empty();
            }

            @Override
            public boolean putIfAbsent(String objectName, Path file) throws IOException {
                throw new IOException("bucket unavailable");
            }

            @Override
            public void put(String objectName, Path file) throws IOException {
                throw new IOException("bucket unavailable");
            }
        };
        uploader = new SnapshotUploader(broken, "", null);
        Path raw = Files.writeString(dir.resolve("raw.bin"), "raw");

        SnapshotUploader.Outcome outcome = uploader.upload(SnapshotKey.of("nsr", 1, "abc").orElseThrow(), raw, false).join();

        assertThat(outcome).isEqualTo(SnapshotUploader.Outcome.FAILED);
        assertThat(raw).doesNotExist();
        assertThat(Files.list(dir)).withFailMessage("no gzip temp file is left behind").isEmpty();
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=SnapshotUploaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `SnapshotUploader` does not exist.

- [ ] **Step 5: Write SnapshotUploader**

```java
package org.entur.vehicles.service.snapshot;

import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses and stores a raw snapshot file off the caller's thread. The caller hands over
 * ownership of the raw file: it is deleted here whatever happens. Nothing here ever
 * propagates to the caller - a failed upload is a log line and a counter, and the next pod
 * that misses will simply try again.
 */
public final class SnapshotUploader {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotUploader.class);

    public enum Outcome { UPLOADED, EXISTS, FAILED }

    private final SnapshotStore store;
    private final String prefix;
    private final PrometheusMetricsService metrics;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snapshot-upload");
        t.setDaemon(true);
        return t;
    });

    public SnapshotUploader(SnapshotStore store, String prefix, PrometheusMetricsService metrics) {
        this.store = store;
        this.prefix = prefix == null ? "" : prefix;
        this.metrics = metrics;
    }

    public CompletableFuture<Outcome> upload(SnapshotKey key, Path rawFile, boolean replaceExisting) {
        return CompletableFuture.supplyAsync(() -> doUpload(key, rawFile, replaceExisting), executor);
    }

    private Outcome doUpload(SnapshotKey key, Path rawFile, boolean replaceExisting) {
        String objectName = key.objectName(prefix);
        long start = System.currentTimeMillis();
        Path gz = null;
        Outcome outcome;
        try {
            gz = Files.createTempFile("snapshot-upload", ".gz");
            gzip(rawFile, gz);
            long size = Files.size(gz);
            if (replaceExisting) {
                store.put(objectName, gz);
                outcome = Outcome.UPLOADED;
            } else {
                outcome = store.putIfAbsent(objectName, gz) ? Outcome.UPLOADED : Outcome.EXISTS;
            }
            LOG.info("Snapshot {} {}: {} bytes in {} ms", objectName, outcome == Outcome.UPLOADED ? "uploaded" : "already existed",
                    size, System.currentTimeMillis() - start);
        } catch (IOException | RuntimeException e) {
            outcome = Outcome.FAILED;
            LOG.warn("Snapshot {} upload failed after {} ms", objectName, System.currentTimeMillis() - start, e);
        } finally {
            deleteQuietly(rawFile);
            deleteQuietly(gz);
        }
        if (metrics != null) {
            metrics.markSnapshotUpload(key.dataset(), outcome.name().toLowerCase());
        }
        return outcome;
    }

    private static void gzip(Path from, Path to) throws IOException {
        try (InputStream in = Files.newInputStream(from);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(to), 1 << 16) {
                 {
                     def.setLevel(Deflater.BEST_SPEED);
                 }
             }) {
            in.transferTo(out);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not delete {}", file, e);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=SnapshotUploaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java src/main/java/org/entur/vehicles/service/snapshot/SnapshotUploader.java src/test/java/org/entur/vehicles/service/snapshot/SnapshotUploaderTest.java
git commit -m "Add a background snapshot uploader and snapshot metrics"
```

---

### Task 4: SnapshotCache facade, GCS store, Spring wiring and the property

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotFormatException.java`
- Create: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotCache.java`
- Create: `src/main/java/org/entur/vehicles/service/snapshot/GcsSnapshotStore.java`
- Create: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotConfiguration.java`
- Modify: `pom.xml` (add dependency after `google-cloud-pubsub`, around line 75)
- Modify: `src/main/resources/application.properties` (after line 57)
- Test: `src/test/java/org/entur/vehicles/service/snapshot/SnapshotCacheTest.java`

**Interfaces:**
- Produces:
  - `class SnapshotFormatException extends IOException`.
  - `SnapshotCache.disabled()`; `SnapshotCache.fromUri(String uri, PrometheusMetricsService metrics)` (empty or blank uri gives the disabled instance; unknown scheme throws `IllegalArgumentException`).
  - `boolean enabled()`.
  - `Optional<InputStream> open(SnapshotKey key)`: gunzipped stream; empty when disabled, not found, or on any error (logged). Never throws.
  - `void upload(SnapshotKey key, Path rawFile, boolean replaceExisting)`: no-op that deletes `rawFile` when disabled; otherwise hands off to the uploader.
  - `CompletableFuture<SnapshotUploader.Outcome> lastUpload()` for tests to await; completed-null future when nothing was uploaded.
  - `void close()` (annotated `@PreDestroy`).
  - Spring beans `ExportDownloader` and `SnapshotCache` from `SnapshotConfiguration`.

- [ ] **Step 1: Write the failing cache test**

```java
package org.entur.vehicles.service.snapshot;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SnapshotCacheTest {

    private static PrometheusMetricsService metrics() {
        return new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    @Test
    public void emptyUriIsDisabled(@TempDir Path dir) throws Exception {
        SnapshotCache cache = SnapshotCache.fromUri("", null);
        Path raw = Files.writeString(dir.resolve("raw"), "x");

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.open(SnapshotKey.of("nsr", 1, "abc").orElseThrow())).isEmpty();
        cache.upload(SnapshotKey.of("nsr", 1, "abc").orElseThrow(), raw, false);
        assertThat(raw).withFailMessage("a disabled cache still takes ownership of the raw file").doesNotExist();
        cache.close();
    }

    @Test
    public void unknownSchemesAreRejected() {
        assertThatThrownBy(() -> SnapshotCache.fromUri("s3://bucket/x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SnapshotCache.fromUri("gs://", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void fileUriRoundTripsThroughGzip(@TempDir Path dir) throws Exception {
        SnapshotCache cache = SnapshotCache.fromUri(dir.resolve("store").toUri().toString(), metrics());
        SnapshotKey key = SnapshotKey.of("planned-data", 2, "\"e1\"").orElseThrow();
        Path raw = Files.writeString(dir.resolve("raw"), "records");

        assertThat(cache.enabled()).isTrue();
        assertThat(cache.open(key)).isEmpty();

        cache.upload(key, raw, false);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(dir.resolve("store/planned-data/v2/e1.bin.gz")).exists();

        try (InputStream in = cache.open(key).orElseThrow()) {
            assertThat(new String(in.readAllBytes())).withFailMessage("open() gunzips").isEqualTo("records");
        }
        cache.close();
    }

    @Test
    public void openSwallowsStoreErrorsAndReportsEmpty(@TempDir Path dir) throws Exception {
        Path notADirectory = Files.writeString(dir.resolve("file"), "x");
        SnapshotCache cache = SnapshotCache.fromUri(notADirectory.toUri().toString(), null);

        assertThat(cache.open(SnapshotKey.of("nsr", 1, "abc").orElseThrow())).isEmpty();
        cache.close();
    }

    @Test
    public void openReturnsTheStreamEvenIfItIsNotValidGzip(@TempDir Path dir) throws Exception {
        // Corruption is the reader's problem: the cache only checks presence. A bad gzip
        // header surfaces as an IOException on the first read, which the service turns into
        // a fallback and a replacement upload.
        Path store = dir.resolve("store");
        Path object = store.resolve("nsr/v1/abc.bin.gz");
        Files.createDirectories(object.getParent());
        Files.writeString(object, "not gzip");
        SnapshotCache cache = SnapshotCache.fromUri(store.toUri().toString(), null);

        assertThat(cache.open(SnapshotKey.of("nsr", 1, "abc").orElseThrow())).isPresent();
        cache.close();
    }

    @Test
    public void gsUriParsesBucketAndPrefix() {
        assertThat(SnapshotCache.parseGs("gs://my-bucket/some/prefix")).containsExactly("my-bucket", "some/prefix");
        assertThat(SnapshotCache.parseGs("gs://my-bucket")).containsExactly("my-bucket", "");
        assertThat(SnapshotCache.parseGs("gs://my-bucket/")).containsExactly("my-bucket", "");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=SnapshotCacheTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `SnapshotCache` does not exist.

- [ ] **Step 3: Add the GCS dependency**

In `pom.xml`, directly after the `google-cloud-pubsub` dependency:

```xml
		<dependency>
			<groupId>com.google.cloud</groupId>
			<artifactId>google-cloud-storage</artifactId>
		</dependency>
```

- [ ] **Step 4: Write SnapshotFormatException, GcsSnapshotStore and SnapshotCache**

```java
package org.entur.vehicles.service.snapshot;

import java.io.IOException;

/** A snapshot whose bytes are not what this build writes: wrong magic, wrong version, or cut short. */
public class SnapshotFormatException extends IOException {
    public SnapshotFormatException(String message) {
        super(message);
    }
}
```

```java
package org.entur.vehicles.service.snapshot;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Snapshot objects in a GCS bucket. Credentials come from Application Default Credentials,
 * the same route the Pub/Sub subscribers use, so the pod's Workload Identity binding covers
 * both. Not unit tested: it is one client call per method, and the dev rollout exercises it.
 */
public final class GcsSnapshotStore implements SnapshotStore {

    private static final String CONTENT_TYPE = "application/octet-stream";

    private final Storage storage;
    private final String bucket;

    public GcsSnapshotStore(String bucket) {
        this(StorageOptions.getDefaultInstance().getService(), bucket);
    }

    GcsSnapshotStore(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public Optional<InputStream> open(String objectName) throws IOException {
        try {
            Blob blob = storage.get(BlobId.of(bucket, objectName));
            if (blob == null) {
                return Optional.empty();
            }
            return Optional.of(Channels.newInputStream(blob.reader()));
        } catch (StorageException e) {
            throw new IOException("Could not open gs://" + bucket + "/" + objectName, e);
        }
    }

    @Override
    public boolean putIfAbsent(String objectName, Path file) throws IOException {
        try {
            storage.createFrom(info(objectName), file, Storage.BlobWriteOption.doesNotExist());
            return true;
        } catch (StorageException e) {
            if (e.getCode() == 412) {
                return false;
            }
            throw new IOException("Could not upload gs://" + bucket + "/" + objectName, e);
        }
    }

    @Override
    public void put(String objectName, Path file) throws IOException {
        try {
            storage.createFrom(info(objectName), file);
        } catch (StorageException e) {
            throw new IOException("Could not upload gs://" + bucket + "/" + objectName, e);
        }
    }

    private BlobInfo info(String objectName) {
        return BlobInfo.newBuilder(BlobId.of(bucket, objectName)).setContentType(CONTENT_TYPE).build();
    }
}
```

```java
package org.entur.vehicles.service.snapshot;

import jakarta.annotation.PreDestroy;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/**
 * What the two dataset services talk to. Resolves object names, gunzips on open, uploads in
 * the background, and turns every failure on the way into a log line and an empty result.
 * The disabled instance is what runs when {@code vehicle.snapshot.uri} is empty.
 */
public final class SnapshotCache {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotCache.class);

    private final SnapshotStore store;
    private final String prefix;
    private final SnapshotUploader uploader;
    private volatile CompletableFuture<SnapshotUploader.Outcome> lastUpload = CompletableFuture.completedFuture(null);

    private SnapshotCache(SnapshotStore store, String prefix, SnapshotUploader uploader) {
        this.store = store;
        this.prefix = prefix;
        this.uploader = uploader;
    }

    public static SnapshotCache disabled() {
        return new SnapshotCache(null, "", null);
    }

    public static SnapshotCache fromUri(String uri, PrometheusMetricsService metrics) {
        if (uri == null || uri.isBlank()) {
            LOG.info("Snapshots disabled - vehicle.snapshot.uri is empty");
            return disabled();
        }
        SnapshotStore store;
        String prefix;
        if (uri.startsWith("gs://")) {
            List<String> parts = parseGs(uri);
            store = new GcsSnapshotStore(parts.get(0));
            prefix = parts.get(1);
        } else if (uri.startsWith("file:")) {
            store = new FileSnapshotStore(Path.of(URI.create(uri)));
            prefix = "";
        } else {
            throw new IllegalArgumentException("vehicle.snapshot.uri must be gs://bucket/prefix, file:///dir or empty, got " + uri);
        }
        LOG.info("Snapshots enabled at {}", uri);
        return new SnapshotCache(store, prefix, new SnapshotUploader(store, prefix, metrics));
    }

    /** {@code gs://bucket/some/prefix} into [bucket, prefix]; the prefix may be empty. */
    static List<String> parseGs(String uri) {
        String rest = uri.substring("gs://".length());
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String prefix = slash < 0 ? "" : rest.substring(slash + 1);
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("vehicle.snapshot.uri has no bucket: " + uri);
        }
        return List.of(bucket, prefix);
    }

    public boolean enabled() {
        return store != null;
    }

    /** A gunzipped stream of the snapshot, or empty on disabled, not found, or any store error. */
    public Optional<InputStream> open(SnapshotKey key) {
        if (!enabled()) {
            return Optional.empty();
        }
        String objectName = key.objectName(prefix);
        try {
            Optional<InputStream> raw = store.open(objectName);
            if (raw.isEmpty()) {
                LOG.info("Snapshot miss: {}", objectName);
                return Optional.empty();
            }
            LOG.info("Snapshot hit: {}", objectName);
            return Optional.of(new GZIPInputStream(raw.get(), 1 << 16));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Snapshot lookup of {} failed - treating as a miss", objectName, e);
            return Optional.empty();
        }
    }

    /** Hands the raw file to the uploader (or deletes it when disabled). Never blocks, never throws. */
    public void upload(SnapshotKey key, Path rawFile, boolean replaceExisting) {
        if (!enabled()) {
            try {
                Files.deleteIfExists(rawFile);
            } catch (IOException e) {
                LOG.warn("Could not delete {}", rawFile, e);
            }
            return;
        }
        lastUpload = uploader.upload(key, rawFile, replaceExisting);
    }

    /** The most recent upload's future, for tests that need to wait for it. */
    public CompletableFuture<SnapshotUploader.Outcome> lastUpload() {
        return lastUpload;
    }

    @PreDestroy
    public void close() {
        if (uploader != null) {
            uploader.shutdown();
        }
    }
}
```

Note on `open` of a corrupt object: `new GZIPInputStream(...)` reads the gzip header eagerly and throws `ZipException` (an `IOException`) on bad magic. The test `openReturnsTheStreamEvenIfItIsNotValidGzip` expects `isPresent()`, so wrap lazily instead: return a stream that constructs the `GZIPInputStream` on first read. Implement this with a small private static class:

```java
    /** Defers the gzip header read to the first read() so a corrupt object surfaces to the reader, not the lookup. */
    private static final class LazyGzipInputStream extends InputStream {
        private final InputStream raw;
        private InputStream gz;

        LazyGzipInputStream(InputStream raw) {
            this.raw = raw;
        }

        private InputStream delegate() throws IOException {
            if (gz == null) {
                gz = new GZIPInputStream(raw, 1 << 16);
            }
            return gz;
        }

        @Override
        public int read() throws IOException {
            return delegate().read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate().read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (gz != null) {
                gz.close();
            } else {
                raw.close();
            }
        }
    }
```

and in `open` return `Optional.of(new LazyGzipInputStream(raw.get()))`.

- [ ] **Step 5: Write SnapshotConfiguration and the property**

```java
package org.entur.vehicles.service.snapshot;

import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SnapshotConfiguration {

    @Bean
    public ExportDownloader exportDownloader() {
        return new ExportDownloader();
    }

    @Bean // close() is @PreDestroy on the class; Spring runs it on context shutdown
    public SnapshotCache snapshotCache(@Value("${vehicle.snapshot.uri:}") String uri, PrometheusMetricsService metrics) {
        return SnapshotCache.fromUri(uri, metrics);
    }
}
```

In `src/main/resources/application.properties`, after the planned-data block (line 57):

```properties

# Where per-export snapshots of the planned data and NSR datasets are cached so a starting
# pod can skip the NeTEx parses. gs://bucket/prefix, file:///dir, or empty to disable.
vehicle.snapshot.uri=
```

- [ ] **Step 6: Run the cache test and the Spring wiring test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='SnapshotCacheTest,NSRServiceSpringWiringTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. The wiring test proves the new configuration class loads with an empty URI.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/java/org/entur/vehicles/service/snapshot src/test/java/org/entur/vehicles/service/snapshot/SnapshotCacheTest.java
git commit -m "Add the snapshot cache facade with GCS and directory stores behind vehicle.snapshot.uri"
```

---

### Task 5: PlannedDataSink interface through extractor and loader

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSink.java`
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java` (Builder, lines 152-222)
- Modify: `src/main/java/org/entur/vehicles/service/planned/NetexPlannedDataExtractor.java` (every `PlannedDataset.Builder builder` parameter)
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataLoader.java`
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java` (line 100, `loader.load(zip)`)
- Modify: `src/test/java/org/entur/vehicles/service/planned/PlannedDataLoaderTest.java`

**Interfaces:**
- Produces:
  - `interface PlannedDataSink` with `addOperator(String id, String name)`, `addLine(String id, String name, String publicCode)`, `addServiceLink(String id, int[] geometry)`, `addJourneyPattern(String id, List<String> serviceLinkIds)`, `addServiceJourney(String id, String journeyPatternId, String lineId)`, `addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId)`, `addOperatingDay(String id, String calendarDate)`, each returning `PlannedDataSink`.
  - `PlannedDataset.Builder implements PlannedDataSink`, overriding each with return type `Builder`. The two-argument `addServiceJourney(String, String)` stays on `Builder` only.
  - `NetexPlannedDataExtractor.extract(InputStream in, PlannedDataSink sink)`.
  - `PlannedDataLoader.load(Path zip, PlannedDataSink sink) throws PlannedDataLoadException` (void).

- [ ] **Step 1: Write the interface**

```java
package org.entur.vehicles.service.planned;

import java.util.List;

/**
 * Where {@link NetexPlannedDataExtractor} puts what it finds. The builder is the sink that
 * matters; the snapshot writer is a second one, and a tee feeds both during a full parse.
 * Ids are never null (the extractor skips elements without one); every other argument may be.
 */
public interface PlannedDataSink {

    PlannedDataSink addOperator(String id, String name);

    PlannedDataSink addLine(String id, String name, String publicCode);

    /** @param geometry interleaved lat/lon microdegrees, or null when the link has no gis:posList */
    PlannedDataSink addServiceLink(String id, int[] geometry);

    PlannedDataSink addJourneyPattern(String id, List<String> serviceLinkIds);

    PlannedDataSink addServiceJourney(String id, String journeyPatternId, String lineId);

    PlannedDataSink addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId);

    PlannedDataSink addOperatingDay(String id, String calendarDate);
}
```

- [ ] **Step 2: Make Builder implement it**

In `PlannedDataset.java` change the class line to `public static final class Builder implements PlannedDataSink {` and add `@Override` to each of the seven interface methods (`addOperator`, `addLine`, `addServiceLink`, `addJourneyPattern`, the three-argument `addServiceJourney`, `addDatedServiceJourney`, `addOperatingDay`). Their existing `Builder` return type is a valid covariant override; the bodies do not change.

- [ ] **Step 3: Switch the extractor to the sink**

In `NetexPlannedDataExtractor.java` replace every `PlannedDataset.Builder builder` parameter with `PlannedDataSink sink` and every `builder.add...` with `sink.add...`. There are eight occurrences: `extract` and the seven `read*` methods. Update the class Javadoc's first sentence to "One StAX pass over a NeTEx XML stream, feeding the seven element types the service needs into a {@link PlannedDataSink}."

- [ ] **Step 4: Switch the loader to the sink**

Replace `PlannedDataLoader.load` with:

```java
    /** Streams every XML entry of the zip into the sink. The caller owns the sink and builds from it. */
    public void load(Path zip, PlannedDataSink sink) throws PlannedDataLoadException {
        int lineFiles = 0;
        int failedEntries = 0;

        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".xml")) {
                    continue;
                }
                try (InputStream in = new BufferedInputStream(zipFile.getInputStream(entry), 1 << 16)) {
                    extractor.extract(in, sink);
                    if (isLineFile(entry.getName())) {
                        lineFiles++;
                    }
                } catch (Exception e) {
                    failedEntries++;
                    LOG.error("Skipping NeTEx entry {}", entry.getName(), e);
                }
            }
        } catch (IOException e) {
            throw new PlannedDataLoadException("Could not read NeTEx zip " + zip, e);
        }

        if (lineFiles == 0) {
            throw new PlannedDataLoadException("NeTEx zip " + zip + " contains no parseable line file");
        }
        if (failedEntries > 0) {
            LOG.warn("{} NeTEx entries were skipped due to parse errors", failedEntries);
        }
    }
```

Update the class Javadoc's first sentence to "Streams an aggregated NeTEx zip into a {@link PlannedDataSink}."

- [ ] **Step 5: Update the service call site**

In `PlannedDataService.load()` replace `PlannedDataset fresh = loader.load(zip);` with:

```java
            PlannedDataset.Builder builder = new PlannedDataset.Builder();
            loader.load(zip, builder);
            PlannedDataset fresh = builder.build();
```

- [ ] **Step 6: Update PlannedDataLoaderTest**

Add a helper to the test class and use it in all three tests:

```java
    private static PlannedDataset load(Path zip) throws PlannedDataLoadException {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        new PlannedDataLoader().load(zip, builder);
        return builder.build();
    }
```

Replace `new PlannedDataLoader().load(goaZip())` with `load(goaZip())`, `new PlannedDataLoader().load(zip)` with `load(zip)`, and in `zeroLineFilesIsAFailedLoad` the lambda body becomes `load(zip)`.

- [ ] **Step 7: Run the planned-data tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='org.entur.vehicles.service.planned.*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, every existing planned-data test.

- [ ] **Step 8: Run the whole suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test`
Expected: PASS. The GraphQL tests that chain `new PlannedDataset.Builder().addOperator(...).addLine(...)` still compile because `Builder` keeps its covariant return type.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned src/test/java/org/entur/vehicles/service/planned/PlannedDataLoaderTest.java
git commit -m "Feed the NeTEx extractor through a PlannedDataSink interface"
```

---

### Task 6: PlannedDataSnapshot writer and reader, TeeSink

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSnapshot.java`
- Create: `src/main/java/org/entur/vehicles/service/planned/TeeSink.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataSnapshotTest.java`

**Interfaces:**
- Consumes: `PlannedDataSink`, `PlannedDataLoader.load(Path, PlannedDataSink)`, `SnapshotFormatException`.
- Produces:
  - `PlannedDataSnapshot.FORMAT_VERSION` (int, starts at 1), `PlannedDataSnapshot.DATASET` = `"planned-data"`.
  - `PlannedDataSnapshot.Writer implements PlannedDataSink, Closeable`, created by `PlannedDataSnapshot.writer(Path file, String etag) throws IOException`. Methods throw `UncheckedIOException` on write failure. `close()` writes the end marker and count.
  - `static void replay(InputStream in, PlannedDataSink sink) throws IOException`.
  - `TeeSink(PlannedDataSink primary, PlannedDataSnapshot.Writer writer)` implementing `PlannedDataSink`, with `boolean writerFailed()`.

- [ ] **Step 1: Write the failing round-trip and guard tests**

```java
package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.SnapshotFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlannedDataSnapshotTest {

    private static Path goaZip() throws URISyntaxException {
        return Path.of(PlannedDataSnapshotTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI());
    }

    @Test
    public void aReplayedSnapshotBuildsTheSameDatasetAsTheParse(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        PlannedDataset.Builder parsed = new PlannedDataset.Builder();
        try (PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "etag-1")) {
            TeeSink tee = new TeeSink(parsed, writer);
            new PlannedDataLoader().load(goaZip(), tee);
            assertThat(tee.writerFailed()).isFalse();
        }
        PlannedDataset fromParse = parsed.build();

        PlannedDataset.Builder replayed = new PlannedDataset.Builder();
        try (InputStream in = Files.newInputStream(raw)) {
            PlannedDataSnapshot.replay(in, replayed);
        }
        PlannedDataset fromSnapshot = replayed.build();

        assertThat(fromSnapshot.stats()).isEqualTo(fromParse.stats());
        assertThat(fromSnapshot.operator("GOA:Operator:GOA").getName()).isEqualTo(fromParse.operator("GOA:Operator:GOA").getName());
        assertThat(fromSnapshot.line("GOA:Line:59").getLineName()).isEqualTo(fromParse.line("GOA:Line:59").getLineName());
        assertThat(fromSnapshot.line("GOA:Line:59").getPublicCode()).isEqualTo(fromParse.line("GOA:Line:59").getPublicCode());
        assertThat(fromSnapshot.journeyPatternOf("GOA:ServiceJourney:B3008-AA_30082-R"))
                .isEqualTo(fromParse.journeyPatternOf("GOA:ServiceJourney:B3008-AA_30082-R"));
        assertThat(fromSnapshot.lineOf("GOA:ServiceJourney:B3008-AA_30082-R"))
                .isEqualTo(fromParse.lineOf("GOA:ServiceJourney:B3008-AA_30082-R"));
        assertThat(fromSnapshot.datedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20"))
                .isEqualTo(fromParse.datedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20"));
        assertThat(fromSnapshot.pointsOnLink("GOA:JourneyPattern:L59-153").getPoints())
                .isEqualTo(fromParse.pointsOnLink("GOA:JourneyPattern:L59-153").getPoints());
        // ObjectRef caches its hashCode lazily in a field; ignore it so an incidental hashCode()
        // call on one side does not make two equal lines compare unequal.
        assertThat(fromSnapshot.lines(null)).usingRecursiveComparison().ignoringFields("hashCode").isEqualTo(fromParse.lines(null));
        assertThat(fromSnapshot.operators(null)).usingRecursiveComparison().ignoringFields("hashCode").isEqualTo(fromParse.operators(null));
        assertThat(fromSnapshot.codespaces()).isEqualTo(fromParse.codespaces()); // Codespace instances are interned
    }

    @Test
    public void nullsAndEmptyGeometryRoundTrip(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        try (PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "e")) {
            writer.addOperator("O:1", null);
            writer.addLine("L:1", null, null);
            writer.addServiceLink("SL:1", null);
            writer.addServiceLink("SL:2", new int[0]);
            writer.addServiceLink("SL:3", new int[]{1, 2, 3, 4});
            writer.addJourneyPattern("JP:1", List.of());
            writer.addJourneyPattern("JP:2", List.of("SL:1", "SL:3"));
            writer.addServiceJourney("SJ:1", null, null);
            writer.addServiceJourney("SJ:2", "JP:2", "L:1");
            writer.addDatedServiceJourney("DSJ:1", null, null);
            writer.addDatedServiceJourney("DSJ:2", "SJ:2", "OD:1");
            writer.addOperatingDay("OD:1", "2026-09-02");
            writer.addOperatingDay("OD:2", null);
        }

        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        try (InputStream in = Files.newInputStream(raw)) {
            PlannedDataSnapshot.replay(in, builder);
        }
        PlannedDataset dataset = builder.build();

        assertThat(dataset.operator("O:1").getName()).isNull();
        assertThat(dataset.line("L:1").getPublicCode()).isNull();
        assertThat(dataset.stats().serviceLinks()).isEqualTo(3);
        assertThat(dataset.stats().serviceJourneys()).isEqualTo(2);
        assertThat(dataset.journeyPatternOf("SJ:1")).isEqualTo("");
        assertThat(dataset.datedServiceJourney("DSJ:2")).isEqualTo(new DatedJourneyRef("SJ:2", "2026-09-02"));
        assertThat(dataset.datedServiceJourney("DSJ:1")).isEqualTo(new DatedJourneyRef(null, null));
        assertThat(dataset.pointsOnLink("JP:2").getLength()).isEqualTo(2);
    }

    @Test
    public void headerAndTruncationAreGuarded(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        try (PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "e")) {
            writer.addOperator("O:1", "One");
        }
        byte[] good = Files.readAllBytes(raw);

        byte[] wrongMagic = good.clone();
        wrongMagic[0] = 'X';
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongMagic), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("magic");

        byte[] wrongVersion = good.clone();
        wrongVersion[7] = (byte) (wrongVersion[7] + 1);
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongVersion), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("version");

        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3);
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(truncated), new PlannedDataset.Builder()))
                .isInstanceOf(IOException.class);

        byte[] wrongCount = good.clone();
        wrongCount[wrongCount.length - 1] = (byte) (wrongCount[wrongCount.length - 1] + 1);
        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(wrongCount), new PlannedDataset.Builder()))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("count");

        assertThatThrownBy(() -> PlannedDataSnapshot.replay(new ByteArrayInputStream(new byte[0]), new PlannedDataset.Builder()))
                .isInstanceOf(EOFException.class);
    }

    @Test
    public void aFailingWriterDoesNotDisturbThePrimarySink(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("planned.bin");
        PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, "e");
        writer.close(); // any further write fails
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        TeeSink tee = new TeeSink(builder, writer);

        tee.addOperator("O:1", "One");
        tee.addLine("L:1", "Line", "1");

        assertThat(tee.writerFailed()).isTrue();
        PlannedDataset dataset = builder.build();
        assertThat(dataset.operator("O:1")).isNotNull();
        assertThat(dataset.line("L:1")).isNotNull();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDataSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `PlannedDataSnapshot` does not exist.

- [ ] **Step 3: Write PlannedDataSnapshot**

```java
package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.SnapshotFormatException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The planned-data snapshot format: the raw records the NeTEx extractor emits, in order, so
 * a replay through {@link PlannedDataset.Builder} builds exactly what a parse would. Header,
 * then tagged records, then an end marker and the record count.
 * <p>
 * Bump {@link #FORMAT_VERSION} whenever a record's layout or the set of extracted fields
 * changes; the version is part of the object name, so old and new images never read each
 * other's snapshots.
 */
public final class PlannedDataSnapshot {

    public static final String DATASET = "planned-data";
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'V', 'P', 'P', 'D'};
    private static final byte TAG_OPERATOR = 1;
    private static final byte TAG_LINE = 2;
    private static final byte TAG_SERVICE_LINK = 3;
    private static final byte TAG_JOURNEY_PATTERN = 4;
    private static final byte TAG_SERVICE_JOURNEY = 5;
    private static final byte TAG_DATED_SERVICE_JOURNEY = 6;
    private static final byte TAG_OPERATING_DAY = 7;
    private static final byte TAG_END = (byte) 0xFF;

    private PlannedDataSnapshot() {
    }

    public static Writer writer(Path file, String etag) throws IOException {
        return new Writer(file, etag);
    }

    /** A {@link PlannedDataSink} that appends each record to the file. Write failures surface as {@link UncheckedIOException}. */
    public static final class Writer implements PlannedDataSink, Closeable {

        private final DataOutputStream out;
        private int count = 0;
        private boolean closed = false;

        private Writer(Path file, String etag) throws IOException {
            this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(etag);
            out.writeLong(System.currentTimeMillis());
        }

        @Override
        public Writer addOperator(String id, String name) {
            return record(TAG_OPERATOR, () -> {
                out.writeUTF(id);
                nullable(name);
            });
        }

        @Override
        public Writer addLine(String id, String name, String publicCode) {
            return record(TAG_LINE, () -> {
                out.writeUTF(id);
                nullable(name);
                nullable(publicCode);
            });
        }

        @Override
        public Writer addServiceLink(String id, int[] geometry) {
            return record(TAG_SERVICE_LINK, () -> {
                out.writeUTF(id);
                if (geometry == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(geometry.length);
                    for (int v : geometry) {
                        out.writeInt(v);
                    }
                }
            });
        }

        @Override
        public Writer addJourneyPattern(String id, List<String> serviceLinkIds) {
            return record(TAG_JOURNEY_PATTERN, () -> {
                out.writeUTF(id);
                out.writeInt(serviceLinkIds.size());
                for (String link : serviceLinkIds) {
                    out.writeUTF(link);
                }
            });
        }

        @Override
        public Writer addServiceJourney(String id, String journeyPatternId, String lineId) {
            return record(TAG_SERVICE_JOURNEY, () -> {
                out.writeUTF(id);
                nullable(journeyPatternId);
                nullable(lineId);
            });
        }

        @Override
        public Writer addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId) {
            return record(TAG_DATED_SERVICE_JOURNEY, () -> {
                out.writeUTF(id);
                nullable(serviceJourneyId);
                nullable(operatingDayId);
            });
        }

        @Override
        public Writer addOperatingDay(String id, String calendarDate) {
            return record(TAG_OPERATING_DAY, () -> {
                out.writeUTF(id);
                nullable(calendarDate);
            });
        }

        private interface Body {
            void write() throws IOException;
        }

        private Writer record(byte tag, Body body) {
            if (closed) {
                // The buffered stream would silently absorb writes after close until its
                // buffer filled; fail at once instead so the tee drops the writer immediately.
                throw new UncheckedIOException(new IOException("snapshot writer is closed"));
            }
            try {
                out.writeByte(tag);
                body.write();
                count++;
                return this;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void nullable(String s) throws IOException {
            out.writeBoolean(s != null);
            if (s != null) {
                out.writeUTF(s);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.writeByte(TAG_END);
                out.writeInt(count);
            } finally {
                out.close();
            }
        }
    }

    /** Feeds every record of a snapshot into the sink. Throws {@link SnapshotFormatException} on a header or count mismatch and {@link IOException} on truncation. */
    public static void replay(InputStream stream, PlannedDataSink sink) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 1 << 16));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new SnapshotFormatException("Not a planned-data snapshot (bad magic)");
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new SnapshotFormatException("Planned-data snapshot version " + version + ", expected " + FORMAT_VERSION);
        }
        in.readUTF(); // etag, informational
        in.readLong(); // createdAt, informational

        int count = 0;
        while (true) {
            byte tag = in.readByte();
            if (tag == TAG_END) {
                break;
            }
            String id = in.readUTF();
            switch (tag) {
                case TAG_OPERATOR -> sink.addOperator(id, nullable(in));
                case TAG_LINE -> sink.addLine(id, nullable(in), nullable(in));
                case TAG_SERVICE_LINK -> {
                    int length = in.readInt();
                    int[] geometry = null;
                    if (length >= 0) {
                        geometry = new int[length];
                        for (int i = 0; i < length; i++) {
                            geometry[i] = in.readInt();
                        }
                    }
                    sink.addServiceLink(id, geometry);
                }
                case TAG_JOURNEY_PATTERN -> {
                    int size = in.readInt();
                    List<String> links = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        links.add(in.readUTF());
                    }
                    sink.addJourneyPattern(id, links);
                }
                case TAG_SERVICE_JOURNEY -> sink.addServiceJourney(id, nullable(in), nullable(in));
                case TAG_DATED_SERVICE_JOURNEY -> sink.addDatedServiceJourney(id, nullable(in), nullable(in));
                case TAG_OPERATING_DAY -> sink.addOperatingDay(id, nullable(in));
                default -> throw new SnapshotFormatException("Unknown planned-data record tag " + tag);
            }
            count++;
        }
        int expected = in.readInt();
        if (expected != count) {
            throw new SnapshotFormatException("Planned-data snapshot record count " + count + ", header says " + expected);
        }
    }

    private static String nullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
```

Note on the wrong-version test: the header is 4 magic bytes then a 4-byte big-endian int, so byte index 7 is the low byte of the version. Incrementing it changes the version without touching the magic.

- [ ] **Step 4: Write TeeSink**

```java
package org.entur.vehicles.service.planned;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * Forwards every record to the builder and, as long as it keeps working, to the snapshot
 * writer. The writer is the optional part: its first failure is logged once and it is
 * dropped, so a full disk can cost the snapshot but never the dataset.
 */
final class TeeSink implements PlannedDataSink {

    private static final Logger LOG = LoggerFactory.getLogger(TeeSink.class);

    private final PlannedDataSink primary;
    private final PlannedDataSnapshot.Writer writer;
    private boolean writerFailed = false;

    TeeSink(PlannedDataSink primary, PlannedDataSnapshot.Writer writer) {
        this.primary = primary;
        this.writer = writer;
    }

    boolean writerFailed() {
        return writerFailed;
    }

    private interface Write {
        void to(PlannedDataSink sink);
    }

    private TeeSink both(Write write) {
        write.to(primary);
        if (!writerFailed) {
            try {
                write.to(writer);
            } catch (UncheckedIOException e) {
                writerFailed = true;
                LOG.warn("Snapshot writer failed - the dataset is unaffected, no snapshot will be uploaded", e);
            }
        }
        return this;
    }

    @Override
    public TeeSink addOperator(String id, String name) {
        return both(s -> s.addOperator(id, name));
    }

    @Override
    public TeeSink addLine(String id, String name, String publicCode) {
        return both(s -> s.addLine(id, name, publicCode));
    }

    @Override
    public TeeSink addServiceLink(String id, int[] geometry) {
        return both(s -> s.addServiceLink(id, geometry));
    }

    @Override
    public TeeSink addJourneyPattern(String id, List<String> serviceLinkIds) {
        return both(s -> s.addJourneyPattern(id, serviceLinkIds));
    }

    @Override
    public TeeSink addServiceJourney(String id, String journeyPatternId, String lineId) {
        return both(s -> s.addServiceJourney(id, journeyPatternId, lineId));
    }

    @Override
    public TeeSink addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId) {
        return both(s -> s.addDatedServiceJourney(id, serviceJourneyId, operatingDayId));
    }

    @Override
    public TeeSink addOperatingDay(String id, String calendarDate) {
        return both(s -> s.addOperatingDay(id, calendarDate));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDataSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/PlannedDataSnapshot.java src/main/java/org/entur/vehicles/service/planned/TeeSink.java src/test/java/org/entur/vehicles/service/planned/PlannedDataSnapshotTest.java
git commit -m "Add the planned-data snapshot format with a tee for the full parse"
```

---

### Task 7: Snapshot-first PlannedDataService

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java`
- Modify: `src/test/java/org/entur/vehicles/service/planned/PlannedDataServiceTest.java` (constructor calls)
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataServiceSnapshotTest.java`

**Interfaces:**
- Consumes: `ExportDownloader`, `SnapshotCache`, `SnapshotKey`, `PlannedDataSnapshot`, `TeeSink`, `PlannedDataLoader.load(Path, PlannedDataSink)`, `PrometheusMetricsService.markSnapshotSource`.
- Produces: `PlannedDataService(boolean enabled, String url, PlannedDataLoader loader, PrometheusMetricsService metrics, int minServiceJourneys, ExportDownloader downloader, SnapshotCache snapshots)`; `disabled()` unchanged in signature.

- [ ] **Step 1: Write the failing service test**

```java
package org.entur.vehicles.service.planned;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.snapshot.EtagHttpServer;
import org.entur.vehicles.service.snapshot.ExportDownloader;
import org.entur.vehicles.service.snapshot.SnapshotCache;
import org.entur.vehicles.service.snapshot.SnapshotUploader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class PlannedDataServiceSnapshotTest {

    @TempDir
    Path dir;
    private Path storeDir;
    private EtagHttpServer server;
    private SnapshotCache cache;

    @BeforeEach
    public void setUp() throws Exception {
        Path zip = dir.resolve("goa.zip");
        Files.copy(Path.of(getClass().getResource("/netex/rb_goa-aggregated-netex.zip").toURI()), zip);
        server = new EtagHttpServer(zip, "\"etag-1\"");
        storeDir = dir.resolve("store");
        cache = SnapshotCache.fromUri(storeDir.toUri().toString(), metrics());
    }

    @AfterEach
    public void tearDown() {
        cache.close();
        server.close();
    }

    private static PrometheusMetricsService metrics() {
        return new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    private PlannedDataService service(SnapshotCache snapshots) {
        return new PlannedDataService(true, server.url(), new PlannedDataLoader(), metrics(), 0,
                new ExportDownloader(Duration.ofSeconds(5)), snapshots);
    }

    private Path expectedObject() {
        return storeDir.resolve("planned-data/v" + PlannedDataSnapshot.FORMAT_VERSION + "/etag-1.bin.gz");
    }

    @Test
    public void firstLoadMissesParsesAndUploads() {
        PlannedDataService service = service(cache);

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(expectedObject()).exists();
        assertThat(server.getCount()).isEqualTo(1);
    }

    @Test
    public void secondLoadHitsAndDoesNotDownload() {
        PlannedDataService first = service(cache);
        first.initialLoad();
        cache.lastUpload().join();
        PlannedDataset parsed = first.current();

        PlannedDataService second = service(cache);
        second.initialLoad();

        assertThat(server.getCount()).withFailMessage("a hit never downloads the export").isEqualTo(1);
        assertThat(second.current().stats()).isEqualTo(parsed.stats());
        assertThat(second.current().line("GOA:Line:59").getPublicCode()).isEqualTo("L5");
    }

    @Test
    public void aCorruptSnapshotFallsBackAndIsReplaced() throws Exception {
        Files.createDirectories(expectedObject().getParent());
        Files.writeString(expectedObject(), "garbage");
        PlannedDataService service = service(cache);

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(Files.size(expectedObject())).isGreaterThan("garbage".length());
    }

    @Test
    public void aChangedEtagMisses() {
        PlannedDataService first = service(cache);
        first.initialLoad();
        cache.lastUpload().join();
        server.setEtag("\"etag-2\"");

        PlannedDataService second = service(cache);
        second.initialLoad();

        assertThat(server.getCount()).isEqualTo(2);
        assertThat(storeDir.resolve("planned-data/v" + PlannedDataSnapshot.FORMAT_VERSION + "/etag-2.bin.gz")).exists();
    }

    @Test
    public void noEtagMeansNoSnapshotButStillALoad() {
        server.setEtag(null);
        PlannedDataService service = service(cache);

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(cache.lastUpload().join()).isNull();
        assertThat(storeDir).doesNotExist();
    }

    @Test
    public void aDisabledCacheIsTodaysBehaviour() {
        PlannedDataService service = service(SnapshotCache.disabled());

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(server.headCount()).isZero();
        assertThat(storeDir).doesNotExist();
    }
}
```

- [ ] **Step 2: Update the existing test's constructor calls**

In `PlannedDataServiceTest`, every `new PlannedDataService(true, <url>, new PlannedDataLoader(), metrics(), <n>)` gains two trailing arguments: `, new ExportDownloader(), SnapshotCache.disabled()`. Add the two imports. There are five call sites.

- [ ] **Step 3: Run both to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='PlannedDataServiceTest,PlannedDataServiceSnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, no seven-argument constructor.

- [ ] **Step 4: Rewrite PlannedDataService**

Replace the fields, constructor, `disabled()`, `load()` and `download()` with the following. The `find*` methods, `scheduledReload`, `initialLoad`, `isSuspiciouslySmall` and `miss` are unchanged.

```java
    private final boolean enabled;
    private final String url;
    private final PlannedDataLoader loader;
    private final PrometheusMetricsService metrics;
    private final int minServiceJourneys;
    private final ExportDownloader downloader;
    private final SnapshotCache snapshots;
    private final AtomicReference<PlannedDataset> current = new AtomicReference<>(PlannedDataset.EMPTY);

    @Autowired
    public PlannedDataService(@Value("${vehicle.planned.data.enabled:false}") boolean enabled,
                              @Value("${vehicle.planned.data.url:https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip}") String url,
                              PlannedDataLoader loader,
                              PrometheusMetricsService metrics,
                              @Value("${vehicle.planned.data.min.service.journeys:50000}") int minServiceJourneys,
                              ExportDownloader downloader,
                              SnapshotCache snapshots) {
        this.enabled = enabled;
        this.url = url;
        this.loader = loader;
        this.metrics = metrics;
        this.minServiceJourneys = minServiceJourneys;
        this.downloader = downloader;
        this.snapshots = snapshots;
    }

    /** A service that never loads and serves {@link PlannedDataset#EMPTY} - for tests. */
    public static PlannedDataService disabled() {
        return new PlannedDataService(false, null, null, null, 0, null, SnapshotCache.disabled());
    }
```

```java
    private void load() throws PlannedDataLoadException {
        long start = System.currentTimeMillis();
        Path zip = null;
        Path raw = null;
        try {
            PlannedDataset.Builder builder = new PlannedDataset.Builder();
            String source = null;
            SnapshotKey key = null;
            boolean unreadable = false;

            // 1. Snapshot first: the export's ETag names the object that holds exactly its records.
            if (snapshots.enabled()) {
                Optional<SnapshotKey> candidate = downloader.head(url)
                        .flatMap(etag -> SnapshotKey.of(PlannedDataSnapshot.DATASET, PlannedDataSnapshot.FORMAT_VERSION, etag));
                if (candidate.isEmpty()) {
                    LOG.info("No ETag for {} - loading the export without a snapshot", url);
                }
                Optional<InputStream> in = candidate.flatMap(snapshots::open);
                if (in.isPresent()) {
                    try (InputStream stream = in.get()) {
                        PlannedDataSnapshot.replay(stream, builder);
                        key = candidate.get();
                        source = "snapshot";
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("Snapshot {} could not be replayed - falling back to the export", candidate.get(), e);
                        builder = new PlannedDataset.Builder();
                        unreadable = true;
                    }
                }
            }

            // 2. Full parse, teeing the records into a snapshot file when we know which export this is.
            if (source == null) {
                zip = Files.createTempFile("planned-netex", ".zip");
                long downloadStart = System.currentTimeMillis();
                Optional<String> etag;
                try {
                    etag = downloader.download(url, zip);
                } catch (IOException e) {
                    throw new PlannedDataLoadException("Could not download " + url, e);
                }
                LOG.info("Download of {} took {} ms", url, System.currentTimeMillis() - downloadStart);
                Optional<SnapshotKey> uploadKey = snapshots.enabled()
                        ? etag.flatMap(e -> SnapshotKey.of(PlannedDataSnapshot.DATASET, PlannedDataSnapshot.FORMAT_VERSION, e))
                        : Optional.empty();
                if (uploadKey.isPresent()) {
                    raw = Files.createTempFile("planned-snapshot", ".bin");
                    TeeSink tee;
                    try (PlannedDataSnapshot.Writer writer = PlannedDataSnapshot.writer(raw, uploadKey.get().etag())) {
                        tee = new TeeSink(builder, writer);
                        loader.load(zip, tee);
                    }
                    if (tee.writerFailed()) {
                        Files.deleteIfExists(raw);
                        raw = null;
                    } else {
                        key = uploadKey.get();
                    }
                } else {
                    loader.load(zip, builder);
                }
                source = "export";
            }

            // 3. Build, check, install.
            PlannedDataset fresh = builder.build();
            if (fresh.serviceJourneyCount() < minServiceJourneys) {
                throw new PlannedDataLoadException("Fresh dataset has " + fresh.serviceJourneyCount()
                        + " service journeys, below the configured minimum of " + minServiceJourneys
                        + " - rejecting");
            }
            PlannedDataset previous = current.get();
            if (isSuspiciouslySmall(fresh, previous)) {
                throw new PlannedDataLoadException("Fresh dataset has " + fresh.serviceJourneyCount()
                        + " service journeys, current has " + previous.serviceJourneyCount()
                        + " - rejecting as a truncated export");
            }
            current.set(fresh);
            long duration = System.currentTimeMillis() - start;
            if (metrics != null) {
                metrics.markPlannedDataLoaded(duration, fresh.stats());
                metrics.markSnapshotSource(PlannedDataSnapshot.DATASET, "snapshot".equals(source));
            }
            LOG.info("Planned data loaded in {} ms from {} (etag={}): {}", duration, source,
                    key == null ? "none" : key.etag(), fresh.stats());

            // 4. Only now, off the readiness path, does the raw file go to the bucket.
            if (raw != null) {
                snapshots.upload(key, raw, unreadable);
                raw = null; // the uploader owns it from here
            }
        } catch (IOException e) {
            if (metrics != null) {
                metrics.markPlannedDataLoadFailure();
            }
            throw new PlannedDataLoadException("Planned data load failed", e);
        } catch (PlannedDataLoadException e) {
            if (metrics != null) {
                metrics.markPlannedDataLoadFailure();
            }
            throw e;
        } finally {
            deleteQuietly(zip);
            deleteQuietly(raw);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not delete temp file {}", file, e);
        }
    }
```

Delete the old `download` method and the `DOWNLOAD_TIMEOUT_MILLIS` constant and the `org.apache.commons.io.FileUtils` and `java.net.URL` imports. Add imports for `java.io.InputStream`, `java.util.Optional`, `org.entur.vehicles.service.snapshot.ExportDownloader`, `org.entur.vehicles.service.snapshot.SnapshotCache`, `org.entur.vehicles.service.snapshot.SnapshotKey`.

Update the class Javadoc: after the first paragraph add "Each load tries the snapshot named by the export's ETag before parsing; a parse tees its records into a new snapshot that is uploaded after the dataset is live. See `docs/superpowers/specs/2026-09-02-planned-data-snapshot-design.md`."

- [ ] **Step 5: Run the planned-data tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='org.entur.vehicles.service.planned.*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including the six new snapshot tests.

- [ ] **Step 6: Run the whole suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test`
Expected: PASS. `PlannedDataService` is constructed by Spring in the GraphQL tests with `vehicle.planned.data.enabled=false`; the two new constructor arguments come from `SnapshotConfiguration`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java src/test/java/org/entur/vehicles/service/planned
git commit -m "Load planned data from a per-export snapshot before falling back to the NeTEx parse"
```

---

### Task 8: NsrData and NsrNetexParser extracted from NSRService

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/NsrData.java`
- Create: `src/main/java/org/entur/vehicles/service/NsrNetexParser.java`
- Create: `src/test/resources/nsr/fixture-site-frame.xml`
- Test: `src/test/java/org/entur/vehicles/service/NsrNetexParserTest.java`

**Interfaces:**
- Produces:
  - `record NsrData(Map<String, StopPoint> stopPoints, Map<String, String> childToParent)` with `static NsrData EMPTY`.
  - `NsrNetexParser.parse(Path zip) throws IOException` returning `NsrData`.
  - Test support `NsrFixture.zip(Path dir)` (a static helper inside `NsrNetexParserTest`, made `public static` so later tests reuse it) that writes the fixture XML into a zip and returns the zip path.

`NSRService` itself is not changed in this task; the parse logic is copied out, and Task 10 makes the service call it.

- [ ] **Step 1: Write the fixture**

`src/test/resources/nsr/fixture-site-frame.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PublicationDelivery xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" version="1.15:NO-NeTEx-networktimetable:1.5">
  <PublicationTimestamp>2026-09-02T00:00:00</PublicationTimestamp>
  <ParticipantRef>NSR</ParticipantRef>
  <dataObjects>
    <SiteFrame id="NSR:SiteFrame:1" version="1">
      <stopPlaces>
        <StopPlace id="NSR:StopPlace:1" version="1">
          <Name>Hub</Name>
          <Centroid>
            <Location>
              <Longitude>10.750000</Longitude>
              <Latitude>59.910000</Latitude>
            </Location>
          </Centroid>
        </StopPlace>
        <StopPlace id="NSR:StopPlace:2" version="1">
          <Name>Oslo S</Name>
          <Centroid>
            <Location>
              <Longitude>10.752245</Longitude>
              <Latitude>59.910357</Latitude>
            </Location>
          </Centroid>
          <ParentSiteRef ref="NSR:StopPlace:1" version="1"/>
          <quays>
            <Quay id="NSR:Quay:21" version="1">
              <Name>Spor 1</Name>
              <Centroid>
                <Location>
                  <Longitude>10.752300</Longitude>
                  <Latitude>59.910400</Latitude>
                </Location>
              </Centroid>
            </Quay>
            <Quay id="NSR:Quay:22" version="1">
              <Centroid>
                <Location>
                  <Longitude>10.752400</Longitude>
                  <Latitude>59.910500</Latitude>
                </Location>
              </Centroid>
            </Quay>
          </quays>
        </StopPlace>
        <StopPlace id="NSR:StopPlace:3" version="1">
          <Name>Lonely</Name>
          <Centroid>
            <Location>
              <Longitude>5.320000</Longitude>
              <Latitude>60.390000</Latitude>
            </Location>
          </Centroid>
          <quays>
            <Quay id="NSR:Quay:31" version="1">
              <Name>A</Name>
              <Centroid>
                <Location>
                  <Longitude>5.320100</Longitude>
                  <Latitude>60.390100</Latitude>
                </Location>
              </Centroid>
            </Quay>
          </quays>
        </StopPlace>
      </stopPlaces>
    </SiteFrame>
  </dataObjects>
</PublicationDelivery>
```

- [ ] **Step 2: Write the failing parser test**

```java
package org.entur.vehicles.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class NsrNetexParserTest {

    /** The fixture XML zipped the way the NSR export is: one XML entry. Shared with the service tests. */
    public static Path zip(Path dir) throws IOException {
        Path zip = dir.resolve("nsr.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
             InputStream xml = NsrNetexParserTest.class.getResourceAsStream("/nsr/fixture-site-frame.xml")) {
            out.putNextEntry(new ZipEntry("_stops.xml"));
            xml.transferTo(out);
            out.closeEntry();
        }
        return zip;
    }

    @Test
    public void parsesStopPlacesQuaysAndParents(@TempDir Path dir) throws Exception {
        NsrData data = new NsrNetexParser().parse(zip(dir));

        assertThat(data.stopPoints()).containsOnlyKeys(
                "NSR:StopPlace:1", "NSR:StopPlace:2", "NSR:StopPlace:3", "NSR:Quay:21", "NSR:Quay:22", "NSR:Quay:31");
        assertThat(data.stopPoints().get("NSR:StopPlace:2").getName()).isEqualTo("Oslo S");
        assertThat(data.stopPoints().get("NSR:StopPlace:2").getLocation().getLongitude()).isEqualTo(10.752245);
        assertThat(data.stopPoints().get("NSR:StopPlace:2").getLocation().getLatitude()).isEqualTo(59.910357);
        assertThat(data.stopPoints().get("NSR:Quay:21").getName()).isEqualTo("Spor 1");
        assertThat(data.stopPoints().get("NSR:Quay:22").getName())
                .withFailMessage("a quay without a name takes its stop place's name")
                .isEqualTo("Oslo S");
        assertThat(data.childToParent()).containsOnly(
                entry("NSR:Quay:21", "NSR:StopPlace:2"),
                entry("NSR:Quay:22", "NSR:StopPlace:2"),
                entry("NSR:Quay:31", "NSR:StopPlace:3"),
                entry("NSR:StopPlace:2", "NSR:StopPlace:1"));
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=NsrNetexParserTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `NsrData` and `NsrNetexParser` do not exist.

- [ ] **Step 4: Write NsrData and NsrNetexParser**

```java
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
```

```java
package org.entur.vehicles.service;

import org.entur.netex.NetexParser;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.rutebanken.netex.model.LocationStructure;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.Quays_RelStructure;
import org.rutebanken.netex.model.SiteRefStructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The JAXB parse of the NSR stop-place export, moved out of {@code NSRService.warmUpCache}
 * unchanged so it is a pure function of the zip. This is the slow path (about 18 s on the
 * full export); the snapshot exists so most pods never run it.
 */
public final class NsrNetexParser {

    public NsrData parse(Path zip) throws IOException {
        NetexEntitiesIndex index;
        try {
            index = new NetexParser().parse(zip.toAbsolutePath().toString());
        } catch (RuntimeException e) {
            throw new IOException("Could not parse NSR export " + zip, e);
        }

        Map<String, StopPoint> stopPoints = new HashMap<>();
        // The parser already publishes quay -> stop place; stop place -> multimodal parent is
        // added from the loop below, which visits every stop place anyway.
        Map<String, String> childToParent = new HashMap<>(index.getStopPlaceIdByQuayIdIndex());

        index.getStopPlaceIndex().getLatestVersions().forEach(stopPlace -> {
            String stopPlaceId = stopPlace.getId();
            SiteRefStructure parentSiteRef = stopPlace.getParentSiteRef();
            if (parentSiteRef != null && parentSiteRef.getRef() != null) {
                childToParent.put(stopPlaceId, parentSiteRef.getRef());
            }
            String stopPlaceName = stopPlace.getName().getValue();
            LocationStructure stopPlaceLocation = stopPlace.getCentroid().getLocation();
            stopPoints.put(stopPlaceId, new StopPoint(stopPlaceId, stopPlaceName,
                    new Location(stopPlaceLocation.getLongitude().doubleValue(), stopPlaceLocation.getLatitude().doubleValue())));
            Quays_RelStructure quays = stopPlace.getQuays();
            if (quays != null) {
                quays.getQuayRefOrQuay().forEach(jaxbQuay -> {
                    if (jaxbQuay.getValue() instanceof Quay quay) {
                        String id = quay.getId();
                        String name = quay.getName() == null || quay.getName().getValue() == null
                                ? stopPlaceName
                                : quay.getName().getValue();
                        LocationStructure quayLocation = quay.getCentroid().getLocation();
                        stopPoints.put(id, new StopPoint(id, name,
                                new Location(quayLocation.getLongitude().doubleValue(), quayLocation.getLatitude().doubleValue())));
                    }
                });
            }
        });
        return new NsrData(stopPoints, childToParent);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=NsrNetexParserTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If the assertion on `childToParent` fails because `getStopPlaceIdByQuayIdIndex()` is empty for the fixture, the fixture's quays are not being indexed; check that each `Quay` has a `version` attribute and that the file name inside the zip ends with `.xml`, both of which `netex-parser-java` 4.0.0 requires. If the parse throws on element order, move `<ParentSiteRef>` to directly after `<Name>` in the fixture. Adjust the fixture, never the parser, until the test passes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/NsrData.java src/main/java/org/entur/vehicles/service/NsrNetexParser.java src/test/resources/nsr src/test/java/org/entur/vehicles/service/NsrNetexParserTest.java
git commit -m "Extract the NSR stop-place parse into a pure NsrNetexParser"
```

---

### Task 9: NsrSnapshot writer and reader

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/NsrSnapshot.java`
- Test: `src/test/java/org/entur/vehicles/service/NsrSnapshotTest.java`

**Interfaces:**
- Consumes: `NsrData`, `SnapshotFormatException`, `NsrNetexParserTest.zip(Path)`.
- Produces: `NsrSnapshot.DATASET = "nsr"`, `NsrSnapshot.FORMAT_VERSION = 1`, `static void write(NsrData data, Path file, String etag) throws IOException`, `static NsrData read(InputStream in) throws IOException`.

- [ ] **Step 1: Write the failing test**

```java
package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.snapshot.SnapshotFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NsrSnapshotTest {

    @Test
    public void theParsedFixtureRoundTrips(@TempDir Path dir) throws Exception {
        NsrData parsed = new NsrNetexParser().parse(NsrNetexParserTest.zip(dir));
        Path raw = dir.resolve("nsr.bin");

        NsrSnapshot.write(parsed, raw, "etag-1");
        NsrData replayed;
        try (InputStream in = Files.newInputStream(raw)) {
            replayed = NsrSnapshot.read(in);
        }

        assertThat(replayed.childToParent()).isEqualTo(parsed.childToParent());
        assertThat(replayed.stopPoints().keySet()).isEqualTo(parsed.stopPoints().keySet());
        for (String id : parsed.stopPoints().keySet()) {
            StopPoint a = parsed.stopPoints().get(id);
            StopPoint b = replayed.stopPoints().get(id);
            assertThat(b.getName()).as(id).isEqualTo(a.getName());
            assertThat(b.getLocation().getLongitude()).as(id).isEqualTo(a.getLocation().getLongitude());
            assertThat(b.getLocation().getLatitude()).as(id).isEqualTo(a.getLocation().getLatitude());
        }
    }

    @Test
    public void aNullNameRoundTrips(@TempDir Path dir) throws Exception {
        NsrData data = new NsrData(Map.of("NSR:Quay:1", new StopPoint("NSR:Quay:1", null, new Location(1.0, 2.0))), Map.of());
        Path raw = dir.resolve("nsr.bin");

        NsrSnapshot.write(data, raw, "e");
        NsrData replayed;
        try (InputStream in = Files.newInputStream(raw)) {
            replayed = NsrSnapshot.read(in);
        }

        assertThat(replayed.stopPoints().get("NSR:Quay:1").getName()).isNull();
        assertThat(replayed.stopPoints().get("NSR:Quay:1").getLocation().getLongitude()).isEqualTo(1.0);
    }

    @Test
    public void headerAndTruncationAreGuarded(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("nsr.bin");
        NsrSnapshot.write(new NsrData(Map.of("A", new StopPoint("A", "a", new Location(0, 0))), Map.of("A", "B")), raw, "e");
        byte[] good = Files.readAllBytes(raw);

        byte[] wrongMagic = good.clone();
        wrongMagic[0] = 'X';
        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(wrongMagic)))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("magic");

        byte[] wrongVersion = good.clone();
        wrongVersion[7] = (byte) (wrongVersion[7] + 1);
        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(wrongVersion)))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("version");

        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(Arrays.copyOf(good, good.length - 3))))
                .isInstanceOf(IOException.class);

        byte[] wrongCount = good.clone();
        wrongCount[wrongCount.length - 1] = (byte) (wrongCount[wrongCount.length - 1] + 1);
        assertThatThrownBy(() -> NsrSnapshot.read(new ByteArrayInputStream(wrongCount)))
                .isInstanceOf(SnapshotFormatException.class).hasMessageContaining("count");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=NsrSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `NsrSnapshot` does not exist.

- [ ] **Step 3: Write NsrSnapshot**

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=NsrSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/NsrSnapshot.java src/test/java/org/entur/vehicles/service/NsrSnapshotTest.java
git commit -m "Add the NSR snapshot format"
```

---

### Task 10: Snapshot-first NSRService

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/NSRService.java`
- Test: `src/test/java/org/entur/vehicles/service/NSRServiceSnapshotTest.java`

**Interfaces:**
- Consumes: `ExportDownloader`, `SnapshotCache`, `SnapshotKey`, `NsrNetexParser`, `NsrSnapshot`, `NsrData`, `PrometheusMetricsService.markSnapshotSource` and `markNsrLoaded`, `NsrNetexParserTest.zip(Path)`, `EtagHttpServer`.
- Produces:
  - `@Autowired NSRService(boolean enabled, String url, ExportDownloader downloader, SnapshotCache snapshots, PrometheusMetricsService metrics)`.
  - The package-private test seam `NSRService(boolean enabled, String url, Map<String, String> childToParent)` is kept.
  - Package-private `void warmUpCache()` (was private) so the test can drive it without Spring.
  - Package-private `void install(NsrData data)`.

- [ ] **Step 1: Write the failing service test**

```java
package org.entur.vehicles.service;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.snapshot.EtagHttpServer;
import org.entur.vehicles.service.snapshot.ExportDownloader;
import org.entur.vehicles.service.snapshot.SnapshotCache;
import org.entur.vehicles.service.snapshot.SnapshotUploader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class NSRServiceSnapshotTest {

    @TempDir
    Path dir;
    private Path storeDir;
    private EtagHttpServer server;
    private SnapshotCache cache;

    @BeforeEach
    public void setUp() throws Exception {
        server = new EtagHttpServer(NsrNetexParserTest.zip(dir), "\"nsr-1\"");
        storeDir = dir.resolve("store");
        cache = SnapshotCache.fromUri(storeDir.toUri().toString(), metrics());
    }

    @AfterEach
    public void tearDown() {
        cache.close();
        server.close();
    }

    private static PrometheusMetricsService metrics() {
        return new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    private NSRService service(SnapshotCache snapshots) {
        return new NSRService(true, server.url(), new ExportDownloader(Duration.ofSeconds(5)), snapshots, metrics());
    }

    private Path expectedObject() {
        return storeDir.resolve("nsr/v" + NsrSnapshot.FORMAT_VERSION + "/nsr-1.bin.gz");
    }

    private static void assertFixtureInstalled(NSRService service) {
        assertThat(service.getStop("NSR:Quay:22").getName()).isEqualTo("Oslo S");
        assertThat(service.getStop("NSR:StopPlace:3").getLocation().getLatitude()).isEqualTo(60.39);
        assertThat(service.ancestorsOf("NSR:Quay:21")).isEqualTo(Set.of("NSR:StopPlace:2", "NSR:StopPlace:1"));
        assertThat(service.getStop("NSR:Quay:999").getName()).withFailMessage("unknown refs still resolve to a bare ref").isNull();
    }

    @Test
    public void firstWarmUpMissesParsesAndUploads() {
        NSRService service = service(cache);

        service.warmUpCache();

        assertFixtureInstalled(service);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(expectedObject()).exists();
        assertThat(server.getCount()).isEqualTo(1);
    }

    @Test
    public void secondWarmUpHitsAndDoesNotDownload() {
        service(cache).warmUpCache();
        cache.lastUpload().join();

        NSRService second = service(cache);
        second.warmUpCache();

        assertThat(server.getCount()).isEqualTo(1);
        assertFixtureInstalled(second);
    }

    @Test
    public void aCorruptSnapshotFallsBackAndIsReplaced() throws Exception {
        Files.createDirectories(expectedObject().getParent());
        Files.writeString(expectedObject(), "garbage");
        NSRService service = service(cache);

        service.warmUpCache();

        assertFixtureInstalled(service);
        assertThat(cache.lastUpload().join()).isEqualTo(SnapshotUploader.Outcome.UPLOADED);
        assertThat(Files.size(expectedObject())).isGreaterThan("garbage".length());
    }

    @Test
    public void aDisabledCacheIsTodaysBehaviour() {
        NSRService service = service(SnapshotCache.disabled());

        service.warmUpCache();

        assertFixtureInstalled(service);
        assertThat(server.headCount()).isZero();
        assertThat(storeDir).doesNotExist();
    }

    @Test
    public void disabledLookupNeverFetches() {
        NSRService service = new NSRService(false, server.url(), new ExportDownloader(Duration.ofSeconds(5)), cache, metrics());

        service.warmUpCache();

        assertThat(server.headCount()).isZero();
        assertThat(server.getCount()).isZero();
        assertThat(service.getStop("NSR:Quay:21").getName()).isNull();
        assertThat(service.ancestorsOf("NSR:Quay:21")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=NSRServiceSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, no five-argument constructor.

- [ ] **Step 3: Rewrite the loading part of NSRService**

Replace the fields, both constructors, `warmUpCache` and `readUrl` with the following. `stopPointCache`, `MAX_ANCESTOR_DEPTH`, `ancestorsByRef`, `getStop`, `ancestorsOf`, `expandWithAncestors`, `flattenAncestors` and `lookup` are unchanged.

```java
    private final String url;
    private final boolean enabled;
    private final ExportDownloader downloader;
    private final SnapshotCache snapshots;
    private final PrometheusMetricsService metrics;

    @Autowired
    public NSRService(
            @Value("${vehicle.nsr.lookup.enabled:false}") boolean enabled,
            @Value("${vehicle.nsr.lookup.url:}") String url,
            ExportDownloader downloader,
            SnapshotCache snapshots,
            PrometheusMetricsService metrics
    ) {
        this.enabled = enabled;
        this.url = url;
        this.downloader = downloader;
        this.snapshots = snapshots;
        this.metrics = metrics;
    }

    /**
     * Test seam: installs a child-to-parent map directly, via the same {@link #install} the
     * real warm-up uses, without downloading or parsing a NeTEx file. Package-private - not
     * part of the public surface. The {@code enabled}/{@code url} it forwards only ever govern
     * {@link #warmUpCache} (never run here) and {@link #getStop} lookup.
     */
    NSRService(boolean enabled, String url, Map<String, String> childToParent) {
        this(enabled, url, null, SnapshotCache.disabled(), null);
        install(new NsrData(Map.of(), childToParent));
    }

    @PostConstruct
    void warmUpCache() {
        if (!enabled) {
            return;
        }
        long start = System.currentTimeMillis();
        Path zip = null;
        Path raw = null;
        try {
            NsrData data = null;
            String source = null;
            SnapshotKey key = null;
            boolean unreadable = false;

            if (snapshots.enabled()) {
                Optional<SnapshotKey> candidate = downloader.head(url)
                        .flatMap(etag -> SnapshotKey.of(NsrSnapshot.DATASET, NsrSnapshot.FORMAT_VERSION, etag));
                if (candidate.isEmpty()) {
                    LOG.info("No ETag for {} - loading the NSR export without a snapshot", url);
                }
                Optional<InputStream> in = candidate.flatMap(snapshots::open);
                if (in.isPresent()) {
                    try (InputStream stream = in.get()) {
                        data = NsrSnapshot.read(stream);
                        key = candidate.get();
                        source = "snapshot";
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("Snapshot {} could not be read - falling back to the NSR export", candidate.get(), e);
                        unreadable = true;
                    }
                }
            }

            if (data == null) {
                zip = Files.createTempFile("nsr-netex", ".zip");
                long downloadStart = System.currentTimeMillis();
                Optional<String> etag = downloader.download(url, zip);
                LOG.info("Download of {} took {} ms", url, System.currentTimeMillis() - downloadStart);
                data = new NsrNetexParser().parse(zip);
                source = "export";
                Optional<SnapshotKey> uploadKey = snapshots.enabled()
                        ? etag.flatMap(e -> SnapshotKey.of(NsrSnapshot.DATASET, NsrSnapshot.FORMAT_VERSION, e))
                        : Optional.empty();
                if (uploadKey.isPresent()) {
                    raw = Files.createTempFile("nsr-snapshot", ".bin");
                    NsrSnapshot.write(data, raw, uploadKey.get().etag());
                    key = uploadKey.get();
                }
            }

            install(data);
            long duration = System.currentTimeMillis() - start;
            if (metrics != null) {
                metrics.markNsrLoaded(duration);
                metrics.markSnapshotSource(NsrSnapshot.DATASET, "snapshot".equals(source));
            }
            LOG.info("NSRService resolved ancestors for {} stop refs.", ancestorsByRef.size());
            LOG.info("NSRService cache warm-up took: {} ms from {} (etag={})", duration, source,
                    key == null ? "none" : key.etag());

            if (raw != null) {
                snapshots.upload(key, raw, unreadable);
                raw = null;
            }
        } catch (IOException e) {
            throw new IllegalStateException("NSR warm-up failed", e);
        } finally {
            deleteQuietly(zip);
            deleteQuietly(raw);
        }
    }

    /** The one place the caches are filled, on both the snapshot and the parse path. */
    void install(NsrData data) {
        stopPointCache.putAll(data.stopPoints());
        ancestorsByRef.putAll(flattenAncestors(data.childToParent()));
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not delete temp file {}", file, e);
        }
    }
```

Remove the now-unused imports (`FileUtils`, `File`, `URL`, `NetexParser`, `NetexEntitiesIndex`, `LocationStructure`, `Quay`, `Quays_RelStructure`, `SiteRefStructure`, `Location`, `HashMap`) and add `java.io.InputStream`, `java.nio.file.Files`, `java.nio.file.Path`, `java.util.Optional`, `org.entur.vehicles.metrics.PrometheusMetricsService`, `org.entur.vehicles.service.snapshot.ExportDownloader`, `org.entur.vehicles.service.snapshot.SnapshotCache`, `org.entur.vehicles.service.snapshot.SnapshotKey`.

Field order matters: `stopPointCache` is initialised inline and its loader reads `enabled`, which is fine because the loader only runs on `get`. The test-seam constructor calls `install` after `this(...)`, so `ancestorsByRef` must be declared before the constructors or initialised inline (it is, as `new ConcurrentHashMap<>()`).

- [ ] **Step 4: Run the NSR tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='NSRService*Test,Nsr*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including `NSRServiceAncestorTest` (test seam unchanged) and `NSRServiceSpringWiringTest` (Spring resolves the single `@Autowired` constructor).

- [ ] **Step 5: Run the whole suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/NSRService.java src/test/java/org/entur/vehicles/service/NSRServiceSnapshotTest.java
git commit -m "Load NSR stop places from a per-export snapshot before falling back to the JAXB parse"
```

---

### Task 11: Helm, docs and spec status

**Files:**
- Modify: `helm/vehicle-positions-2/values.yaml`
- Modify: `helm/vehicle-positions-2/templates/configmap.yaml` (after line 72)
- Modify: `helm/vehicle-positions-2/templates/deployment.yaml` (resources block, lines 55-59)
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-09-02-planned-data-snapshot-design.md` (status line)

- [ ] **Step 1: Add the Helm value and ConfigMap line**

In `values.yaml`, under `configMap:` after the `plannedData:` block:

```yaml
  # Per-export snapshots of the planned data and NSR datasets, so a starting pod skips the
  # NeTEx parses. gs://bucket/prefix per environment; empty disables snapshots entirely.
  snapshotUri: ""
```

In `resources:` at the top of `values.yaml`:

```yaml
  # The NeTEx zip (280 MB) and the raw planned-data snapshot (about 250 MB) coexist briefly on disk.
  ephemeralRequest: 1Gi
```

In `templates/configmap.yaml` after the `vehicle.planned.data.min.service.journeys` line:

```yaml
      vehicle.snapshot.uri={{ .Values.configMap.snapshotUri }}
```

In `templates/deployment.yaml` under `requests:`:

```yaml
              ephemeral-storage: {{ .Values.resources.ephemeralRequest }}
```

- [ ] **Step 2: Render the chart to check the template**

Run: `helm template helm/vehicle-positions-2 -f helm/vehicle-positions-2/env/values-kub-ent-dev.yaml | grep -n "vehicle.snapshot.uri\|ephemeral-storage"`
Expected: two lines, `vehicle.snapshot.uri=` (empty) and `ephemeral-storage: 1Gi`. If `helm` is not installed, inspect the three files by eye and note that in the commit message.

- [ ] **Step 3: Document in CLAUDE.md**

Under "External Service Integrations", after the NeTEx Data item, add:

```markdown
4. **Startup snapshots** (`vehicle.snapshot.uri`, empty = disabled)
   - Package `service.snapshot`; spec `docs/superpowers/specs/2026-09-02-planned-data-snapshot-design.md`
   - `PlannedDataService` and `NSRService` HEAD their export, look for a snapshot object named by dataset, format version and the export's ETag, and replay it instead of parsing
   - A miss parses as before, then uploads a snapshot in the background with a does-not-exist precondition; every failure on the snapshot path falls back to the parse
   - `gs://bucket/prefix` in the environments, `file:///dir` for local runs
```

In "Important Behavioral Notes" add:

```markdown
8. **Snapshot formats are versioned in the object path**: bump `PlannedDataSnapshot.FORMAT_VERSION` or `NsrSnapshot.FORMAT_VERSION` whenever the record layout or the extracted fields change; old and new images then never read each other's snapshots
```

- [ ] **Step 4: Update the spec status**

Change the spec's `Status:` line to `Implemented 2026-09-02; measured section pending the dev rollout`.

- [ ] **Step 5: Run the whole suite one last time**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add helm/vehicle-positions-2 CLAUDE.md docs/superpowers/specs/2026-09-02-planned-data-snapshot-design.md
git commit -m "Wire vehicle.snapshot.uri through Helm and document the snapshot path"
```

---

## After the plan: rollout (manual, not part of the tasks)

1. Open a PR with the branch; merge with `snapshotUri` empty everywhere.
2. Provision a bucket in `ent-vpos-dev` (standard storage, uniform access, 7-day lifecycle delete rule) and grant `roles/storage.objectUser` on it to the Google service account behind the `application` Kubernetes service account.
3. Set `configMap.snapshotUri: gs://<bucket>/snapshots` in `env/values-kub-ent-dev.yaml`, deploy, and read the logs: the first pod logs `Snapshot miss` twice, `Planned data loaded ... from export`, `NSRService cache warm-up ... from export`, then two `Snapshot ... uploaded` lines. Every later pod logs `Snapshot hit` and `from snapshot`.
4. Append a "Measured" section to the spec with the hit-path durations from "Planned data loaded in" and "NSRService cache warm-up took", the object sizes from the upload log lines, and the upload durations.
5. Repeat for tst and prd.
