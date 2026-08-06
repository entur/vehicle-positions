# Situations on the Estimated Timetable API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attach relevant SIRI-SX situations to estimated timetable data — `EstimatedTimetableUpdate.situations` for the whole journey, and `Call.situations` for the specific stop while the vehicle is there.

**Architecture:** Two `@BatchMapping` resolvers build a ref→situations index once per batch from the live situation map, then match each ET message and each call against it. Matching is journey / dated journey / line against the journey's time span, and stop against that call's own window. Cost is opt-in: nothing is computed unless the client selects the field.

**Tech Stack:** Java 21, Spring Boot, Spring for GraphQL (`@BatchMapping`), JUnit 5 + Mockito + AssertJ.

**Design spec:** `docs/superpowers/specs/2026-08-06-sx-in-et-api-design.md`

## Global Constraints

- Package root is `org.entur.vehicles`. Branch is `siri_sx_et_join`, based on `siri_sx_api`.
- The full SX feature — repository, mapper, filter, GraphQL feed, startup snapshot — already exists and is committed. Do not reimplement any of it.
- Closed situations (`progress == closed`) never attach, on either field.
- A missing timestamp must never cause a disruption to disappear. An unresolvable window is **unbounded**, so a matching situation still attaches.
- Overlap is **inclusive** at both ends: a situation ending exactly at the arrival time still attaches.
- A situation with no validity periods, or with an open-ended period, overlaps everything.
- Neither field may compute anything when the client does not select it.
- `TimetableRepository`, `SituationRepository`, `SituationMapper`, `QueryFilter`, `SituationFilter`, the SX ingest paths and everything VM-related must NOT be modified.
- No new dependency in `pom.xml`.
- No test may perform network I/O.
- Build and test with `mvn`. Full suite: `mvn clean test` — **98 tests currently pass.**
- No Claude/AI attribution in commit messages — match the existing terse style.

## The hazard that shapes Task 3

`AbstractUpdate` overrides `equals`/`hashCode` over `serviceJourney`, `operator`, `codespace`, `mode` and `line` — and **not** `datedServiceJourney`. `EstimatedTimetableUpdate` inherits that. So two ET messages for the same service journey on different operating days compare **equal**.

`@BatchMapping` methods may return either `Map<Source, Value>` or `List<Value>`. The `Map` form uses the source objects as keys, so with value-based equality those two journeys would collapse into one entry and one of them would receive the other's situations — or none.

**Both resolvers therefore return `List<List<SituationUpdate>>`, positionally aligned with the input list.** Do not "simplify" either to a `Map`.

## Task Overview

| # | Deliverable |
|---|---|
| 1 | Time-window primitives: `ValidityPeriod.overlaps`, `SituationUpdate.isValidDuring`, `Call` window accessors |
| 2 | `SituationMatcher` — the index and the match rule |
| 3 | `SituationJoinController`, schema fields, wiring test |

---

### Task 1: Time-window primitives

Three small, pure additions that the matcher composes. No Spring, no GraphQL, no I/O.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/data/model/ValidityPeriod.java`
- Modify: `src/main/java/org/entur/vehicles/data/SituationUpdate.java`
- Modify: `src/main/java/org/entur/vehicles/data/model/Call.java`
- Test: `src/test/java/org/entur/vehicles/data/model/TimeWindowTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ValidityPeriod.overlaps(ZonedDateTime from, ZonedDateTime to) -> boolean` — a null bound means unbounded on that side; inclusive at both ends.
  - `SituationUpdate.isValidDuring(ZonedDateTime from, ZonedDateTime to) -> boolean` — true when there are no validity periods, else true when any period overlaps.
  - `Call.getWindowStart() -> ZonedDateTime` and `Call.getWindowEnd() -> ZonedDateTime` — both null when the call carries no timestamps at all.

Note `getWindowStart`/`getWindowEnd` are new public getters on a type that GraphQL maps by name. That is safe: Spring for GraphQL only resolves fields the schema declares, and the schema declares neither. Do not add them to the schema.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/data/model/TimeWindowTest.java`:

```java
package org.entur.vehicles.data.model;

import org.entur.vehicles.data.SituationUpdate;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TimeWindowTest {

    private final ZonedDateTime noon = ZonedDateTime.parse("2026-08-06T12:00:00Z");

    private ValidityPeriod period(ZonedDateTime start, ZonedDateTime end) {
        return new ValidityPeriod(start, end);
    }

    @Test
    public void testOverlapWhenPeriodCoversTheWindow() {
        assertThat(period(noon.minusHours(2), noon.plusHours(2))
                .overlaps(noon.minusMinutes(10), noon.plusMinutes(10))).isTrue();
    }

    @Test
    public void testNoOverlapWhenPeriodEndsBeforeTheWindow() {
        assertThat(period(noon.minusHours(2), noon)
                .overlaps(noon.plusMinutes(30), noon.plusMinutes(40))).isFalse();
    }

    @Test
    public void testNoOverlapWhenPeriodStartsAfterTheWindow() {
        assertThat(period(noon.plusHours(1), noon.plusHours(2))
                .overlaps(noon.minusMinutes(10), noon)).isFalse();
    }

    @Test
    public void testOverlapIsInclusiveAtBothEnds() {
        // A situation ending exactly when the vehicle arrives still applies.
        assertThat(period(noon.minusHours(1), noon).overlaps(noon, noon.plusMinutes(5))).isTrue();
        // ...and one starting exactly as it departs.
        assertThat(period(noon, noon.plusHours(1)).overlaps(noon.minusMinutes(5), noon)).isTrue();
    }

    @Test
    public void testOpenEndedPeriodOverlapsAnything() {
        assertThat(period(noon.minusYears(3), null)
                .overlaps(noon.plusYears(5), noon.plusYears(5))).isTrue();
    }

    @Test
    public void testUnboundedWindowOverlapsAnyPeriod() {
        assertThat(period(noon.minusHours(2), noon.minusHours(1)).overlaps(null, null)).isTrue();
    }

    @Test
    public void testSituationWithNoValidityPeriodsAlwaysApplies() {
        SituationUpdate situation = new SituationUpdate();
        assertThat(situation.isValidDuring(noon, noon)).isTrue();

        situation.setValidityPeriods(List.of());
        assertThat(situation.isValidDuring(noon, noon)).isTrue();
    }

    @Test
    public void testSituationAppliesWhenAnyPeriodOverlaps() {
        SituationUpdate situation = new SituationUpdate();
        situation.setValidityPeriods(List.of(
                period(noon.minusDays(5), noon.minusDays(4)),
                period(noon.minusMinutes(5), noon.plusMinutes(5))));

        assertThat(situation.isValidDuring(noon, noon)).isTrue();
    }

    @Test
    public void testSituationDoesNotApplyWhenNoPeriodOverlaps() {
        SituationUpdate situation = new SituationUpdate();
        situation.setValidityPeriods(List.of(
                period(noon.minusDays(5), noon.minusDays(4)),
                period(noon.plusDays(4), noon.plusDays(5))));

        assertThat(situation.isValidDuring(noon, noon)).isFalse();
    }

    @Test
    public void testCallWindowPrefersActualOverExpectedOverAimed() {
        Call call = new Call();
        call.setAimedArrivalTime(noon);
        call.setAimedDepartureTime(noon.plusMinutes(2));
        assertThat(call.getWindowStart()).isEqualTo(noon);
        assertThat(call.getWindowEnd()).isEqualTo(noon.plusMinutes(2));

        call.setExpectedArrivalTime(noon.plusMinutes(5));
        call.setExpectedDepartureTime(noon.plusMinutes(7));
        assertThat(call.getWindowStart()).isEqualTo(noon.plusMinutes(5));
        assertThat(call.getWindowEnd()).isEqualTo(noon.plusMinutes(7));

        call.setActualArrivalTime(noon.plusMinutes(9));
        call.setActualDepartureTime(noon.plusMinutes(11));
        assertThat(call.getWindowStart()).isEqualTo(noon.plusMinutes(9));
        assertThat(call.getWindowEnd()).isEqualTo(noon.plusMinutes(11));
    }

    @Test
    public void testCallWithOnlyOneTimeIsAnInstant() {
        Call arrivalOnly = new Call();
        arrivalOnly.setAimedArrivalTime(noon);
        assertThat(arrivalOnly.getWindowStart()).isEqualTo(noon);
        assertThat(arrivalOnly.getWindowEnd()).isEqualTo(noon);

        Call departureOnly = new Call();
        departureOnly.setAimedDepartureTime(noon);
        assertThat(departureOnly.getWindowStart()).isEqualTo(noon);
        assertThat(departureOnly.getWindowEnd()).isEqualTo(noon);
    }

    @Test
    public void testCallWithNoTimesIsUnbounded() {
        Call call = new Call();
        assertThat(call.getWindowStart()).isNull();
        assertThat(call.getWindowEnd()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=TimeWindowTest`
Expected: FAIL — compilation error; `overlaps`, `isValidDuring`, `getWindowStart` and `getWindowEnd` do not exist.

- [ ] **Step 3: Add `overlaps` to ValidityPeriod**

In `ValidityPeriod.java`, after `isValidAt`:

```java
    /**
     * True when this period overlaps the window {@code [from, to]}, inclusive at both ends.
     * A null bound means unbounded on that side, so an unresolvable call window overlaps
     * every period rather than silently dropping the situation.
     */
    public boolean overlaps(ZonedDateTime from, ZonedDateTime to) {
        if (endTime != null && from != null && endTime.isBefore(from)) {
            return false;
        }
        return startTime == null || to == null || !startTime.isAfter(to);
    }
```

- [ ] **Step 4: Add `isValidDuring` to SituationUpdate**

In `SituationUpdate.java`, next to `isValidAt`:

```java
    /**
     * True when any validity period overlaps {@code [from, to]}. A situation with no
     * validity periods is unconstrained and always applies, matching {@link #getOpenEnded()}.
     */
    public boolean isValidDuring(ZonedDateTime from, ZonedDateTime to) {
        if (validityPeriods == null || validityPeriods.isEmpty()) {
            return true;
        }
        return validityPeriods.stream().anyMatch(period -> period.overlaps(from, to));
    }
```

- [ ] **Step 5: Add the window accessors to Call**

In `Call.java`, after the timestamp accessors:

```java
    /**
     * Start of the window during which the vehicle is at this stop, resolved
     * actual -> expected -> aimed. Falls back to the departure side when no arrival time
     * is known, so a call with a single timestamp is an instant. Null when the call
     * carries no timestamps at all, meaning an unbounded window.
     * <p>
     * Not exposed through GraphQL - the schema declares no such field.
     */
    public ZonedDateTime getWindowStart() {
        ZonedDateTime arrival = firstNonNull(actualArrivalTime, expectedArrivalTime, aimedArrivalTime);
        return arrival != null
                ? arrival
                : firstNonNull(actualDepartureTime, expectedDepartureTime, aimedDepartureTime);
    }

    /** End of the window during which the vehicle is at this stop. See {@link #getWindowStart()}. */
    public ZonedDateTime getWindowEnd() {
        ZonedDateTime departure = firstNonNull(actualDepartureTime, expectedDepartureTime, aimedDepartureTime);
        return departure != null
                ? departure
                : firstNonNull(actualArrivalTime, expectedArrivalTime, aimedArrivalTime);
    }

    private static ZonedDateTime firstNonNull(ZonedDateTime... candidates) {
        for (ZonedDateTime candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
```

Check the actual field names in `Call.java` before writing this — use whatever the fields are called, not what this snippet assumes.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=TimeWindowTest`
Expected: PASS, 12 tests.

- [ ] **Step 7: Run the full suite**

Run: `mvn clean test`
Expected: PASS, 110 tests (98 + 12).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/model/ValidityPeriod.java \
        src/main/java/org/entur/vehicles/data/SituationUpdate.java \
        src/main/java/org/entur/vehicles/data/model/Call.java \
        src/test/java/org/entur/vehicles/data/model/TimeWindowTest.java
git commit -m "Adding time-window overlap primitives for situation matching"
```

---

### Task 2: SituationMatcher

The index and the match rule, with no Spring or GraphQL dependency so it can be unit-tested directly.

**Files:**
- Create: `src/main/java/org/entur/vehicles/data/SituationMatcher.java`
- Test: `src/test/java/org/entur/vehicles/data/SituationMatcherTest.java`

**Interfaces:**
- Consumes: `ValidityPeriod.overlaps`, `SituationUpdate.isValidDuring`, `Call.getWindowStart()/getWindowEnd()` (Task 1); `Affects.getLineRefs()`, `getStopRefs()`, `getServiceJourneyIds()`, `getDatedServiceJourneyIds()`; `SituationUpdate.getProgress().isClosed()`.
- Produces:
  - `new SituationMatcher(Collection<SituationUpdate> situations)` — builds the index, skipping closed situations
  - `List<SituationUpdate> match(EstimatedTimetableUpdate timetable)`
  - `List<SituationUpdate> match(Call call)`

**Refinement over the spec:** the spec describes one `Map<String, List<SituationUpdate>>` keyed by every identifier. Use **four separate maps** — line, stop, service journey, dated service journey — instead. A single map would let an identifier that happens to appear as both a line ref and a stop ref cross-match. Separate maps cost a few extra lines and make that impossible.

Journey span is the earliest `getWindowStart()` across the journey's calls to the latest `getWindowEnd()`. If no call yields a time, both ends are null, meaning unbounded.

**Watch `AbstractUpdate.getServiceJourney()`.** It does not return the field of that name — when a `datedServiceJourney` is set it returns `datedServiceJourney.getServiceJourney()`, which is null if that inner journey was never populated. `TimetableRepository` always populates it (`TimetableRepository.java:144,149`), so real data is fine, but a test fixture that sets a bare `DatedServiceJourney` will silently lose the service-journey match. Always use the two-arg `DatedServiceJourney(id, serviceJourney)` constructor in fixtures.

`match(EstimatedTimetableUpdate)` returns the union of the journey-level matches and every call's stop matches, deduplicated by situation number, in a stable order (journey matches first, then calls in order).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/data/SituationMatcherTest.java`:

```java
package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SituationMatcherTest {

    private final ZonedDateTime noon = ZonedDateTime.parse("2026-08-06T12:00:00Z");

    private SituationUpdate situation(String number, ValidityPeriod... periods) {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber(number);
        situation.setProgress(WorkflowStatusEnumeration.published);
        situation.setAffects(new Affects());
        situation.setValidityPeriods(List.of(periods));
        return situation;
    }

    private Call call(String stopRef, ZonedDateTime arrival, ZonedDateTime departure) {
        Call call = new Call();
        call.setStopPoint(new StopPoint(stopRef));
        call.setAimedArrivalTime(arrival);
        call.setAimedDepartureTime(departure);
        return call;
    }

    private EstimatedTimetableUpdate timetable(String lineRef, String serviceJourneyId, Call... calls) {
        EstimatedTimetableUpdate timetable = new EstimatedTimetableUpdate();
        timetable.setLine(new Line(lineRef));
        timetable.setServiceJourney(new ServiceJourney(serviceJourneyId));
        for (Call call : calls) {
            timetable.addCall(call);
        }
        return timetable;
    }

    @Test
    public void testMatchesOnLine() {
        SituationUpdate situation = situation("TST:SituationNumber:line");
        situation.getAffects().addLine(new Line("TST:Line:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)))))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:line");
    }

    @Test
    public void testMatchesOnServiceJourneyAndDatedServiceJourney() {
        SituationUpdate bySj = situation("TST:SituationNumber:sj");
        bySj.getAffects().addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));

        SituationUpdate byDsj = situation("TST:SituationNumber:dsj");
        byDsj.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(bySj, byDsj));

        EstimatedTimetableUpdate timetable = timetable("TST:Line:9", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)));
        // The inner ServiceJourney is required: AbstractUpdate.getServiceJourney() delegates
        // through datedServiceJourney when one is set, so the two-arg constructor is what
        // TimetableRepository actually builds (see TimetableRepository.java:144,149).
        timetable.setDatedServiceJourney(new DatedServiceJourney(
                "TST:DatedServiceJourney:1", new ServiceJourney("TST:ServiceJourney:1")));

        assertThat(matcher.match(timetable))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactlyInAnyOrder("TST:SituationNumber:sj", "TST:SituationNumber:dsj");
    }

    @Test
    public void testDoesNotMatchAnUnrelatedSituation() {
        SituationUpdate situation = situation("TST:SituationNumber:elsewhere");
        situation.getAffects().addLine(new Line("TST:Line:999"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1))))).isEmpty();
    }

    @Test
    public void testClosedSituationNeverAttaches() {
        SituationUpdate closed = situation("TST:SituationNumber:closed");
        closed.setProgress(WorkflowStatusEnumeration.closed);
        closed.getAffects().addLine(new Line("TST:Line:1"));
        closed.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(closed));

        Call call = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", call))).isEmpty();
        assertThat(matcher.match(call)).isEmpty();
    }

    /** The case that motivated the whole design. */
    @Test
    public void testStopSituationIsTestedAgainstThatCallsOwnWindow() {
        SituationUpdate untilNoon = situation("TST:SituationNumber:quay",
                new ValidityPeriod(noon.minusHours(3), noon));
        untilNoon.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(untilNoon));

        Call arrivesLate = call("NSR:Quay:1", noon.plusMinutes(30), noon.plusMinutes(31));
        assertThat(matcher.match(arrivesLate))
                .withFailMessage("a quay message ending at 12:00 must not attach to a call at 12:30")
                .isEmpty();

        Call arrivesEarly = call("NSR:Quay:1", noon.minusMinutes(30), noon.minusMinutes(29));
        assertThat(matcher.match(arrivesEarly))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:quay");
    }

    @Test
    public void testLateCallDoesNotPullTheStopSituationOntoTheJourneyEither() {
        SituationUpdate untilNoon = situation("TST:SituationNumber:quay",
                new ValidityPeriod(noon.minusHours(3), noon));
        untilNoon.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(untilNoon));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon.plusMinutes(30), noon.plusMinutes(31))))).isEmpty();
    }

    @Test
    public void testStopSituationAppearsOnTheJourneyAndOnlyOnItsOwnCall() {
        SituationUpdate atQuay1 = situation("TST:SituationNumber:quay1");
        atQuay1.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(atQuay1));

        Call first = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        Call second = call("NSR:Quay:2", noon.plusMinutes(10), noon.plusMinutes(11));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1", first, second)))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:quay1");
        assertThat(matcher.match(first)).hasSize(1);
        assertThat(matcher.match(second)).isEmpty();
    }

    @Test
    public void testSituationMatchingSeveralWaysAppearsOnceOnTheJourney() {
        SituationUpdate broad = situation("TST:SituationNumber:broad");
        broad.getAffects().addLine(new Line("TST:Line:1"));
        broad.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));
        broad.getAffects().addStopPoint(new StopPoint("NSR:Quay:2"));

        SituationMatcher matcher = new SituationMatcher(List.of(broad));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)),
                call("NSR:Quay:2", noon.plusMinutes(10), noon.plusMinutes(11)))))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:broad");
    }

    @Test
    public void testCallWithNoTimesStillAttachesAMatchingSituation() {
        SituationUpdate longExpired = situation("TST:SituationNumber:quay",
                new ValidityPeriod(noon.minusYears(2), noon.minusYears(1)));
        longExpired.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(longExpired));

        assertThat(matcher.match(call("NSR:Quay:1", null, null)))
                .withFailMessage("missing timestamps must not make a disruption disappear")
                .hasSize(1);
    }

    @Test
    public void testOpenEndedSituationAlwaysAttaches() {
        SituationUpdate openEnded = situation("TST:SituationNumber:openended",
                new ValidityPeriod(noon.minusYears(3), null));
        openEnded.getAffects().addLine(new Line("TST:Line:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(openEnded));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon.plusYears(5), noon.plusYears(5))))).hasSize(1);
    }

    @Test
    public void testJourneyLevelMatchUsesTheWholeJourneySpan() {
        // Valid only during the journey's later half - a line match must still attach,
        // because the journey as a whole overlaps it.
        SituationUpdate laterToday = situation("TST:SituationNumber:later",
                new ValidityPeriod(noon.plusMinutes(20), noon.plusMinutes(40)));
        laterToday.getAffects().addLine(new Line("TST:Line:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(laterToday));

        assertThat(matcher.match(timetable("TST:Line:1", "TST:ServiceJourney:1",
                call("NSR:Quay:1", noon, noon.plusMinutes(1)),
                call("NSR:Quay:2", noon.plusMinutes(30), noon.plusMinutes(31))))).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SituationMatcherTest`
Expected: FAIL — compilation error, `SituationMatcher` does not exist.

- [ ] **Step 3: Implement SituationMatcher**

Create `src/main/java/org/entur/vehicles/data/SituationMatcher.java`:

```java
package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Call;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches situations to estimated timetable data.
 * <p>
 * Built from a snapshot of the situation map and then discarded - it is not a maintained
 * index. Rebuilding per batch is always correct by construction, whereas an index kept in
 * sync with situation replacement and the purge thread could leave a closed situation
 * attached to journeys.
 * <p>
 * Deliberately free of Spring and GraphQL dependencies so the match rule can be tested
 * directly.
 */
public class SituationMatcher {

    private final Map<String, List<SituationUpdate>> byLineRef = new HashMap<>();
    private final Map<String, List<SituationUpdate>> byStopRef = new HashMap<>();
    private final Map<String, List<SituationUpdate>> byServiceJourneyId = new HashMap<>();
    private final Map<String, List<SituationUpdate>> byDatedServiceJourneyId = new HashMap<>();

    public SituationMatcher(Collection<SituationUpdate> situations) {
        for (SituationUpdate situation : situations) {
            if (situation.getProgress() != null && situation.getProgress().isClosed()) {
                continue;
            }
            Affects affects = situation.getAffects();
            if (affects == null) {
                continue;
            }
            index(byLineRef, affects.getLineRefs(), situation);
            index(byStopRef, affects.getStopRefs(), situation);
            index(byServiceJourneyId, affects.getServiceJourneyIds(), situation);
            index(byDatedServiceJourneyId, affects.getDatedServiceJourneyIds(), situation);
        }
    }

    private static void index(Map<String, List<SituationUpdate>> target,
                              Set<String> refs,
                              SituationUpdate situation) {
        for (String ref : refs) {
            target.computeIfAbsent(ref, key -> new ArrayList<>()).add(situation);
        }
    }

    /**
     * Situations affecting this journey: those naming the journey, its dated journey or its
     * line and overlapping the journey's span, plus every call's own stop matches.
     * Deduplicated by situation number.
     */
    public List<SituationUpdate> match(EstimatedTimetableUpdate timetable) {
        Map<String, SituationUpdate> matched = new LinkedHashMap<>();

        ZonedDateTime spanStart = null;
        ZonedDateTime spanEnd = null;
        List<Call> calls = timetable.getCalls();
        if (calls != null) {
            for (Call call : calls) {
                ZonedDateTime start = call.getWindowStart();
                ZonedDateTime end = call.getWindowEnd();
                if (start != null && (spanStart == null || start.isBefore(spanStart))) {
                    spanStart = start;
                }
                if (end != null && (spanEnd == null || end.isAfter(spanEnd))) {
                    spanEnd = end;
                }
            }
        }

        if (timetable.getLine() != null) {
            collect(byLineRef.get(timetable.getLine().getLineRef()), spanStart, spanEnd, matched);
        }
        if (timetable.getServiceJourney() != null) {
            collect(byServiceJourneyId.get(timetable.getServiceJourney().getId()), spanStart, spanEnd, matched);
        }
        if (timetable.getDatedServiceJourney() != null) {
            collect(byDatedServiceJourneyId.get(timetable.getDatedServiceJourney().getId()),
                    spanStart, spanEnd, matched);
        }

        if (calls != null) {
            for (Call call : calls) {
                for (SituationUpdate situation : match(call)) {
                    matched.putIfAbsent(situation.getSituationNumber(), situation);
                }
            }
        }

        return new ArrayList<>(matched.values());
    }

    /**
     * Situations affecting this call's stop while the vehicle is there. A situation on a
     * quay is only relevant if it is in force when the vehicle actually calls: one ending
     * at 12:00 does not apply to a call at 12:30, even though it may be valid right now.
     */
    public List<SituationUpdate> match(Call call) {
        if (call.getStopPoint() == null || call.getStopPoint().getId() == null) {
            return List.of();
        }
        Map<String, SituationUpdate> matched = new LinkedHashMap<>();
        collect(byStopRef.get(call.getStopPoint().getId()),
                call.getWindowStart(), call.getWindowEnd(), matched);
        return new ArrayList<>(matched.values());
    }

    private static void collect(List<SituationUpdate> candidates,
                                ZonedDateTime from,
                                ZonedDateTime to,
                                Map<String, SituationUpdate> matched) {
        if (candidates == null) {
            return;
        }
        for (SituationUpdate situation : candidates) {
            if (situation.isValidDuring(from, to)) {
                matched.putIfAbsent(situation.getSituationNumber(), situation);
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SituationMatcherTest`
Expected: PASS, 11 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn clean test`
Expected: PASS, 121 tests (110 + 11).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/SituationMatcher.java \
        src/test/java/org/entur/vehicles/data/SituationMatcherTest.java
git commit -m "Adding SituationMatcher for the estimated timetable join"
```

---

### Task 3: Batch resolvers and schema

Exposes both fields. This is the first field-level GraphQL resolver in the codebase — `@SchemaMapping` currently appears only as a class-level marker on domain types.

**Files:**
- Create: `src/main/java/org/entur/vehicles/graphql/SituationJoinController.java`
- Modify: `src/main/resources/graphql/vehicle-updates.graphqls`
- Modify: `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java`

**Interfaces:**
- Consumes: `SituationMatcher` (Task 2); `SituationRepository.getSituations(SituationFilter)` — pass `null` to get everything.
- Produces: nothing other code consumes.

**Both resolvers MUST return `List<List<SituationUpdate>>`, not `Map`.** `EstimatedTimetableUpdate` inherits `equals`/`hashCode` from `AbstractUpdate` over `serviceJourney`, `operator`, `codespace`, `mode` and `line` — and NOT `datedServiceJourney`. Two ET messages for the same service journey on different operating days therefore compare equal, and a `Map<Source, Value>` return would collapse them so one receives the other's situations. The `List` form is positional and immune to this.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java`. It already autowires `ExecutionGraphQlService` and `SituationRepository`; you also need `TimetableRepository`.

```java
    @Test
    void situationsResolveOnBothTheJourneyAndTheSpecificCall() {
        situationRepository.add(quaySituationRecord());
        timetableRepository.add(journeyCallingAtQuay());

        String document = """
                query {
                  timetables {
                    serviceJourney { id }
                    situations { situationNumber }
                    calls {
                      stopPoint { id }
                      situations { situationNumber }
                    }
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-join", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<String> journeySituations =
                response.field("timetables[0].situations[*].situationNumber").getValue();
        assertThat(journeySituations).containsExactly("TST:SituationNumber:quay");

        // The stop-triggered situation belongs to the call it came from.
        List<String> firstCallSituations =
                response.field("timetables[0].calls[0].situations[*].situationNumber").getValue();
        assertThat(firstCallSituations).containsExactly("TST:SituationNumber:quay");

        // ...and not to the journey's other call.
        List<String> secondCallSituations =
                response.field("timetables[0].calls[1].situations[*].situationNumber").getValue();
        assertThat(secondCallSituations).isEmpty();
    }
```

Plus the two fixture builders. `quaySituationRecord()` builds a `PtSituationElementRecord` numbered `TST:SituationNumber:quay`, progress `PUBLISHED`, with an `AffectedStopPointRecord` for `NSR:Quay:1` and no validity periods, following the shape of the existing `situationRecord()` helper in that class. `journeyCallingAtQuay()` builds an `EstimatedVehicleJourneyRecord` with `dataSource` `TST`, a `datedVehicleJourneyRef`, and two `EstimatedCallRecord`s — one at `NSR:Quay:1` and one at `NSR:Quay:2`, each with aimed arrival and departure times a few minutes apart — following the shape used in `SituationSnapshotServiceTest` and `TimetableRepositoryStopPointTest`.

If `response.field(...)` does not support the `[*]` projection syntax, read the list of maps from `response.field("timetables[0].situations").getValue()` and extract the numbers in Java. Do not weaken the assertions.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=ApplicationGraphQlSchemaTests`
Expected: FAIL — the schema has no `situations` field on `EstimatedTimetableUpdate` or `Call`, so the query is invalid and `response.getErrors()` is non-empty.

- [ ] **Step 3: Add the schema fields**

In `src/main/resources/graphql/vehicle-updates.graphqls`, add to `type EstimatedTimetableUpdate`:

```graphql
    # Situations affecting this journey: those naming the journey, its dated journey or its
    # line and overlapping the journey, plus those affecting any stop it calls at while the
    # vehicle is there. Closed situations are never included.
    situations: [Situation]
```

and to `type Call`:

```graphql
    # Situations affecting this stop while the vehicle is here. A situation on a quay that
    # ends before the vehicle arrives is not included.
    situations: [Situation]
```

- [ ] **Step 4: Implement the controller**

Create `src/main/java/org/entur/vehicles/graphql/SituationJoinController.java`:

```java
package org.entur.vehicles.graphql;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.SituationMatcher;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.repository.SituationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Attaches situations to estimated timetable data.
 * <p>
 * Batched deliberately: GraphQL resolves a field once per parent object, so a per-object
 * resolver would rebuild the match index for every journey in the result. A batch resolver
 * builds it once per batch.
 * <p>
 * Neither method runs unless the client selects the field, so consumers that do not ask for
 * situations pay nothing.
 */
@Controller
public class SituationJoinController {

    private final SituationRepository situationRepository;

    public SituationJoinController(@Autowired SituationRepository situationRepository) {
        this.situationRepository = situationRepository;
    }

    /**
     * Returns a list positionally aligned with {@code timetables} - NOT a Map keyed by the
     * source objects. {@code EstimatedTimetableUpdate} inherits value-based equals/hashCode
     * from AbstractUpdate that ignore datedServiceJourney, so two journeys on different
     * operating days compare equal and a Map would collapse them.
     */
    @BatchMapping(typeName = "EstimatedTimetableUpdate", field = "situations")
    public List<List<SituationUpdate>> timetableSituations(List<EstimatedTimetableUpdate> timetables) {
        SituationMatcher matcher = matcher();
        List<List<SituationUpdate>> result = new ArrayList<>(timetables.size());
        for (EstimatedTimetableUpdate timetable : timetables) {
            result.add(matcher.match(timetable));
        }
        return result;
    }

    /** Positionally aligned with {@code calls}, for the same reason as above. */
    @BatchMapping(typeName = "Call", field = "situations")
    public List<List<SituationUpdate>> callSituations(List<Call> calls) {
        SituationMatcher matcher = matcher();
        List<List<SituationUpdate>> result = new ArrayList<>(calls.size());
        for (Call call : calls) {
            result.add(matcher.match(call));
        }
        return result;
    }

    private SituationMatcher matcher() {
        return new SituationMatcher(situationRepository.getSituations(null));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=ApplicationGraphQlSchemaTests`
Expected: PASS.

If the batch resolver is not invoked at all — the field resolves to null with no error — check that `typeName` matches the schema type exactly and that the class is annotated `@Controller`. Do not fall back to a per-object `@SchemaMapping` without reporting it.

- [ ] **Step 6: Prove the field is opt-out**

The spec requires this be asserted observably, not assumed. Add to the same test class:

```java
    @Test
    void aTimetablesQueryThatDoesNotSelectSituationsDoesNoMatchingWork() {
        situationRepository.add(quaySituationRecord());
        timetableRepository.add(journeyCallingAtQuay());

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(
                        "query { timetables { serviceJourney { id } calls { stopPoint { id } } } }",
                        null, Map.of(), Map.of(), "test-optout", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        // The resolvers are the only callers of getSituations in this context, so never
        // touching the repository proves no index was built and no matching was done.
        verify(situationRepository, never()).getSituations(any());
    }
```

This needs `situationRepository` to be a spy rather than a plain injected bean. Change its
declaration to a Spring bean-override spy — in this Spring Boot version that is
`@MockitoSpyBean` from `org.springframework.test.context.bean.override.mockito`; on older
Boot it is `@SpyBean` from `org.springframework.boot.test.mock.mockito`. Use whichever the
version on the classpath provides.

Spying changes the bean for the whole test class, so re-run the other tests in it and confirm
they still pass — a spy delegates to the real object, so they should.

If neither annotation is available without adding a dependency, STOP and report it. Do not
replace this with a test that merely asserts the query succeeds — that would assert nothing
about whether work was done, which is exactly the weakness this test exists to close.

- [ ] **Step 7: Run the full suite**

Run: `mvn clean test`
Expected: PASS, 123 tests (121 + 2).

- [ ] **Step 8: Document the fields**

`src/main/resources/Usage.md` has a `## Situations` section. Add to the end of it:

````markdown
Situations are also attached to estimated timetable data, so a consumer fetching a journey
receives the disruptions affecting it without querying the situations feed separately:

```
{
  timetables(codespaceId: "RUT") {
    serviceJourney { id }
    situations { situationNumber severity summary { value } }
    calls {
      stopPoint { id name }
      situations { situationNumber summary { value } }
    }
  }
}
```

A situation attaches to a journey when it names that journey, its dated journey or its line
and overlaps the journey in time, or when it affects a stop the journey calls at. A situation
attached to a call is in force while the vehicle is at that stop specifically — a quay message
that ends before the vehicle arrives is not included. Closed situations are never attached.
````

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/entur/vehicles/graphql/SituationJoinController.java \
        src/main/resources/graphql/vehicle-updates.graphqls \
        src/main/resources/Usage.md \
        src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java
git commit -m "Attaching situations to estimated timetable data"
```

---

## Verification

1. `mvn clean install` passes, 123 tests.
2. `git log --oneline siri_sx_api..HEAD` shows the spec commit plus one commit per task.
3. The 98 tests that existed before this plan still pass.
4. `grep -c "situations" src/main/resources/graphql/vehicle-updates.graphqls` has increased by 2.

**Manual verification** (needs network and GCP credentials; report it skipped rather than faked if unavailable):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--entur.vehicle-positions.sx.enabled=true,--entur.vehicle-positions.et.enabled=true
```

Then in GraphiQL, run the `Usage.md` query above against a codespace with active disruptions and confirm that a stop-level situation appears against the call for its own stop and not the others.

Do not claim the feature is complete without the output of `mvn clean install` in hand.
