# Republishing estimated timetables on situation changes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a situation changes, republish the stored estimated timetables it affects, so an active `timetables` subscription learns about the disruption without waiting for the journey's producer to send another ET message.

**Architecture:** A new `SituationTriggeredRepublisher` collects the identifier refs named by a situation's previous and current versions, scans the stored timetables for journeys touching any of them, and re-emits those journeys onto the existing ET sink. `SituationRepository.add()` hands off to it without blocking; a single worker thread coalesces bursts into one scan and paces emission in chunks.

**Tech Stack:** Java 21, Spring Boot, Reactor `Sinks.Many`, JUnit 5 + Mockito + AssertJ.

**Design spec:** `docs/superpowers/specs/2026-08-06-sx-timetable-republish-design.md`

## Global Constraints

- Package root is `org.entur.vehicles`. Work continues on the **existing** branch `siri_sx_et_join`. Do not create a branch.
- The whole SX feature — repository, mapper, filter, GraphQL feed, startup snapshot — and the ET join with its `SituationMatcher` already exist and are committed. Do not reimplement any of it.
- `SituationRepository` is modified in Task 3 and **only** as described there. `TimetableRepository`, `SituationMapper`, `SituationMatcher`, `QueryFilter`, `SituationFilter`, `AutoPurgingTimetableMap`, the SX ingest paths and everything VM-related must NOT be modified.
- A republished message is the stored `EstimatedTimetableUpdate` instance, emitted through `EstimatedTimetableUpdateRxPublisher.publishUpdate(...)`. It must be **indistinguishable** from an ordinary ET update. No new GraphQL field, no schema change.
- Situation-triggered republishes must NOT call `metricsService.markTimetableUpdate(...)` — that counter measures ingest, and inflating it would corrupt an existing operational signal.
- Discovery is deliberately **looser** than the read-path match rule: it ignores validity windows and `progress`, testing only whether a situation *names* something the journey touches. Over-republishing is correct; missing a republish is not.
- Operator refs are NOT a trigger dimension. `SituationMatcher` does not match on operator, and this must stay consistent with it.
- No new dependency in `pom.xml`. No test may perform network I/O.
- Build and test with `mvn`. Full suite: `mvn clean test` — **128 tests currently pass.**
- No Claude/AI attribution in commit messages — match the existing terse style (`git log --oneline`).

## The fixture trap that has already cost this branch once

`AbstractUpdate.getServiceJourney()` does **not** return the field of that name. When a `datedServiceJourney` is set it returns `datedServiceJourney.getServiceJourney()`, which is null if that inner journey was never populated:

```java
public ServiceJourney getServiceJourney() {
    if (datedServiceJourney != null) {
      return datedServiceJourney.getServiceJourney();
    }
    ...
}
```

**Always use the two-arg `DatedServiceJourney(id, serviceJourney)` constructor in fixtures.** That is what `TimetableRepository` actually builds. A one-arg fixture makes the service-journey dimension silently never fire while the test still passes for the wrong reason.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java` (new) | Ref-set union, candidate discovery, chunked emission, worker thread, counters |
| `src/main/java/org/entur/vehicles/repository/SituationRepository.java` (modify) | Capture the previous version; hand off to the republisher |
| `src/main/resources/application.properties` (modify) | Chunk size and delay |
| `src/main/resources/Usage.md` (modify) | Document the behaviour |
| `src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java` (new) | Unit tests for discovery, coalescing, pacing |
| `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java` (modify) | End-to-end subscription tests |

## Task Overview

| # | Deliverable |
|---|---|
| 1 | Discovery: trigger ref set + candidate scan, both pure and synchronous |
| 2 | The worker: subscriber short-circuit, coalescing, chunked emission, counters, lifecycle |
| 3 | Wiring into `SituationRepository`, configuration, end-to-end subscription tests, docs |

---

### Task 1: Discovery

Two pure pieces with no threading, no emission and no Spring lifecycle: what refs a situation change triggers on, and which stored journeys touch them.

**Files:**
- Create: `src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java`
- Test: `src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java`

**Interfaces:**
- Consumes: `SituationUpdate.getAffects()`; `Affects.getLineRefs()/getStopRefs()/getServiceJourneyIds()/getDatedServiceJourneyIds()` (all `Set<String>`, never null); `AutoPurgingTimetableMap` (a `ConcurrentHashMap<StorageKey, EstimatedTimetableUpdate>`); `EstimatedTimetableUpdate.getLine()/getServiceJourney()/getDatedServiceJourney()/getCalls()`.
- Produces:
  - `static Set<String> triggerRefs(SituationUpdate previous, SituationUpdate current)` — union of the refs both versions name; `previous` may be null.
  - `List<EstimatedTimetableUpdate> findAffected(Set<String> refs)` — stored journeys touching any of those refs.
  - `long getSkippedCount()` — journeys skipped on `ConcurrentModificationException`.
  - Constructor `SituationTriggeredRepublisher(AutoPurgingTimetableMap, EstimatedTimetableUpdateRxPublisher)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java`:

```java
package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class SituationTriggeredRepublisherTest {

    private AutoPurgingTimetableMap timetableMap;
    private SituationTriggeredRepublisher republisher;

    @BeforeEach
    public void setUp() {
        timetableMap = new AutoPurgingTimetableMap(Duration.parse("PT1M"), Duration.parse("PT10M"));
        republisher = new SituationTriggeredRepublisher(
                timetableMap, new EstimatedTimetableUpdateRxPublisher());
    }

    private SituationUpdate situation() {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber("TST:SituationNumber:1");
        situation.setCodespace(Codespace.getCodespace("TST"));
        situation.setAffects(new Affects());
        return situation;
    }

    /**
     * Stores a journey under a key built the way TimetableRepository builds one. The two-arg
     * DatedServiceJourney constructor is required: AbstractUpdate.getServiceJourney()
     * delegates through datedServiceJourney, so a one-arg fixture would leave the
     * service-journey dimension permanently null.
     */
    private EstimatedTimetableUpdate storeJourney(String lineRef,
                                                  String serviceJourneyId,
                                                  String datedServiceJourneyId,
                                                  String... stopRefs) {
        EstimatedTimetableUpdate timetable = new EstimatedTimetableUpdate();
        timetable.setCodespace(Codespace.getCodespace("TST"));
        timetable.setLine(new Line(lineRef));
        timetable.setDatedServiceJourney(new DatedServiceJourney(
                datedServiceJourneyId, new ServiceJourney(serviceJourneyId)));
        for (String stopRef : stopRefs) {
            Call call = new Call();
            call.setStopPoint(new StopPoint(stopRef));
            timetable.addCall(call);
        }
        timetableMap.put(
                new StorageKey(Codespace.getCodespace("TST"), null, lineRef, serviceJourneyId, datedServiceJourneyId),
                timetable);
        return timetable;
    }

    @Test
    public void testTriggerRefsUnionsPreviousAndCurrentVersions() {
        SituationUpdate previous = situation();
        previous.getAffects().addStopPoint(new StopPoint("NSR:Quay:A"));

        SituationUpdate current = situation();
        current.getAffects().addStopPoint(new StopPoint("NSR:Quay:B"));

        assertThat(SituationTriggeredRepublisher.triggerRefs(previous, current))
                .withFailMessage("a situation narrowing from A to B must still republish the "
                        + "journeys calling at A, which only the previous version names")
                .containsExactlyInAnyOrder("NSR:Quay:A", "NSR:Quay:B");
    }

    @Test
    public void testTriggerRefsHandlesAFirstTimeSituation() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));

        assertThat(SituationTriggeredRepublisher.triggerRefs(null, current))
                .containsExactly("TST:Line:1");
    }

    @Test
    public void testTriggerRefsCollectsEveryMatchDimensionButNotOperator() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));
        current.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));
        current.getAffects().addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));
        current.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:1"));
        current.getAffects().addOperator(new Operator("TST:Operator:1"));

        assertThat(SituationTriggeredRepublisher.triggerRefs(null, current))
                .withFailMessage("operator is not a match dimension in SituationMatcher, so it "
                        + "must not be a trigger dimension here either")
                .containsExactlyInAnyOrder(
                        "TST:Line:1",
                        "NSR:Quay:1",
                        "TST:ServiceJourney:1",
                        "TST:DatedServiceJourney:1");
    }

    @Test
    public void testFindsAffectedJourneyByLine() {
        EstimatedTimetableUpdate journey =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:Line:1"))).containsExactly(journey);
    }

    @Test
    public void testFindsAffectedJourneyByServiceJourney() {
        EstimatedTimetableUpdate journey =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:ServiceJourney:1"))).containsExactly(journey);
    }

    @Test
    public void testFindsAffectedJourneyByDatedServiceJourney() {
        EstimatedTimetableUpdate journey =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:DatedServiceJourney:1"))).containsExactly(journey);
    }

    @Test
    public void testFindsAffectedJourneyByAnyCalledAtStop() {
        EstimatedTimetableUpdate journey = storeJourney(
                "TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1",
                "NSR:Quay:1", "NSR:Quay:2", "NSR:Quay:3");

        assertThat(republisher.findAffected(Set.of("NSR:Quay:3")))
                .withFailMessage("a stop anywhere in the journey counts, not just the first call")
                .containsExactly(journey);
    }

    @Test
    public void testDoesNotFindAnUnrelatedJourney() {
        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of("TST:Line:999", "NSR:Quay:999"))).isEmpty();
    }

    @Test
    public void testEmptyRefSetFindsNothing() {
        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        assertThat(republisher.findAffected(Set.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o test -Dtest=SituationTriggeredRepublisherTest`

Expected: FAIL — compilation error, `SituationTriggeredRepublisher` does not exist.

Do not delete the operator assertion — it is the only thing keeping the trigger dimensions aligned with `SituationMatcher`. `Operator(String id)` and `Affects.addOperator(Operator)` both exist.

- [ ] **Step 3: Create SituationTriggeredRepublisher with discovery only**

Create `src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java`:

```java
package org.entur.vehicles.repository;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Republishes stored estimated timetables when a situation affecting them changes.
 * <p>
 * The {@code timetables} subscription is fed only by {@link EstimatedTimetableUpdateRxPublisher},
 * and the {@code situations} field on an estimated timetable is resolved once per emitted event.
 * Without this, a situation that appears, changes or closes stays invisible to a subscriber until
 * that journey's producer happens to send another ET message - which for a quiet producer may be
 * never, and which for a closing situation means the disruption lingers on the client's display.
 */
@Component
public class SituationTriggeredRepublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SituationTriggeredRepublisher.class);

    private final AutoPurgingTimetableMap timetableMap;
    private final EstimatedTimetableUpdateRxPublisher etPublisher;

    private final AtomicLong skippedCount = new AtomicLong();

    public SituationTriggeredRepublisher(@Autowired AutoPurgingTimetableMap timetableMap,
                                         @Autowired EstimatedTimetableUpdateRxPublisher etPublisher) {
        this.timetableMap = timetableMap;
        this.etPublisher = etPublisher;
    }

    /**
     * The identifier refs a situation change should trigger on: everything either version names.
     * <p>
     * The previous version is required for two cases where the new one alone is not enough. When a
     * situation closes, the matcher excludes it, so matching the new state finds no journeys at all -
     * exactly the case that most needs to reach the client. When a situation narrows, say from one
     * stop to another, the new state no longer names the stop whose journeys must be told.
     * <p>
     * Operator refs are deliberately absent: {@code SituationMatcher} does not match on operator,
     * and triggering on it would republish journeys whose situation list cannot have changed.
     */
    static Set<String> triggerRefs(SituationUpdate previous, SituationUpdate current) {
        Set<String> refs = new HashSet<>();
        addRefs(refs, previous);
        addRefs(refs, current);
        return refs;
    }

    private static void addRefs(Set<String> refs, SituationUpdate situation) {
        if (situation == null || situation.getAffects() == null) {
            return;
        }
        Affects affects = situation.getAffects();
        refs.addAll(affects.getLineRefs());
        refs.addAll(affects.getStopRefs());
        refs.addAll(affects.getServiceJourneyIds());
        refs.addAll(affects.getDatedServiceJourneyIds());
    }

    /**
     * Stored journeys touching any of these refs.
     * <p>
     * Deliberately looser than the read-path match rule: validity windows and {@code progress} are
     * ignored, so this asks only whether the situation names something the journey touches. Some
     * journeys are therefore republished whose situation list did not actually change. That is the
     * safe direction - a redundant republish carries data the client already has and is applied
     * idempotently, whereas a missed one leaves a disruption on screen after it has ended. Computing
     * an exact before/after diff would need per-subscription state this service does not keep.
     */
    List<EstimatedTimetableUpdate> findAffected(Set<String> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        List<EstimatedTimetableUpdate> affected = new ArrayList<>();
        // ConcurrentHashMap iteration is weakly consistent, so this is safe while ingest writes.
        for (EstimatedTimetableUpdate timetable : timetableMap.values()) {
            try {
                if (isAffected(timetable, refs)) {
                    affected.add(timetable);
                }
            } catch (ConcurrentModificationException e) {
                // TimetableRepository.add() mutates a stored update in place, including
                // getCalls().clear(), so a journey being updated right now can throw here.
                // Skipping is safe: that journey is mid-update, so an ET event for it is about
                // to be published anyway, carrying the fresh situations with it.
                skippedCount.incrementAndGet();
            }
        }
        return affected;
    }

    private static boolean isAffected(EstimatedTimetableUpdate timetable, Set<String> refs) {
        if (timetable.getLine() != null && refs.contains(timetable.getLine().getLineRef())) {
            return true;
        }
        if (timetable.getServiceJourney() != null && refs.contains(timetable.getServiceJourney().getId())) {
            return true;
        }
        if (timetable.getDatedServiceJourney() != null
                && refs.contains(timetable.getDatedServiceJourney().getId())) {
            return true;
        }
        List<Call> calls = timetable.getCalls();
        if (calls != null) {
            for (Call call : calls) {
                if (call.getStopPoint() != null && refs.contains(call.getStopPoint().getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    public long getSkippedCount() {
        return skippedCount.get();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -o test -Dtest=SituationTriggeredRepublisherTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn -o clean test`
Expected: PASS, 136 tests (128 + 8). Output pristine.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java \
        src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java
git commit -m "Adding situation-to-timetable discovery for republishing"
```

---

### Task 2: The worker

Turns discovery into a working mechanism: skip when nobody is listening, coalesce bursts into one scan, pace emission so one situation cannot monopolise a best-effort sink.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java`
- Test: `src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java`

**Interfaces:**
- Consumes: `triggerRefs(...)`, `findAffected(...)` and `getSkippedCount()` from Task 1; `EstimatedTimetableUpdateRxPublisher.currentSubscribers()` and `.publishUpdate(EstimatedTimetableUpdate)`.
- Produces:
  - `void onSituationChanged(SituationUpdate previous, SituationUpdate current)` — non-blocking hand-off, called by Task 3.
  - `void republishNow(Set<String> refs)` — synchronous: subscriber check, scan, chunked emit, counters.
  - `Set<String> takePending()` — takes the accumulated refs, leaving the pending set empty.
  - `void start()` / `void stop()` — worker lifecycle, `@PostConstruct` / `@PreDestroy`.
  - `long getScanCount()`, `long getRepublishedCount()`, `long getChunkCount()`.
  - Constructor gains two config parameters — see Step 3.

**Coalescing uses an accumulating set, not a queue.** Refs pile into a shared set while the worker is busy, and the worker takes the whole thing at once — so a burst costs one scan. A bounded queue was considered and rejected: it needs an overflow rule, and every cheap overflow rule either drops a trigger or merges into an entry the worker may concurrently have taken, losing refs silently. A set has no overflow case at all.

**Why the chunking matters.** The ET sink is `Sinks.many().multicast().directBestEffort()` and each subscriber ends in `.onBackpressureDrop()` (`EstimatedTimetableUpdateRxPublisher.java:18,38`). A burst does not queue — it is **discarded** for any subscriber that cannot keep up, and the sink cannot tell a republish from a genuine ET update, so what gets dropped may be real ET data. Pacing is a correctness concern here, not a nicety.

- [ ] **Step 1: Write the failing test**

Append these tests to `SituationTriggeredRepublisherTest.java` (keep everything from Task 1), and add the imports `java.util.ArrayList`, `java.util.List`, `java.util.concurrent.TimeUnit`, `reactor.core.Disposable`, `reactor.core.publisher.Flux`, `org.entur.vehicles.data.QueryFilter`, `org.entur.vehicles.data.MetricType`.

Add this helper to the test class — `QueryFilter` has no no-arg constructor, and the existing `QueryFilterTest` builds one exactly this way, with a null metrics service:

```java
    /** A filter that matches everything, with the small buffer the publisher requires. */
    private QueryFilter matchAll() {
        return new QueryFilter(
                null, MetricType.SUBSCRIPTION,
                null, null, null, null, null, null, null, null, null, null, null, null,
                1, 1);
    }
```

```java
    @Test
    public void testPendingRefsAccumulateIntoASingleTake() {
        SituationUpdate first = situation();
        first.getAffects().addLine(new Line("TST:Line:1"));

        SituationUpdate second = situation();
        second.getAffects().addStopPoint(new StopPoint("NSR:Quay:2"));

        republisher.onSituationChanged(null, first);
        republisher.onSituationChanged(null, second);

        assertThat(republisher.takePending())
                .withFailMessage("a burst of situation changes must cost one scan, not one each")
                .containsExactlyInAnyOrder("TST:Line:1", "NSR:Quay:2");
        assertThat(republisher.takePending())
                .withFailMessage("taking must clear the pending set, or every later scan would "
                        + "redo all the work of every earlier one")
                .isEmpty();
    }

    @Test
    public void testNoScanHappensWhenNobodyIsSubscribed() {
        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        republisher.republishNow(Set.of("TST:Line:1"));

        assertThat(republisher.getScanCount())
                .withFailMessage("with no timetables subscribers there is nobody to tell, so the "
                        + "scan must not run at all - this is what makes the startup snapshot free")
                .isZero();
        assertThat(republisher.getRepublishedCount()).isZero();
    }

    @Test
    public void testRepublishesAffectedJourneysToSubscribers() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher =
                new SituationTriggeredRepublisher(timetableMap, etPublisher, 100, Duration.ofMillis(1));

        EstimatedTimetableUpdate affected =
                storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");
        storeJourney("TST:Line:2", "TST:ServiceJourney:2", "TST:DatedServiceJourney:2", "NSR:Quay:2");

        List<EstimatedTimetableUpdate> received = new ArrayList<>();
        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(received::addAll);

        try {
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(received).containsExactly(affected);
            assertThat(republisher.getScanCount()).isEqualTo(1);
            assertThat(republisher.getRepublishedCount()).isEqualTo(1);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void testLargeFanOutIsEmittedInChunksRatherThanOneBurst() {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher =
                new SituationTriggeredRepublisher(timetableMap, etPublisher, 100, Duration.ofMillis(1));

        for (int i = 0; i < 250; i++) {
            storeJourney("TST:Line:1", "TST:ServiceJourney:" + i, "TST:DatedServiceJourney:" + i, "NSR:Quay:1");
        }

        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            republisher.republishNow(Set.of("TST:Line:1"));

            assertThat(republisher.getRepublishedCount()).isEqualTo(250);
            assertThat(republisher.getChunkCount())
                    .withFailMessage("250 journeys at a chunk size of 100 must go out as 3 chunks - "
                            + "emitting them in one tight loop would discard messages for slower "
                            + "subscribers on a directBestEffort sink")
                    .isEqualTo(3);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void testHandOffEventuallyRunsAScan() throws Exception {
        EstimatedTimetableUpdateRxPublisher etPublisher = new EstimatedTimetableUpdateRxPublisher();
        SituationTriggeredRepublisher republisher =
                new SituationTriggeredRepublisher(timetableMap, etPublisher, 100, Duration.ofMillis(1));
        republisher.start();

        storeJourney("TST:Line:1", "TST:ServiceJourney:1", "TST:DatedServiceJourney:1", "NSR:Quay:1");

        Disposable subscription = Flux.from(etPublisher.getPublisher(matchAll(), "test"))
                .subscribe(batch -> { });

        try {
            SituationUpdate current = situation();
            current.getAffects().addLine(new Line("TST:Line:1"));

            republisher.onSituationChanged(null, current);

            long deadline = System.currentTimeMillis() + 5000;
            while (republisher.getRepublishedCount() == 0 && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(republisher.getRepublishedCount())
                    .withFailMessage("onSituationChanged must hand off to the worker, which must "
                            + "then scan and emit")
                    .isEqualTo(1);
        } finally {
            subscription.dispose();
            republisher.stop();
        }
    }

    @Test
    public void testHandOffDoesNotThrowWhenTheWorkerIsNotRunning() {
        SituationUpdate current = situation();
        current.getAffects().addLine(new Line("TST:Line:1"));

        // SX ingest must never be broken by a republishing failure - a situation that fails to
        // trigger a republish is still stored and still reaches the situations subscription.
        republisher.onSituationChanged(null, current);
    }
```

Also update `setUp()` so the default instance carries the new constructor arguments:

```java
    @BeforeEach
    public void setUp() {
        timetableMap = new AutoPurgingTimetableMap(Duration.parse("PT1M"), Duration.parse("PT10M"));
        republisher = new SituationTriggeredRepublisher(
                timetableMap, new EstimatedTimetableUpdateRxPublisher(), 100, Duration.ofMillis(1));
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o test -Dtest=SituationTriggeredRepublisherTest`
Expected: FAIL — compilation error; the four-argument constructor, `republishNow`, `takePending`, `onSituationChanged`, `start`, `stop`, `getScanCount`, `getRepublishedCount` and `getChunkCount` do not exist.

- [ ] **Step 3: Add the worker**

Replace the constructor and add the members below to `SituationTriggeredRepublisher`. Keep `triggerRefs`, `addRefs`, `findAffected`, `isAffected` and `getSkippedCount` from Task 1 exactly as they are.

Add imports: `jakarta.annotation.PostConstruct`, `jakarta.annotation.PreDestroy`, `org.springframework.beans.factory.annotation.Value`, `java.time.Duration`, `java.util.concurrent.ConcurrentHashMap`, `java.util.concurrent.Semaphore`, `java.util.concurrent.TimeUnit`.

```java
    private final int chunkSize;
    private final Duration chunkDelay;

    // Refs accumulate here while the worker is busy; the worker takes the whole set at once,
    // so a burst of situation changes costs one scan. A set has no capacity and therefore no
    // overflow rule to get wrong - see the plan's note on why a bounded queue was rejected.
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Semaphore signal = new Semaphore(0);

    private final AtomicLong scanCount = new AtomicLong();
    private final AtomicLong republishedCount = new AtomicLong();
    private final AtomicLong chunkCount = new AtomicLong();

    private volatile Thread worker;
    private volatile boolean running;

    public SituationTriggeredRepublisher(
            @Autowired AutoPurgingTimetableMap timetableMap,
            @Autowired EstimatedTimetableUpdateRxPublisher etPublisher,
            @Value("${vehicle.sx.republish.chunk.size:100}") int chunkSize,
            @Value("${vehicle.sx.republish.chunk.delay:PT0.05S}") Duration chunkDelay) {
        this.timetableMap = timetableMap;
        this.etPublisher = etPublisher;
        this.chunkSize = chunkSize;
        this.chunkDelay = chunkDelay;
    }

    @PostConstruct
    public void start() {
        running = true;
        worker = new Thread(this::run, "situation-republisher");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
    }

    /**
     * Hands the change off to the worker and returns. The SX Pub/Sub executor threads must never
     * wait on a scan, and a republishing failure must never break SX ingest - a situation that
     * fails to trigger a republish is still stored and still reaches the situations subscription.
     */
    public void onSituationChanged(SituationUpdate previous, SituationUpdate current) {
        try {
            Set<String> refs = triggerRefs(previous, current);
            if (refs.isEmpty()) {
                return;
            }
            pending.addAll(refs);
            signal.release();
        } catch (RuntimeException e) {
            LOG.warn("Situation-triggered republish not scheduled.", e);
        }
    }

    private void run() {
        while (running) {
            try {
                signal.acquire();
                signal.drainPermits();
                Set<String> refs = takePending();
                if (!refs.isEmpty()) {
                    republishNow(refs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // The worker must never die: a dead worker silently stops all republishing
                // while the rest of the service looks healthy.
                LOG.warn("Situation-triggered republish failed.", e);
            }
        }
    }

    /**
     * Takes everything accumulated since the last call, leaving the pending set empty.
     * <p>
     * Refs added between the copy and the removal stay pending, and the permit released for them
     * survives the {@code drainPermits()} above, so the next loop picks them up. The worst case is
     * one extra pass that finds nothing, which {@link #run()} skips.
     */
    Set<String> takePending() {
        Set<String> taken = new HashSet<>(pending);
        pending.removeAll(taken);
        return taken;
    }

    /**
     * Scans for affected journeys and re-emits them, paced in chunks.
     * <p>
     * Returns immediately when nothing is subscribed. That is not only an optimisation: it makes
     * the 343-situation startup snapshot cost nothing, since no subscriber can exist yet, and it
     * makes the whole mechanism free for deployments that never use timetables subscriptions.
     */
    void republishNow(Set<String> refs) {
        if (etPublisher.currentSubscribers() == 0) {
            return;
        }

        long started = System.currentTimeMillis();
        List<EstimatedTimetableUpdate> affected = findAffected(refs);
        scanCount.incrementAndGet();

        for (int from = 0; from < affected.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, affected.size());
            for (EstimatedTimetableUpdate timetable : affected.subList(from, to)) {
                etPublisher.publishUpdate(timetable);
            }
            republishedCount.addAndGet(to - from);
            chunkCount.incrementAndGet();

            if (to < affected.size()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(chunkDelay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        long duration = System.currentTimeMillis() - started;
        if (duration > 1000) {
            LOG.warn("Situation-triggered republish took {} ms for {} refs and {} journeys - the "
                    + "timetable map may have outgrown a full scan per situation change.",
                    duration, refs.size(), affected.size());
        } else {
            LOG.debug("Republished {} journeys for {} refs in {} ms.", affected.size(), refs.size(), duration);
        }
    }

    public long getScanCount() {
        return scanCount.get();
    }

    public long getRepublishedCount() {
        return republishedCount.get();
    }

    public long getChunkCount() {
        return chunkCount.get();
    }
```

Note `republishNow` does **not** call `metricsService.markTimetableUpdate(...)`. That counter measures ingest; inflating it with republishes would corrupt an existing operational signal.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -o test -Dtest=SituationTriggeredRepublisherTest`
Expected: PASS, 14 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn -o clean test`
Expected: PASS, 142 tests (128 + 14). Output pristine.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java \
        src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java
git commit -m "Adding paced worker for situation-triggered timetable republishing"
```

---

### Task 3: Wiring, configuration and end-to-end proof

Connects the republisher to SX ingest and proves the behaviour the whole change exists for, through the real schema and a real subscription.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/repository/SituationRepository.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/Usage.md`
- Test: `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java`

**Interfaces:**
- Consumes: `SituationTriggeredRepublisher.onSituationChanged(SituationUpdate previous, SituationUpdate current)` from Task 2.
- Produces: nothing further tasks depend on.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java`. Add these constants beside the existing ones near the top of the class:

```java
    private static final String REPUBLISH_LINE = "TST:Line:republish-probe";
    private static final String REPUBLISH_DSJ = "TST:DatedServiceJourney:republish-probe";
    private static final String REPUBLISH_QUAY = "NSR:Quay:republish-probe";
    private static final String REPUBLISH_SITUATION = "TST:SituationNumber:republish-probe";
```

and these two tests:

```java
    /**
     * The behaviour this whole mechanism exists for. The timetables subscription is fed only by
     * EstimatedTimetableUpdateRxPublisher, and the situations field is resolved once per emitted
     * event - so without SituationTriggeredRepublisher, a situation appearing after the journey
     * was published never reaches the subscriber at all.
     * <p>
     * Note there is deliberately NO ET update after the subscription opens: the journey is
     * published first, and the only thing that happens afterwards is the situation being added.
     */
    @Test
    void addingASituationRepublishesTheAffectedJourneyWithNoEtUpdate() throws InterruptedException {
        timetableRepository.add(journeyCallingAt(REPUBLISH_LINE, REPUBLISH_DSJ, REPUBLISH_QUAY));

        String document = """
                subscription {
                  timetables(lineRef: "%s", bufferSize: 1, bufferTime: 50) {
                    datedServiceJourney { id }
                    calls { situations { situationNumber } }
                  }
                }
                """.formatted(REPUBLISH_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-republish", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        CountDownLatch situationSeen = new CountDownLatch(1);

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timetables = (List<Map<String, Object>>) data.get("timetables");
            for (Map<String, Object> timetable : timetables) {
                if (REPUBLISH_DSJ.equals(datedServiceJourneyId(timetable))
                        && callSituationNumbers(timetable).contains(REPUBLISH_SITUATION)) {
                    situationSeen.countDown();
                }
            }
        });

        try {
            situationRepository.add(situationAffectingStop(REPUBLISH_SITUATION, REPUBLISH_QUAY));

            assertThat(situationSeen.await(10, TimeUnit.SECONDS))
                    .withFailMessage("a situation affecting a stored journey must reach an active "
                            + "timetables subscriber without waiting for an ET update - check "
                            + "SituationTriggeredRepublisher is wired into SituationRepository.add")
                    .isTrue();
        } finally {
            subscription.dispose();
        }
    }

    /**
     * Closing is the case that fails if the PREVIOUS version is not captured: the matcher excludes
     * closed situations, so matching only the new state finds no journeys and nothing would be
     * republished - leaving the disruption on the client's display indefinitely.
     */
    @Test
    void closingASituationRepublishesTheAffectedJourneyWithoutIt() throws InterruptedException {
        String line = REPUBLISH_LINE + "-close";
        String dsj = REPUBLISH_DSJ + "-close";
        String quay = REPUBLISH_QUAY + "-close";
        String situationNumber = REPUBLISH_SITUATION + "-close";

        timetableRepository.add(journeyCallingAt(line, dsj, quay));
        situationRepository.add(situationAffectingStop(situationNumber, quay));

        String document = """
                subscription {
                  timetables(lineRef: "%s", bufferSize: 1, bufferTime: 50) {
                    datedServiceJourney { id }
                    calls { situations { situationNumber } }
                  }
                }
                """.formatted(line);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-republish-close", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        CountDownLatch situationGone = new CountDownLatch(1);
        AtomicReference<List<String>> lastSeen = new AtomicReference<>(List.of());

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timetables = (List<Map<String, Object>>) data.get("timetables");
            for (Map<String, Object> timetable : timetables) {
                if (dsj.equals(datedServiceJourneyId(timetable))) {
                    List<String> situations = callSituationNumbers(timetable);
                    lastSeen.set(situations);
                    if (!situations.contains(situationNumber)) {
                        situationGone.countDown();
                    }
                }
            }
        });

        try {
            PtSituationElementRecord closed = situationAffectingStop(situationNumber, quay);
            closed.setProgress("CLOSED");
            closed.setVersion(2);
            situationRepository.add(closed);

            assertThat(situationGone.await(10, TimeUnit.SECONDS))
                    .withFailMessage("closing a situation must republish the journey without it - "
                            + "the matcher excludes closed situations, so the republisher has to "
                            + "trigger on the PREVIOUS version's refs. Last seen: " + lastSeen.get())
                    .isTrue();
        } finally {
            subscription.dispose();
        }
    }

    /** Flattens the situationNumbers across every call of a timetable in a subscription payload. */
    private List<String> callSituationNumbers(Map<String, Object> timetable) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) timetable.get("calls");
        List<String> numbers = new ArrayList<>();
        if (calls != null) {
            for (Map<String, Object> call : calls) {
                numbers.addAll(situationNumbersOf(call.get("situations")));
            }
        }
        return numbers;
    }
```

The initial-snapshot event delivers the journey before the situation exists, so the latch in the first test can only trip on a republished event. In the second test the initial snapshot already carries the situation, so `situationGone` can only trip on a republish after the close.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o test -Dtest=ApplicationGraphQlSchemaTests`
Expected: FAIL — both new tests time out after 10 seconds with the messages above. That timeout IS the bug: nothing connects a situation change to the ET sink yet.

- [ ] **Step 3: Capture the previous version in SituationRepository**

In `src/main/java/org/entur/vehicles/repository/SituationRepository.java`, add the field and constructor parameter:

```java
    private final SituationTriggeredRepublisher republisher;
```

```java
    public SituationRepository(@Autowired PrometheusMetricsService metricsService,
                               @Autowired SituationMapper mapper,
                               @Autowired AutoPurgingSituationMap situationMap,
                               @Autowired SituationUpdateRxPublisher publisher,
                               @Autowired SituationTriggeredRepublisher republisher) {
        this.metricsService = metricsService;
        this.mapper = mapper;
        this.situationMap = situationMap;
        this.publisher = publisher;
        this.republisher = republisher;
        this.publisher.setRepository(this);
    }
```

Then replace the `compute` call and add the hand-off. The existing `compute` line is:

```java
            SituationUpdate accepted = situationMap.compute(key,
                    (k, stored) -> isSupersededByStoredVersion(stored, situation) ? stored : situation);
```

Replace it with:

```java
            // The previous version is captured for the republisher: when a situation closes, the
            // matcher excludes it, so matching only the new state would find no journeys to
            // republish - and a situation that narrows no longer names the stops whose journeys
            // must be told. Assigning a reference is not the kind of side effect the comment above
            // rules out: it neither blocks nor performs I/O, and compute() does not re-invoke the
            // mapping function.
            AtomicReference<SituationUpdate> previous = new AtomicReference<>();
            SituationUpdate accepted = situationMap.compute(key, (k, stored) -> {
                previous.set(stored);
                return isSupersededByStoredVersion(stored, situation) ? stored : situation;
            });
```

Add `import java.util.concurrent.atomic.AtomicReference;`.

Then, immediately after the existing `metricsService.markSituationUpdate(1, situation.getCodespace());` line and still inside the `try`, add:

```java
            republisher.onSituationChanged(previous.get(), situation);
```

- [ ] **Step 4: Add the configuration**

Append to `src/main/resources/application.properties`:

```properties
# Situation-triggered republishing of estimated timetables. A situation matching a large share
# of stored journeys is emitted in chunks rather than one burst: the ET sink is directBestEffort
# with onBackpressureDrop, so an unpaced burst is discarded for slower subscribers - and the sink
# cannot tell a republish from a genuine ET update.
vehicle.sx.republish.chunk.size=100
vehicle.sx.republish.chunk.delay=PT0.05S
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -o test -Dtest=ApplicationGraphQlSchemaTests`
Expected: PASS, 9 tests.

- [ ] **Step 6: Run the full suite**

Run: `mvn -o clean test`
Expected: PASS, 144 tests (142 + 2). Output pristine.

- [ ] **Step 7: Verify the tests are not passing for the wrong reason**

Comment out the `republisher.onSituationChanged(previous.get(), situation);` line, run `mvn -o test -Dtest=ApplicationGraphQlSchemaTests`, and confirm BOTH new tests fail on their timeout. Then restore the line and confirm they pass again.

This matters because both tests await a latch — a test that trips its latch from the initial snapshot rather than from a republish would pass without the feature. Record the observed failure output in your report.

- [ ] **Step 8: Document the behaviour**

In `src/main/resources/Usage.md`, find the paragraph ending "A situation naming several of the journey's stops is reported against every one of them, so a client can mark each affected stop. Closed situations are never attached." and add after it:

```markdown
A `timetables` subscription is also told when a situation affecting one of its journeys changes,
even if the journey's own timetable data has not. The affected journeys are re-sent on the normal
stream, identical in shape to any other update, so no special client handling is needed — apply
them exactly as you already apply every event. This covers situations appearing, changing and
closing, and it means a disruption disappears from a journey promptly rather than lingering until
that journey's producer happens to send another message.

A situation affecting a large number of journeys is re-sent over a few seconds rather than all at
once, so that one wide-reaching disruption does not crowd out ordinary timetable updates.
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/SituationRepository.java \
        src/main/resources/application.properties \
        src/main/resources/Usage.md \
        src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java
git commit -m "Republishing affected timetables when a situation changes"
```

---

## Overall verification

```bash
mvn -o clean test          # 144 tests, 0 failures
git log --oneline -3       # three commits, terse subjects, no AI attribution
git status --short         # only the user's pre-existing untracked files
```

Confirm by reading the diff that `TimetableRepository`, `SituationMapper`, `SituationMatcher`, `QueryFilter`, `SituationFilter`, `AutoPurgingTimetableMap`, the SX ingest paths and all VM code are untouched, and that `pom.xml` has no new dependency.
