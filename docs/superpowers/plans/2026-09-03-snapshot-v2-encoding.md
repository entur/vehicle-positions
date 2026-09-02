# Snapshot v2: compact encoding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the planned-data snapshot from 118 MB to about 70 MB, and its decode cost by more, by replacing repeated NeTEx id strings with dictionary indices, packing structured local ids, and delta-encoding geometry. The dataset a pod ends up with is unchanged.

**Architecture:** The snapshot stops being a tee of raw extractor records and becomes a serialisation of `PlannedDataset.Builder`'s completed state, written after the parse. Every reference to another entity becomes a varint index into an earlier section, with a literal escape so dangling references survive.

**Tech Stack:** Java 25, Spring Boot, `DataOutputStream`/`DataInputStream` over gzip, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-03-snapshot-v2-encoding-design.md` (extends `2026-09-02-planned-data-snapshot-design.md`)

**Scope change, 2026-09-03:** this plan originally paired the encoding with an operating-date window that dropped dated service journeys beyond a horizon. The window is abandoned — the map must answer for every future dated service journey the export carries. Tasks 1 and 2 landed before the change and are unaffected; Task 3 below removes what the window left behind.

## Global Constraints

- Build and test with `JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2/libexec/openjdk.jdk/Contents/Home mvn ...`; the shell default JDK is 17 and the enforcer requires 25+.
- The bucket is a cache, never a dependency: every failure on the snapshot path logs and falls back to the full parse. No new exception may escape to the load.
- Nothing filters records. A snapshot holds exactly what the parse produced.
- A hit must be indistinguishable from a parse: identical `Stats` (including `duplicateIds`) and identical lookups.
- `PlannedDataSnapshot.FORMAT_VERSION` is 2; `NsrSnapshot` is untouched.
- Object names keep their v1 shape, `dataset/v<version>/<etag>.bin.gz`.
- Never stage `src/main/resources/logback-test.xml`.
- End every commit message with `Claude-Session: https://claude.ai/code/session_019KAdV8XMMzDMz1qCZYeNKo`.

## Landed before the scope change

- **Task 1 — varint, zigzag and string primitives** (`SnapshotIo`), commit `2cffd35`.
- **Task 2 — id codec with the codespace prefix dictionary** (`IdCodec`), commits `6cd2dc9`, `e3ae1cf`.
- **Task 5 (old numbering) — the v2 writer**, commit `2441475`. Kept, with its window arguments removed by Task 3 below.

---

### Task 3: Remove the window

**Files:**
- Revert: `src/main/java/org/entur/vehicles/service/snapshot/SnapshotKey.java` and `src/test/java/org/entur/vehicles/service/snapshot/SnapshotKeyTest.java` to their state at commit `e3ae1cf`
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java`
- Delete: `src/test/java/org/entur/vehicles/service/planned/PlannedDatasetWindowTest.java`
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSnapshot.java` and `src/test/java/org/entur/vehicles/service/planned/PlannedDataSnapshotTest.java`

**Interfaces:**
- Removed: `SnapshotKey`'s fourth component and its four-argument `of(...)`; `PlannedDataset.Builder.applyFutureWindow(LocalDate, Integer)`; the `datedServiceJourneysDropped` component of `Stats`; the `futureDays` and `asOf` parameters of `PlannedDataSnapshot.write(...)` and the two header fields they wrote.
- **Kept, because the writer needs them:** the builder's package-private state views, `seedDuplicateIds(int)`, `duplicateIds()`, and the package-private `RawDatedServiceJourney`.
- Produces: `PlannedDataSnapshot.write(PlannedDataset.Builder builder, Path file, String etag)`.

- [ ] **Step 1: Remove the window from the builder** — delete `applyFutureWindow`, its dropped-count field, and the `Stats` component; update the single `Stats` construction site.
- [ ] **Step 2: Delete `PlannedDatasetWindowTest`**
- [ ] **Step 3: Revert `SnapshotKey` and its test** to the `e3ae1cf` state — `git show e3ae1cf:<path>` gives the exact content for each of the two files.
- [ ] **Step 4: Strip the window from the writer** — drop the two parameters, drop the two header fields, and update the tests that assert the header layout.
- [ ] **Step 5: Run the whole suite** — `JAVA_HOME=... mvn -o test`. Expect the count to fall by the six window tests and stay otherwise green.
- [ ] **Step 6: Commit** — `refactor: drop the dated-service-journey window`

---

### Task 4: The v2 reader

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSnapshot.java`
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSink.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataSnapshotTest.java`

**Interfaces:**
- Consumes: the byte layout documented in `.superpowers/sdd/2026-09-03-snapshot-v2-window-and-encoding/task-5-report.md`, which is the authority on what the writer emits — including that an operating-day date is `zigzag(epochDay) + 1`.
- Produces: `static void replayV2(InputStream in, PlannedDataSink sink)` — named so it can live beside the v1 `replay` until Task 5 deletes that; a default no-op `seedDuplicateIds(int)` on `PlannedDataSink`, which `PlannedDataset.Builder` already implements.

Sections are read in write order, each section's ids accumulating into an array so later sections resolve their references. Bad magic, a wrong version, a truncated file and a record-count mismatch all throw `SnapshotFormatException`.

- [ ] **Step 1: Write the failing round-trip test** — build the Task-5 fixture builder (dangling pattern, link, journey, line and operating-day references; empty and odd-length geometry; null names and public codes), write it, replay it into a fresh builder, and assert the two datasets have equal `Stats` and equal lookups for one id of every kind.
- [ ] **Step 2: Run it and watch it fail**
- [ ] **Step 3: Implement the reader**
- [ ] **Step 4: Add the guard tests** — bad magic, wrong version, truncation mid-section, count mismatch.
- [ ] **Step 5: Whole suite green**
- [ ] **Step 6: Commit** — `feat: read the v2 planned-data snapshot`

---

### Task 5: Service wiring

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java`
- Delete: `src/main/java/org/entur/vehicles/service/planned/TeeSink.java` and `src/test/java/org/entur/vehicles/service/planned/TeeSinkTest.java`
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataSnapshot.java` (delete the v1 `Writer` and the v1 `replay`; rename `replayV2` to `replay`)
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataServiceSnapshotTest.java`

The load flow becomes: head → key → try replay → on miss download, parse into the builder, and when `skipped == 0` and a key exists write the snapshot from the builder → `build()` → install → upload. No tee, no partial-file bookkeeping during the parse.

- [ ] **Step 1: Write the failing tests** — with `FileSnapshotStore`: a miss parses, writes and uploads; a second load hits and produces a dataset with identical `Stats` and lookups; `skipped > 0` uploads nothing; an unreadable snapshot falls back to the parse and replaces the object.
- [ ] **Step 2: Run them and watch them fail**
- [ ] **Step 3: Rewrite the load flow; delete `TeeSink`, the v1 `Writer` and the v1 `replay`**
- [ ] **Step 4: Whole suite green**
- [ ] **Step 5: Commit** — `feat: write and read the v2 snapshot from the planned data service`

---

### Task 6: Documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-09-03-snapshot-v2-encoding-design.md`

- [ ] **Step 1: Set the status to implemented, with the date**
- [ ] **Step 2: Note that the snapshot is written from the builder after the parse and that `TeeSink` is gone**
- [ ] **Step 3: Commit** — `docs: record the v2 snapshot encoding as implemented`

---

## Notes for the executor

- Task 3 changes the `Stats` record again; do it before Tasks 4 and 5.
- Tasks 3, 4 and 5 all edit `PlannedDataSnapshot.java`; run them in order, never in parallel.
- The dev bucket holds v1 objects. They are never read again — the version is in the path — and the bucket's 7-day lifecycle removes them.
