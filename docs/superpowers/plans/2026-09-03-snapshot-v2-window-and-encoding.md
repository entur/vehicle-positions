# Snapshot v2: operating-date window and compact encoding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the planned-data snapshot from 118 MB to roughly 33 MB and the dated-service-journey map from 2.23M entries to about 200k, by adding a configurable future-days window and a dictionary-and-varint encoding, in one format bump.

**Architecture:** The snapshot stops being a tee of raw extractor records and becomes a serialisation of `PlannedDataset.Builder`'s completed state, written after the parse. Ids are split into a codespace/type prefix dictionary plus a packed local part; every reference to another entity becomes a varint index into an earlier section, with a literal escape so dangling references survive. The window is applied to the builder before both `build()` and the write, so the parse path and the replay path drop the same journeys.

**Tech Stack:** Java 25, Spring Boot, `DataOutputStream`/`DataInputStream` over gzip, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-03-snapshot-v2-window-and-encoding-design.md` (extends `2026-09-02-planned-data-snapshot-design.md`)

## Global Constraints

- Build and test with `JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2/libexec/openjdk.jdk/Contents/Home mvn ...`; the shell default JDK is 17 and the enforcer requires 25+.
- The bucket is a cache, never a dependency: every failure on the snapshot path logs and falls back to the full parse. No new exception may escape to the load.
- `vehicle.planned.data.dsj.future-days` unset means unlimited, which must be byte-for-byte today's behaviour apart from the format bump.
- A hit must be indistinguishable from a parse: identical `Stats` (including `duplicateIds`) and identical lookups, for the same window.
- `PlannedDataSnapshot.FORMAT_VERSION` becomes 2; `NsrSnapshot` is untouched.
- Never stage `src/main/resources/logback-test.xml`.

---

### Task 1: Varint and string primitives

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotIo.java`
- Test: `src/test/java/org/entur/vehicles/service/snapshot/SnapshotIoTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static void writeVarInt(DataOutputStream, long)`, `static long readVarInt(DataInputStream)`, `static void writeZigZag(DataOutputStream, long)`, `static long readZigZag(DataInputStream)`, `static void writeString(DataOutputStream, String)` (null-safe), `static String readString(DataInputStream)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void varIntRoundTripsBoundaries() throws Exception {
    for (long v : new long[]{0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE, Long.MAX_VALUE}) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            SnapshotIo.writeVarInt(out, v);
        }
        assertEquals(v, SnapshotIo.readVarInt(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }
}

@Test
void zigZagRoundTripsNegatives() throws Exception { /* -1, -128, Long.MIN_VALUE, 0, 1 */ }

@Test
void stringRoundTripsNullAndNonAscii() throws Exception { /* null, "", "Bodø", 3000-char string */ }
```

- [ ] **Step 2: Run them and watch them fail** — `mvn -o test -Dtest=SnapshotIoTest`
- [ ] **Step 3: Implement.** Unsigned LEB128 for varint; zigzag is `(v << 1) ^ (v >> 63)`. `writeString` writes varint 0 for null, else varint `utf8.length + 1` then the bytes; no `writeUTF`, so the 64 KB limit is gone.
- [ ] **Step 4: Tests pass**
- [ ] **Step 5: Commit** — `feat: add varint and string primitives for the snapshot codec`

---

### Task 2: Id codec with the prefix dictionary

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/snapshot/IdCodec.java`
- Test: `src/test/java/org/entur/vehicles/service/snapshot/IdCodecTest.java`

**Interfaces:**
- Consumes: `SnapshotIo`.
- Produces: `IdCodec.Writer` with `void writeId(DataOutputStream, String)` and `void writeTable(DataOutputStream)`; `IdCodec.Reader` with `void readTable(DataInputStream)` and `String readId(DataInputStream)`.

Prefix is everything up to and including the last `:`; an id with no `:` gets the empty prefix. Kind byte: 0 raw (varint length + UTF-8), 1 `djj-` + 32 hex (16 bytes), 2 32 hex (16 bytes), 3 UUID (16 bytes), 4 all digits up to 18 characters (varint). Kinds 1-4 must reproduce the original string exactly — lower-case hex only, no leading zero loss for kind 4 (reject a local part with a leading `0` unless it is exactly `"0"`).

- [ ] **Step 1: Write the failing tests** — round trip `RUT:DatedServiceJourney:djj-0933f40da2fc97b867577a5489601564`, `ATB:ServiceJourney:18_251215112551387_10`, `NSR:Quay:20388`, a UUID service link, `NSR:Quay:007` (must take kind 0, not 4), an id with no colon, and 400 ids sharing 3 prefixes (assert the table holds 3 entries).
- [ ] **Step 2: Run them and watch them fail**
- [ ] **Step 3: Implement.** Writer keeps `LinkedHashMap<String,Integer>` for prefixes and emits the table on demand; reader loads the table into a `String[]`.
- [ ] **Step 4: Tests pass**
- [ ] **Step 5: Commit** — `feat: add the snapshot id codec with a codespace prefix dictionary`

---

### Task 3: Snapshot key variants

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotKey.java`
- Test: `src/test/java/org/entur/vehicles/service/snapshot/SnapshotKeyTest.java`

**Interfaces:**
- Produces: `SnapshotKey(String dataset, int formatVersion, String etag, String variant)`, `of(dataset, formatVersion, rawEtag)` (empty variant), `of(dataset, formatVersion, rawEtag, variant)`, unchanged `objectName(prefix)` semantics plus the variant suffix.

- [ ] **Step 1: Write the failing tests** — empty variant gives today's `planned-data/v2/<etag>.bin.gz`; variant `f7_2026-09-02` gives `planned-data/v2/<etag>_f7_2026-09-02.bin.gz`; a variant with unsafe characters is normalised the way the ETag is; a null variant behaves as empty.
- [ ] **Step 2: Run them and watch them fail**
- [ ] **Step 3: Implement.** Reuse `normaliseEtag` for the variant.
- [ ] **Step 4: Tests pass** — including the existing `SnapshotKeyTest` cases
- [ ] **Step 5: Commit** — `feat: allow a variant suffix in snapshot object names`

---

### Task 4: Builder window, state access, duplicate seeding

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDatasetWindowTest.java`

**Interfaces:**
- Produces on `PlannedDataset.Builder`: `int applyFutureWindow(LocalDate asOf, Integer futureDays)` returning the dropped count (no-op returning 0 when `futureDays` is null); `void seedDuplicateIds(int)`; package-private unmodifiable views `operators()`, `lines()`, `operatingDays()`, `linkGeometry()`, `patternLinks()`, `serviceJourneyPattern()`, `serviceJourneyLine()`, `rawDatedServiceJourneys()` for the writer.
- Produces on `Stats`: a new trailing component `int datedServiceJourneysDropped`.

`applyFutureWindow` resolves each raw journey's operating day through `operatingDays`, parses the date with `LocalDate.parse`, and removes the entry when the date is after `asOf.plusDays(futureDays)`. An unresolvable or unparseable date keeps the entry. It records the dropped count so `build()` can put it in `Stats`.

- [ ] **Step 1: Write the failing tests** — a journey on `asOf.plusDays(7)` is kept, one on `plusDays(8)` is dropped, a past-dated one is kept, one whose operating day is unknown is kept, one whose date does not parse is kept, `futureDays` null drops nothing, and the returned and `Stats` counts agree.
- [ ] **Step 2: Run them and watch them fail**
- [ ] **Step 3: Implement.** Every existing `new Stats(...)` call site and every test that pattern-matches `Stats` needs the new component; `mvn -o test-compile` finds them all.
- [ ] **Step 4: Tests pass**
- [ ] **Step 5: Commit** — `feat: filter dated service journeys by an operating-date window`

---

### Task 5: v2 writer

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSnapshot.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataSnapshotTest.java`

**Interfaces:**
- Produces: `static void write(PlannedDataset.Builder builder, Path file, String etag, Integer futureDays, LocalDate asOf)`; `FORMAT_VERSION = 2`; the v1 `Writer` class and its `PlannedDataSink` implementation are deleted.

Section order and record layout come from the spec. References are written by a helper that takes the referenced section's index map: null → varint 0; a hit → varint `index + 1`; a miss → varint `size + 1` followed by the literal id through `IdCodec`. Sections are written in the order operators, lines, operatingDays, serviceLinks, journeyPatterns, serviceJourneys, datedServiceJourneys.

The prefix table is written **first**, before any record, so the reader can resolve ids as it goes. That needs the dictionary complete up front: make one pass over every id the builder holds (keys of all seven maps plus every reference value) to populate it, then emit the table, then the sections. The pass costs one traversal and no extra retained memory beyond the few hundred prefixes.

- [ ] **Step 1: Write the failing tests** — build a small builder by hand (2 operators, 2 lines, a pattern with one known and one dangling link, a journey with a dangling pattern ref, a dated journey with a dangling day ref, one link with empty geometry, one with an odd-length array), write it, and assert the file starts with `VPP2` and version 2.
- [ ] **Step 2: Run them and watch them fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Tests pass**
- [ ] **Step 5: Commit** — `feat: write the planned-data snapshot from the builder in the v2 encoding`

---

### Task 6: v2 reader

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSnapshot.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataSnapshotTest.java`

**Interfaces:**
- Produces: `static void replay(InputStream in, PlannedDataSink sink, Integer futureDays, LocalDate asOf)` — same signature shape as v1's `replay` plus the window it expects; still feeds a `PlannedDataSink`, so `PlannedDataset.Builder` stays the consumer.

Header mismatch on magic, version, `futureDays` or `asOfEpochDay` throws `SnapshotFormatException`. Sections are read in write order; each section's ids accumulate into an array so later sections resolve their references. `duplicateIds` from the header goes to `sink` via `seedDuplicateIds` when the sink is a builder (add a default no-op method on `PlannedDataSink` so the interface stays honest).

- [ ] **Step 1: Write the failing tests** — round trip the Task 5 fixture through a fresh builder and assert the rebuilt dataset's `Stats` and lookups equal the original's; a snapshot written for `futureDays=7` refused when the reader expects 14; one written for yesterday refused; truncated file, bad magic, wrong version, count mismatch each throw.
- [ ] **Step 2: Run them and watch them fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Tests pass**
- [ ] **Step 5: Commit** — `feat: read the v2 planned-data snapshot`

---

### Task 7: Service wiring

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java`
- Modify: `src/main/resources/application.properties`
- Delete: `src/main/java/org/entur/vehicles/service/planned/TeeSink.java` and its test
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataServiceSnapshotTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: constructor argument `@Value("${vehicle.planned.data.dsj.future-days:}") Integer futureDays` (empty means null) and a package-private `Supplier<LocalDate> today` seam defaulting to `() -> LocalDate.now(ZoneId.of("Europe/Oslo"))`.

Load flow becomes: head → key (with variant `f<N>_<asOf>` when windowed) → try replay → on miss download, parse into the builder, `applyFutureWindow`, and, when `skipped == 0` and a key exists, `PlannedDataSnapshot.write(builder, raw, etag, futureDays, asOf)` → `build()` → install → upload. The replay path calls `applyFutureWindow` too, so a snapshot that somehow carries extra journeys is still trimmed.

- [ ] **Step 1: Write the failing tests** — with `FileSnapshotStore`: a miss parses, writes and uploads an object whose name carries the variant; a second load hits and produces an identical dataset; a load with a different `future-days` misses; `skipped > 0` uploads nothing; an unreadable snapshot falls back and replaces the object.
- [ ] **Step 2: Run them and watch them fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Tests pass** — plus the whole suite: `mvn -o test`
- [ ] **Step 5: Commit** — `feat: wire the snapshot window into the planned data service`

---

### Task 8: Helm and configuration

**Files:**
- Modify: `helm/vehicle-positions-2/values.yaml`, `templates/configmap.yaml`, `env/values-kub-ent-dev.yaml`

- [ ] **Step 1: Add `dsjFutureDays: ""` to `values.yaml` under `configMap`**
- [ ] **Step 2: Add `vehicle.planned.data.dsj.future-days={{ .Values.configMap.dsjFutureDays }}` to the configmap template**
- [ ] **Step 3: Set `dsjFutureDays: 7` in the dev values only**
- [ ] **Step 4: Verify** — `helm template vp helm/vehicle-positions-2 -f helm/vehicle-positions-2/env/values-kub-ent-dev.yaml | grep dsj` shows 7, and the tst and prd renders show an empty value
- [ ] **Step 5: Commit** — `feat: expose the dated-service-journey horizon in the chart`

---

### Task 9: Documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-09-03-snapshot-v2-window-and-encoding-design.md` (status), `CLAUDE.md`

- [ ] **Step 1: Set the spec status to implemented, with the date**
- [ ] **Step 2: Note in `CLAUDE.md` that the snapshot is written from the builder after the parse, that `FORMAT_VERSION` is 2, and that the window is behavioural — journeys beyond the horizon resolve to null**
- [ ] **Step 3: Commit** — `docs: record the v2 snapshot format and the window`

---

## Notes for the executor

- Task 4 changes the `Stats` record, so it touches every construction site and any test that
  destructures it. Do that task before 5 and 6.
- Tasks 5 and 6 edit the same file; run them in order, never in parallel.
- The dev bucket already holds v1 objects. They are never read again (the version is in the
  path) and the 7-day lifecycle removes them.
