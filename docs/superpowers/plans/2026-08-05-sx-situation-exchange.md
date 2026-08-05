# SIRI-SX (Situation Exchange) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SIRI-SX situation messages as a third real-time stream alongside Vehicle Monitoring and Estimated Timetables, exposed as a standalone GraphQL query and subscription.

**Architecture:** Mirrors the existing ET pipeline exactly — a Pub/Sub subscriber decodes avro records, a mapper converts them to a domain object enriched via the existing Line/Operator/NSR services, an auto-purging in-memory map stores them, and a Reactor sink publishes updates to subscribers. The one structural departure is lifecycle: situations without a validity end time are retained indefinitely so that producers who never close them can be identified.

**Tech Stack:** Java 21, Spring Boot (spring-boot-starter-graphql), Google Cloud Pub/Sub, `org.entur:siri-avro-model:2.0.4`, Reactor, Guava, Micrometer/Prometheus, JUnit 5 + Mockito.

**Design spec:** `docs/superpowers/specs/2026-08-05-sx-situation-exchange-design.md`

## Global Constraints

- Package root is `org.entur.vehicles`. Follow existing package layout: `data/`, `data/model/`, `repository/`, `service/`, `service/pubsub/impl/`, `graphql/`, `graphql/publishers/`.
- The service has **no database**. All state is in-memory and must be safe for concurrent access.
- GraphQL schema lives in a single file: `src/main/resources/graphql/vehicle-updates.graphqls`.
- Domain classes exposed through GraphQL carry `@SchemaMapping` (see `Line`, `Operator`, `Codespace`).
- `ObjectRef.matches(ObjectRef)` performs **regex** matching (`getRef().matches(other.getRef())`). Situation filtering uses exact `Set.contains` instead — do not route SX matching through `ObjectRef.matches`.
- Avro string fields are `CharSequence`, not `String`. Always `.toString()` them, and null-check first.
- Timestamps arrive as ISO strings; convert with `org.entur.vehicles.repository.helpers.Util.convert(CharSequence)`.
- Build and test with `mvn`. Full suite: `mvn clean install`. Single class: `mvn test -Dtest=ClassName`. Single method: `mvn test -Dtest=ClassName#methodName`.
- Commit after every task. No Claude/AI attribution in commit messages — match the existing terse style (`Adding design-spec for SIRI-SX support`).
- Work on branch `siri_sx_api`.

## Task Overview

| # | Deliverable |
|---|---|
| 1 | Generify `AutoPurgingMap<K, V>` |
| 2 | `SeverityEnumeration` + `WorkflowStatusEnumeration` |
| 3 | `TranslatedString`, `ValidityPeriod`, `InfoLink` |
| 4 | `Affects` with deduplicating id-sets |
| 5 | `SituationUpdate`, `SituationKey`, `AutoPurgingSituationMap` |
| 6 | `SituationMapper` (avro record → domain) |
| 7 | `SituationFilter` |
| 8 | `SituationRepository` + update metric |
| 9 | Publisher, GraphQL schema, Query/Subscription wiring |
| 10 | `PubSubSXSubscriber` + configuration |

**Refinement over the spec:** the spec places mapping inside `SituationRepository.add()`. This plan extracts it into a separate `SituationMapper` (Task 6) so that neither file grows unwieldy — the repository keeps storage, the version guard and publishing; the mapper keeps avro translation and enrichment. Behaviour is identical.

**Refinement over the spec:** the spec states the GraphQL enums are "ours to define with defensive parsing" because the avro *fields* are plain strings. That remains true, but the avro model does ship `SeverityEnum` and `WorkflowStatusEnum` classes whose symbols those strings carry, so `fromValue` can switch over them exactly like the existing `OccupancyStatus.fromValue` does, wrapped in a try/catch for unknown values.

---

### Task 1: Generify AutoPurgingMap

`AutoPurgingMap` is hardcoded to `StorageKey`. Situations are keyed differently, so the key type becomes a parameter. This is a pure refactor with no behavioural change — the existing test suites are the acceptance gate.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/repository/AutoPurgingMap.java`
- Modify: `src/main/java/org/entur/vehicles/repository/AutoPurgingVehicleMap.java:13`
- Modify: `src/main/java/org/entur/vehicles/repository/AutoPurgingTimetableMap.java:13`

**Interfaces:**
- Consumes: nothing.
- Produces: `abstract class AutoPurgingMap<K, V> extends ConcurrentHashMap<K, V>` with protected field `gracePeriod` (`java.time.Duration`) and `public abstract void removeExpiredVehicles()`.

- [ ] **Step 1: Run the existing suite to establish a green baseline**

Run: `mvn test`
Expected: PASS. Note the number of tests run — Task 1 must not change it.

- [ ] **Step 2: Generify the base class**

Replace the class declaration in `AutoPurgingMap.java`. Only the type parameters change; the constructor body and abstract method are untouched.

```java
package org.entur.vehicles.repository;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class AutoPurgingMap<K, V> extends ConcurrentHashMap<K, V> {

    final Duration gracePeriod;

    public AutoPurgingMap(Duration purgeInterval, Duration gracePeriod) {
        super();
        this.gracePeriod = gracePeriod;
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        long purgeIntervalSeconds = purgeInterval.getSeconds();
        service.scheduleWithFixedDelay(this::removeExpiredVehicles, purgeIntervalSeconds, purgeIntervalSeconds, TimeUnit.SECONDS);
    }

    public abstract void removeExpiredVehicles();
}
```

- [ ] **Step 3: Update the two existing subclasses**

In `AutoPurgingVehicleMap.java`, change the `extends` clause only:

```java
public class AutoPurgingVehicleMap extends AutoPurgingMap<StorageKey, VehicleUpdate> {
```

In `AutoPurgingTimetableMap.java`:

```java
public class AutoPurgingTimetableMap extends AutoPurgingMap<StorageKey, EstimatedTimetableUpdate> {
```

Both files need `import org.entur.vehicles.repository.StorageKey;` only if they are in a different package — they are not, so no import changes are required.

- [ ] **Step 4: Verify nothing else referenced the raw type**

Run: `grep -rn "AutoPurgingMap" src/main src/test`
Expected: only the three files above. If `VehicleRepository` or `TimetableRepository` declare a field typed `AutoPurgingMap` without parameters, add the parameters.

- [ ] **Step 5: Compile and run the full suite**

Run: `mvn clean test`
Expected: PASS, with the same test count as Step 1.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/AutoPurgingMap.java \
        src/main/java/org/entur/vehicles/repository/AutoPurgingVehicleMap.java \
        src/main/java/org/entur/vehicles/repository/AutoPurgingTimetableMap.java
git commit -m "Generifying AutoPurgingMap key type"
```

---

### Task 2: Severity and workflow-status enums

**Files:**
- Create: `src/main/java/org/entur/vehicles/data/SeverityEnumeration.java`
- Create: `src/main/java/org/entur/vehicles/data/WorkflowStatusEnumeration.java`
- Test: `src/test/java/org/entur/vehicles/data/SituationEnumerationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `SeverityEnumeration.fromValue(String) -> SeverityEnumeration`, falling back to `undefined`.
  - `WorkflowStatusEnumeration.fromValue(String) -> WorkflowStatusEnumeration`, falling back to `open`.
  - `WorkflowStatusEnumeration.isClosed() -> boolean`.

The avro record carries these as plain strings holding the symbol names of `org.entur.avro.realtime.siri.model.SeverityEnum` and `WorkflowStatusEnum` (e.g. `"VERY_SEVERE"`, `"CLOSED"`). The GraphQL enums use SIRI's lowerCamelCase spelling, matching how `OccupancyStatus` is written.

`open` is the fallback for an absent or unrecognised progress value because SIRI treats a situation without an explicit `Progress` as active — defaulting to `closed` would silently discard live situations.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/data/SituationEnumerationTest.java`:

```java
package org.entur.vehicles.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationEnumerationTest {

    @Test
    public void testSeverityMapsAllAvroSymbols() {
        assertEquals(SeverityEnumeration.noImpact, SeverityEnumeration.fromValue("NO_IMPACT"));
        assertEquals(SeverityEnumeration.verySlight, SeverityEnumeration.fromValue("VERY_SLIGHT"));
        assertEquals(SeverityEnumeration.slight, SeverityEnumeration.fromValue("SLIGHT"));
        assertEquals(SeverityEnumeration.normal, SeverityEnumeration.fromValue("NORMAL"));
        assertEquals(SeverityEnumeration.severe, SeverityEnumeration.fromValue("SEVERE"));
        assertEquals(SeverityEnumeration.verySevere, SeverityEnumeration.fromValue("VERY_SEVERE"));
        assertEquals(SeverityEnumeration.unknown, SeverityEnumeration.fromValue("UNKNOWN"));
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue("UNDEFINED"));
    }

    @Test
    public void testSeverityFallsBackToUndefined() {
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue(null));
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue("NOT_A_SEVERITY"));
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue(""));
    }

    @Test
    public void testProgressMapsAllAvroSymbols() {
        assertEquals(WorkflowStatusEnumeration.draft, WorkflowStatusEnumeration.fromValue("DRAFT"));
        assertEquals(WorkflowStatusEnumeration.pendingApproval, WorkflowStatusEnumeration.fromValue("PENDING_APPROVAL"));
        assertEquals(WorkflowStatusEnumeration.approvedDraft, WorkflowStatusEnumeration.fromValue("APPROVED_DRAFT"));
        assertEquals(WorkflowStatusEnumeration.open, WorkflowStatusEnumeration.fromValue("OPEN"));
        assertEquals(WorkflowStatusEnumeration.published, WorkflowStatusEnumeration.fromValue("PUBLISHED"));
        assertEquals(WorkflowStatusEnumeration.closing, WorkflowStatusEnumeration.fromValue("CLOSING"));
        assertEquals(WorkflowStatusEnumeration.closed, WorkflowStatusEnumeration.fromValue("CLOSED"));
    }

    @Test
    public void testProgressFallsBackToOpenSoLiveSituationsAreNotDiscarded() {
        assertEquals(WorkflowStatusEnumeration.open, WorkflowStatusEnumeration.fromValue(null));
        assertEquals(WorkflowStatusEnumeration.open, WorkflowStatusEnumeration.fromValue("NOT_A_STATUS"));
    }

    @Test
    public void testIsClosed() {
        assertTrue(WorkflowStatusEnumeration.closed.isClosed());
        assertFalse(WorkflowStatusEnumeration.closing.isClosed());
        assertFalse(WorkflowStatusEnumeration.open.isClosed());
        assertFalse(WorkflowStatusEnumeration.published.isClosed());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SituationEnumerationTest`
Expected: FAIL — compilation error, `SeverityEnumeration` and `WorkflowStatusEnumeration` do not exist.

- [ ] **Step 3: Implement SeverityEnumeration**

Create `src/main/java/org/entur/vehicles/data/SeverityEnumeration.java`:

```java
package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.SeverityEnum;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public enum SeverityEnumeration {

    unknown,
    verySlight,
    slight,
    normal,
    severe,
    verySevere,
    noImpact,
    undefined;

    public static SeverityEnumeration fromValue(String severity) {
        if (severity == null) {
            return undefined;
        }
        try {
            return switch (SeverityEnum.valueOf(severity)) {
                case UNKNOWN -> unknown;
                case VERY_SLIGHT -> verySlight;
                case SLIGHT -> slight;
                case NORMAL -> normal;
                case SEVERE -> severe;
                case VERY_SEVERE -> verySevere;
                case NO_IMPACT -> noImpact;
                case UNDEFINED -> undefined;
            };
        } catch (IllegalArgumentException e) {
            return undefined;
        }
    }
}
```

- [ ] **Step 4: Implement WorkflowStatusEnumeration**

Create `src/main/java/org/entur/vehicles/data/WorkflowStatusEnumeration.java`:

```java
package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.WorkflowStatusEnum;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public enum WorkflowStatusEnumeration {

    draft,
    pendingApproval,
    approvedDraft,
    open,
    published,
    closing,
    closed;

    public static WorkflowStatusEnumeration fromValue(String progress) {
        if (progress == null) {
            // SIRI treats a situation without an explicit Progress as active.
            return open;
        }
        try {
            return switch (WorkflowStatusEnum.valueOf(progress)) {
                case DRAFT -> draft;
                case PENDING_APPROVAL -> pendingApproval;
                case APPROVED_DRAFT -> approvedDraft;
                case OPEN -> open;
                case PUBLISHED -> published;
                case CLOSING -> closing;
                case CLOSED -> closed;
            };
        } catch (IllegalArgumentException e) {
            return open;
        }
    }

    public boolean isClosed() {
        return this == closed;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=SituationEnumerationTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/SeverityEnumeration.java \
        src/main/java/org/entur/vehicles/data/WorkflowStatusEnumeration.java \
        src/test/java/org/entur/vehicles/data/SituationEnumerationTest.java
git commit -m "Adding SX severity and workflow-status enums"
```

---

### Task 3: Situation value types

Three small immutable holders for the translated text, validity windows and info links carried by a situation.

**Files:**
- Create: `src/main/java/org/entur/vehicles/data/model/TranslatedString.java`
- Create: `src/main/java/org/entur/vehicles/data/model/ValidityPeriod.java`
- Create: `src/main/java/org/entur/vehicles/data/model/InfoLink.java`
- Test: `src/test/java/org/entur/vehicles/data/model/ValidityPeriodTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `new TranslatedString(String value, String language)` with `getValue()`, `getLanguage()`.
  - `new ValidityPeriod(ZonedDateTime startTime, ZonedDateTime endTime)` with `getStartTime()`, `getEndTime()`, `isOpenEnded()`, `isValidAt(ZonedDateTime)`.
  - `new InfoLink(String uri, List<TranslatedString> labels)` with `getUri()`, `getLabels()`.

`ValidityPeriod.isValidAt` is the primitive behind the `validNow` filter, so it carries the only test in this task. A null `startTime` is treated as "has always been valid" and a null `endTime` as "never ends".

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/data/model/ValidityPeriodTest.java`:

```java
package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ValidityPeriodTest {

    private final ZonedDateTime now = ZonedDateTime.now();

    @Test
    public void testCurrentPeriodIsValid() {
        ValidityPeriod period = new ValidityPeriod(now.minusHours(1), now.plusHours(1));
        assertTrue(period.isValidAt(now));
        assertFalse(period.isOpenEnded());
    }

    @Test
    public void testFuturePeriodIsNotYetValid() {
        ValidityPeriod period = new ValidityPeriod(now.plusHours(1), now.plusHours(2));
        assertFalse(period.isValidAt(now));
    }

    @Test
    public void testEndedPeriodIsNoLongerValid() {
        ValidityPeriod period = new ValidityPeriod(now.minusHours(2), now.minusHours(1));
        assertFalse(period.isValidAt(now));
    }

    @Test
    public void testMissingEndTimeMeansOpenEndedAndStillValid() {
        ValidityPeriod period = new ValidityPeriod(now.minusDays(400), null);
        assertTrue(period.isOpenEnded());
        assertTrue(period.isValidAt(now));
    }

    @Test
    public void testMissingStartTimeMeansAlwaysStarted() {
        ValidityPeriod period = new ValidityPeriod(null, now.plusHours(1));
        assertTrue(period.isValidAt(now));
        assertFalse(period.isOpenEnded());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=ValidityPeriodTest`
Expected: FAIL — compilation error, `ValidityPeriod` does not exist.

- [ ] **Step 3: Implement the three value types**

Create `src/main/java/org/entur/vehicles/data/model/ValidityPeriod.java`:

```java
package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.ZonedDateTime;

@SchemaMapping
public class ValidityPeriod {

    private final ZonedDateTime startTime;
    private final ZonedDateTime endTime;

    public ValidityPeriod(ZonedDateTime startTime, ZonedDateTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }

    public boolean isOpenEnded() {
        return endTime == null;
    }

    public boolean isValidAt(ZonedDateTime timestamp) {
        if (startTime != null && startTime.isAfter(timestamp)) {
            return false;
        }
        return endTime == null || !endTime.isBefore(timestamp);
    }
}
```

Create `src/main/java/org/entur/vehicles/data/model/TranslatedString.java`:

```java
package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public class TranslatedString {

    private final String value;
    private final String language;

    public TranslatedString(String value, String language) {
        this.value = value;
        this.language = language;
    }

    public String getValue() {
        return value;
    }

    public String getLanguage() {
        return language;
    }
}
```

Create `src/main/java/org/entur/vehicles/data/model/InfoLink.java`:

```java
package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

@SchemaMapping
public class InfoLink {

    private final String uri;
    private final List<TranslatedString> labels;

    public InfoLink(String uri, List<TranslatedString> labels) {
        this.uri = uri;
        this.labels = labels;
    }

    public String getUri() {
        return uri;
    }

    public List<TranslatedString> getLabels() {
        return labels;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=ValidityPeriodTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/model/ValidityPeriod.java \
        src/main/java/org/entur/vehicles/data/model/TranslatedString.java \
        src/main/java/org/entur/vehicles/data/model/InfoLink.java \
        src/test/java/org/entur/vehicles/data/model/ValidityPeriodTest.java
git commit -m "Adding SX value types for validity, translations and info links"
```

---

### Task 4: Affects

Holds the flattened set of objects a situation affects. Each `add*` method maintains both the display list and a parallel `Set<String>` of identifiers; the set membership check is what deduplicates, so a line named by both an affected network and an affected vehicle journey appears exactly once.

The id-sets exist for performance. Filtering evaluates against every situation in the map on every query and every published update; without them each criterion would walk nested lists.

**Files:**
- Create: `src/main/java/org/entur/vehicles/data/model/Affects.java`
- Test: `src/test/java/org/entur/vehicles/data/model/AffectsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Affects` with no-arg constructor and:
  - `addLine(Line)`, `addStopPoint(StopPoint)`, `addStopPlace(StopPoint)`, `addServiceJourney(ServiceJourney)`, `addDatedServiceJourney(DatedServiceJourney)`, `addOperator(Operator)`, `addVehicleMode(VehicleModeEnumeration)` — all null-safe, all no-ops on a duplicate identifier.
  - Getters `getLines()`, `getStopPoints()`, `getStopPlaces()`, `getServiceJourneys()`, `getDatedServiceJourneys()`, `getOperators()`, `getVehicleModes()`.
  - Identifier accessors `getLineRefs()`, `getStopRefs()`, `getServiceJourneyIds()`, `getDatedServiceJourneyIds()`, `getOperatorRefs()` — all `Set<String>`.
  - `isEmpty()`.

Note `getStopRefs()` covers stop points **and** stop places in one set, because the `stopRef` filter argument matches either.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/data/model/AffectsTest.java`:

```java
package org.entur.vehicles.data.model;

import org.entur.vehicles.data.VehicleModeEnumeration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AffectsTest {

    @Test
    public void testEmptyByDefault() {
        Affects affects = new Affects();
        assertTrue(affects.isEmpty());
        assertTrue(affects.getLines().isEmpty());
        assertTrue(affects.getLineRefs().isEmpty());
    }

    @Test
    public void testAddingLinePopulatesBothListAndRefSet() {
        Affects affects = new Affects();
        affects.addLine(new Line("TST:Line:1"));

        assertFalse(affects.isEmpty());
        assertEquals(1, affects.getLines().size());
        assertTrue(affects.getLineRefs().contains("TST:Line:1"));
    }

    @Test
    public void testDuplicateLineIsAddedOnce() {
        Affects affects = new Affects();
        affects.addLine(new Line("TST:Line:1", "First"));
        affects.addLine(new Line("TST:Line:1", "Duplicate from vehicle journey"));

        assertEquals(1, affects.getLines().size());
        assertEquals("First", affects.getLines().get(0).getLineName());
        assertEquals(1, affects.getLineRefs().size());
    }

    @Test
    public void testStopPointsAndStopPlacesShareOneRefSet() {
        Affects affects = new Affects();
        affects.addStopPoint(new StopPoint("TST:Quay:1"));
        affects.addStopPlace(new StopPoint("TST:StopPlace:9"));

        assertEquals(1, affects.getStopPoints().size());
        assertEquals(1, affects.getStopPlaces().size());
        assertEquals(2, affects.getStopRefs().size());
        assertTrue(affects.getStopRefs().contains("TST:Quay:1"));
        assertTrue(affects.getStopRefs().contains("TST:StopPlace:9"));
    }

    @Test
    public void testJourneysOperatorsAndModes() {
        Affects affects = new Affects();
        affects.addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));
        affects.addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:1"));
        affects.addOperator(new Operator("TST:Operator:1"));
        affects.addVehicleMode(VehicleModeEnumeration.BUS);
        affects.addVehicleMode(VehicleModeEnumeration.BUS);

        assertTrue(affects.getServiceJourneyIds().contains("TST:ServiceJourney:1"));
        assertTrue(affects.getDatedServiceJourneyIds().contains("TST:DatedServiceJourney:1"));
        assertTrue(affects.getOperatorRefs().contains("TST:Operator:1"));
        assertEquals(1, affects.getVehicleModes().size());
    }

    @Test
    public void testNullsAreIgnored() {
        Affects affects = new Affects();
        affects.addLine(null);
        affects.addLine(new Line((String) null));
        affects.addStopPoint(null);
        affects.addOperator(null);
        affects.addVehicleMode(null);

        assertTrue(affects.isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AffectsTest`
Expected: FAIL — compilation error, `Affects` does not exist.

- [ ] **Step 3: Implement Affects**

Create `src/main/java/org/entur/vehicles/data/model/Affects.java`:

```java
package org.entur.vehicles.data.model;

import org.entur.vehicles.data.VehicleModeEnumeration;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Flattened view of the objects a situation affects.
 * <p>
 * Alongside the display lists, each identifier is kept in a {@link Set} so that
 * filtering is a constant-time lookup rather than a walk of nested lists. Adding
 * an object whose identifier is already present is a no-op, which deduplicates
 * references that SIRI carries in more than one place - a line, for instance, can
 * be named both by an affected network and by an affected vehicle journey.
 */
@SchemaMapping
public class Affects {

    private final List<Line> lines = new ArrayList<>();
    private final List<StopPoint> stopPoints = new ArrayList<>();
    private final List<StopPoint> stopPlaces = new ArrayList<>();
    private final List<ServiceJourney> serviceJourneys = new ArrayList<>();
    private final List<DatedServiceJourney> datedServiceJourneys = new ArrayList<>();
    private final List<Operator> operators = new ArrayList<>();
    private final Set<VehicleModeEnumeration> vehicleModes = new LinkedHashSet<>();

    private final Set<String> lineRefs = new HashSet<>();
    private final Set<String> stopRefs = new HashSet<>();
    private final Set<String> serviceJourneyIds = new HashSet<>();
    private final Set<String> datedServiceJourneyIds = new HashSet<>();
    private final Set<String> operatorRefs = new HashSet<>();

    public void addLine(Line line) {
        if (line != null && line.getLineRef() != null && lineRefs.add(line.getLineRef())) {
            lines.add(line);
        }
    }

    public void addStopPoint(StopPoint stopPoint) {
        if (stopPoint != null && stopPoint.getId() != null && stopRefs.add(stopPoint.getId())) {
            stopPoints.add(stopPoint);
        }
    }

    public void addStopPlace(StopPoint stopPlace) {
        if (stopPlace != null && stopPlace.getId() != null && stopRefs.add(stopPlace.getId())) {
            stopPlaces.add(stopPlace);
        }
    }

    public void addServiceJourney(ServiceJourney serviceJourney) {
        if (serviceJourney != null && serviceJourney.getId() != null && serviceJourneyIds.add(serviceJourney.getId())) {
            serviceJourneys.add(serviceJourney);
        }
    }

    public void addDatedServiceJourney(DatedServiceJourney datedServiceJourney) {
        if (datedServiceJourney != null && datedServiceJourney.getId() != null
                && datedServiceJourneyIds.add(datedServiceJourney.getId())) {
            datedServiceJourneys.add(datedServiceJourney);
        }
    }

    public void addOperator(Operator operator) {
        if (operator != null && operator.getOperatorRef() != null && operatorRefs.add(operator.getOperatorRef())) {
            operators.add(operator);
        }
    }

    public void addVehicleMode(VehicleModeEnumeration mode) {
        if (mode != null) {
            vehicleModes.add(mode);
        }
    }

    public List<Line> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<StopPoint> getStopPoints() {
        return Collections.unmodifiableList(stopPoints);
    }

    public List<StopPoint> getStopPlaces() {
        return Collections.unmodifiableList(stopPlaces);
    }

    public List<ServiceJourney> getServiceJourneys() {
        return Collections.unmodifiableList(serviceJourneys);
    }

    public List<DatedServiceJourney> getDatedServiceJourneys() {
        return Collections.unmodifiableList(datedServiceJourneys);
    }

    public List<Operator> getOperators() {
        return Collections.unmodifiableList(operators);
    }

    public Set<VehicleModeEnumeration> getVehicleModes() {
        return Collections.unmodifiableSet(vehicleModes);
    }

    public Set<String> getLineRefs() {
        return lineRefs;
    }

    /** Covers both stop points and stop places - the `stopRef` filter matches either. */
    public Set<String> getStopRefs() {
        return stopRefs;
    }

    public Set<String> getServiceJourneyIds() {
        return serviceJourneyIds;
    }

    public Set<String> getDatedServiceJourneyIds() {
        return datedServiceJourneyIds;
    }

    public Set<String> getOperatorRefs() {
        return operatorRefs;
    }

    public boolean isEmpty() {
        return lines.isEmpty()
                && stopPoints.isEmpty()
                && stopPlaces.isEmpty()
                && serviceJourneys.isEmpty()
                && datedServiceJourneys.isEmpty()
                && operators.isEmpty()
                && vehicleModes.isEmpty();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=AffectsTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/model/Affects.java \
        src/test/java/org/entur/vehicles/data/model/AffectsTest.java
git commit -m "Adding Affects model with deduplicating identifier sets"
```

---

### Task 5: SituationUpdate, SituationKey and the storage map

`SituationUpdate` is a standalone class — it deliberately does **not** extend `AbstractUpdate`, because that base assumes a single line, operator and service journey while a situation affects many of each.

The nullable `expiration` is the crux of this task: `null` means *never expires*, which is how open-ended situations are retained indefinitely for the data-quality tooling.

**Files:**
- Create: `src/main/java/org/entur/vehicles/data/SituationUpdate.java`
- Create: `src/main/java/org/entur/vehicles/repository/SituationKey.java`
- Create: `src/main/java/org/entur/vehicles/repository/AutoPurgingSituationMap.java`
- Test: `src/test/java/org/entur/vehicles/repository/AutoPurgingSituationMapTest.java`

**Interfaces:**
- Consumes: `Affects` (Task 4), `ValidityPeriod`/`TranslatedString`/`InfoLink` (Task 3), `SeverityEnumeration`/`WorkflowStatusEnumeration` (Task 2), `AutoPurgingMap<K, V>` (Task 1).
- Produces:
  - `SituationUpdate` — no-arg constructor, getter/setter pairs for every field listed below, plus derived read-only `getOpenEnded()`, `getAge()`, `getLastUpdatedEpochSecond()`, `getExpirationEpochSecond()`.
  - `new SituationKey(Codespace codespace, String situationNumber)` with value `equals`/`hashCode`.
  - `new AutoPurgingSituationMap(Duration purgeInterval, Duration gracePeriod)` extending `AutoPurgingMap<SituationKey, SituationUpdate>`.

`getExpirationEpochSecond()` returns `Long` (boxed, nullable), unlike `EstimatedTimetableUpdate.getLastUpdatedEpochSecond()` which returns a primitive `long`. A situation's expiration is legitimately null, so a primitive would throw.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/repository/AutoPurgingSituationMapTest.java`:

```java
package org.entur.vehicles.repository;

import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoPurgingSituationMapTest {

    private SituationUpdate situation(String situationNumber, ZonedDateTime expiration) {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber(situationNumber);
        situation.setCodespace(Codespace.getCodespace("TST"));
        situation.setExpiration(expiration);
        situation.setLastUpdated(ZonedDateTime.now());
        return situation;
    }

    @Test
    public void testExpiredSituationIsPurgedAfterGracePeriod() {
        AutoPurgingSituationMap map =
                new AutoPurgingSituationMap(Duration.parse("PT1S"), Duration.parse("PT1S"));

        SituationUpdate expired = situation("TST:SituationNumber:1", ZonedDateTime.now().minusHours(1));
        map.put(new SituationKey(expired.getCodespace(), expired.getSituationNumber()), expired);
        assertEquals(1, map.size());

        map.removeExpiredVehicles();
        assertEquals(0, map.size());
    }

    @Test
    public void testSituationWithinGracePeriodIsRetained() {
        AutoPurgingSituationMap map =
                new AutoPurgingSituationMap(Duration.parse("PT1S"), Duration.parse("PT10M"));

        SituationUpdate justExpired = situation("TST:SituationNumber:2", ZonedDateTime.now().minusSeconds(5));
        map.put(new SituationKey(justExpired.getCodespace(), justExpired.getSituationNumber()), justExpired);

        map.removeExpiredVehicles();
        assertEquals(1, map.size());
    }

    @Test
    public void testOpenEndedSituationIsNeverPurged() {
        AutoPurgingSituationMap map =
                new AutoPurgingSituationMap(Duration.parse("PT1S"), Duration.parse("PT1S"));

        SituationUpdate openEnded = situation("TST:SituationNumber:3", null);
        map.put(new SituationKey(openEnded.getCodespace(), openEnded.getSituationNumber()), openEnded);

        map.removeExpiredVehicles();
        map.removeExpiredVehicles();
        map.removeExpiredVehicles();

        assertEquals(1, map.size());
    }

    @Test
    public void testKeyEqualityIsByCodespaceAndSituationNumber() {
        SituationKey first = new SituationKey(Codespace.getCodespace("TST"), "TST:SituationNumber:1");
        SituationKey same = new SituationKey(Codespace.getCodespace("TST"), "TST:SituationNumber:1");
        SituationKey otherCodespace = new SituationKey(Codespace.getCodespace("ABC"), "TST:SituationNumber:1");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(otherCodespace));
    }

    @Test
    public void testOpenEndedDerivedFromValidityPeriods() {
        SituationUpdate withEnd = situation("TST:SituationNumber:4", null);
        withEnd.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(1))));
        assertFalse(withEnd.getOpenEnded());

        SituationUpdate withoutEnd = situation("TST:SituationNumber:5", null);
        withoutEnd.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(1), null)));
        assertTrue(withoutEnd.getOpenEnded());

        SituationUpdate noPeriods = situation("TST:SituationNumber:6", null);
        assertTrue(noPeriods.getOpenEnded());
    }

    @Test
    public void testAgeAndEpochAccessorsAreNullSafe() {
        SituationUpdate situation = situation("TST:SituationNumber:7", null);

        assertNull(situation.getExpirationEpochSecond());
        assertNull(situation.getAge());

        situation.setCreationTime(ZonedDateTime.now().minusDays(2));
        assertTrue(situation.getAge().toDays() >= 2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AutoPurgingSituationMapTest`
Expected: FAIL — compilation error, `SituationUpdate` does not exist.

- [ ] **Step 3: Implement SituationUpdate**

Create `src/main/java/org/entur/vehicles/data/SituationUpdate.java`:

```java
package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.InfoLink;
import org.entur.vehicles.data.model.TranslatedString;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * A SIRI-SX situation.
 * <p>
 * Deliberately does not extend {@code AbstractUpdate}: that base models a single
 * line, operator and service journey, whereas a situation affects many of each.
 * <p>
 * A null {@link #getExpiration()} means the situation never expires. Situations
 * published without a validity end time are retained indefinitely so that
 * producers who never close them can be identified.
 */
@SchemaMapping
public class SituationUpdate {

    private String situationNumber;
    private String participantRef;
    private Codespace codespace;
    private Integer version;
    private String sourceType;
    private WorkflowStatusEnumeration progress;
    private SeverityEnumeration severity;
    private Integer priority;
    private String reportType;
    private List<String> keywords;
    private Boolean planned;
    private ZonedDateTime creationTime;
    private ZonedDateTime versionedAtTime;
    private List<ValidityPeriod> validityPeriods;
    private List<TranslatedString> summary;
    private List<TranslatedString> description;
    private List<TranslatedString> advice;
    private List<TranslatedString> detail;
    private List<InfoLink> infoLinks;
    private Affects affects;
    private ZonedDateTime lastUpdated;
    private ZonedDateTime expiration;

    public String getSituationNumber() {
        return situationNumber;
    }

    public void setSituationNumber(String situationNumber) {
        this.situationNumber = situationNumber;
    }

    public String getParticipantRef() {
        return participantRef;
    }

    public void setParticipantRef(String participantRef) {
        this.participantRef = participantRef;
    }

    public Codespace getCodespace() {
        return codespace;
    }

    public void setCodespace(Codespace codespace) {
        this.codespace = codespace;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public WorkflowStatusEnumeration getProgress() {
        return progress;
    }

    public void setProgress(WorkflowStatusEnumeration progress) {
        this.progress = progress;
    }

    public SeverityEnumeration getSeverity() {
        return severity;
    }

    public void setSeverity(SeverityEnumeration severity) {
        this.severity = severity;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public Boolean getPlanned() {
        return planned;
    }

    public void setPlanned(Boolean planned) {
        this.planned = planned;
    }

    public ZonedDateTime getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(ZonedDateTime creationTime) {
        this.creationTime = creationTime;
    }

    public ZonedDateTime getVersionedAtTime() {
        return versionedAtTime;
    }

    public void setVersionedAtTime(ZonedDateTime versionedAtTime) {
        this.versionedAtTime = versionedAtTime;
    }

    public List<ValidityPeriod> getValidityPeriods() {
        return validityPeriods;
    }

    public void setValidityPeriods(List<ValidityPeriod> validityPeriods) {
        this.validityPeriods = validityPeriods;
    }

    public List<TranslatedString> getSummary() {
        return summary;
    }

    public void setSummary(List<TranslatedString> summary) {
        this.summary = summary;
    }

    public List<TranslatedString> getDescription() {
        return description;
    }

    public void setDescription(List<TranslatedString> description) {
        this.description = description;
    }

    public List<TranslatedString> getAdvice() {
        return advice;
    }

    public void setAdvice(List<TranslatedString> advice) {
        this.advice = advice;
    }

    public List<TranslatedString> getDetail() {
        return detail;
    }

    public void setDetail(List<TranslatedString> detail) {
        this.detail = detail;
    }

    public List<InfoLink> getInfoLinks() {
        return infoLinks;
    }

    public void setInfoLinks(List<InfoLink> infoLinks) {
        this.infoLinks = infoLinks;
    }

    public Affects getAffects() {
        return affects;
    }

    public void setAffects(Affects affects) {
        this.affects = affects;
    }

    public ZonedDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(ZonedDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Long getLastUpdatedEpochSecond() {
        return lastUpdated != null ? lastUpdated.toEpochSecond() : null;
    }

    /** Null means the situation never expires. */
    public ZonedDateTime getExpiration() {
        return expiration;
    }

    public void setExpiration(ZonedDateTime expiration) {
        this.expiration = expiration;
    }

    public Long getExpirationEpochSecond() {
        return expiration != null ? expiration.toEpochSecond() : null;
    }

    /** True when no validity period carries an end time. */
    public Boolean getOpenEnded() {
        if (validityPeriods == null || validityPeriods.isEmpty()) {
            return true;
        }
        return validityPeriods.stream().allMatch(ValidityPeriod::isOpenEnded);
    }

    /** Time elapsed since creationTime; null when creationTime is absent. */
    public Duration getAge() {
        return creationTime != null ? Duration.between(creationTime, ZonedDateTime.now()) : null;
    }

    public boolean isValidAt(ZonedDateTime timestamp) {
        if (validityPeriods == null || validityPeriods.isEmpty()) {
            return true;
        }
        return validityPeriods.stream().anyMatch(period -> period.isValidAt(timestamp));
    }
}
```

- [ ] **Step 4: Implement SituationKey**

Create `src/main/java/org/entur/vehicles/repository/SituationKey.java`, following `StorageKey`'s precomputed-hash pattern:

```java
package org.entur.vehicles.repository;

import com.google.common.base.Objects;
import org.entur.vehicles.data.model.Codespace;

public class SituationKey {
    private final Codespace codespace;
    private final String situationNumber;
    private final int hashCode;

    public SituationKey(Codespace codespace, String situationNumber) {
        this.codespace = codespace;
        this.situationNumber = situationNumber;
        hashCode = Objects.hashCode(codespace, situationNumber);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SituationKey that)) return false;
        return Objects.equal(codespace, that.codespace) &&
                Objects.equal(situationNumber, that.situationNumber);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
```

- [ ] **Step 5: Implement AutoPurgingSituationMap**

Create `src/main/java/org/entur/vehicles/repository/AutoPurgingSituationMap.java`. Note the null check — this is what keeps open-ended situations alive.

```java
package org.entur.vehicles.repository;

import org.entur.vehicles.data.SituationUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;

@Component
public class AutoPurgingSituationMap extends AutoPurgingMap<SituationKey, SituationUpdate> {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPurgingSituationMap.class);

    public AutoPurgingSituationMap(
            @Value("${situation.updates.purge.interval:PT1M}") Duration purgeInterval,
            @Value("${situation.updates.expiry.grace.period:PT10M}") Duration gracePeriod) {
        super(purgeInterval, gracePeriod);
    }

    public void removeExpiredVehicles() {
        long before = System.currentTimeMillis();

        int sizeBefore = this.size();

        // A null expiration means the situation never expires - open-ended situations
        // are retained indefinitely so that producers who never close them can be found.
        final boolean entriesRemoved = this.entrySet().removeIf(entry -> {
            ZonedDateTime expiration = entry.getValue().getExpiration();
            return expiration != null
                    && expiration.plus(gracePeriod).isBefore(ZonedDateTime.now());
        });

        final long duration = System.currentTimeMillis() - before;

        if (entriesRemoved) {
            LOG.debug("Removed {} expired situations in {} ms, current size: {}",
                sizeBefore - this.size(),
                duration,
                this.size()
            );
        }

        if (duration > 20) {
            LOG.warn("Removing expired situations took {} ms", duration);
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=AutoPurgingSituationMapTest`
Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/SituationUpdate.java \
        src/main/java/org/entur/vehicles/repository/SituationKey.java \
        src/main/java/org/entur/vehicles/repository/AutoPurgingSituationMap.java \
        src/test/java/org/entur/vehicles/repository/AutoPurgingSituationMapTest.java
git commit -m "Adding SituationUpdate model and auto-purging situation storage"
```

---

### Task 6: SituationMapper

Translates `PtSituationElementRecord` into `SituationUpdate`, including enrichment and expiration computation. Kept separate from the repository so neither file grows unwieldy.

**Files:**
- Create: `src/main/java/org/entur/vehicles/repository/SituationMapper.java`
- Test: `src/test/java/org/entur/vehicles/repository/SituationMapperTest.java`

**Interfaces:**
- Consumes: `SituationUpdate`, `Affects`, `ValidityPeriod`, `TranslatedString`, `InfoLink`, both enums; `LineService.getLine(String) throws ExecutionException`, `OperatorService.getOperator(String)` (static), `NSRService.getStop(String)`.
- Produces: `SituationMapper(LineService, NSRService)` Spring component with `SituationUpdate map(PtSituationElementRecord record)`, returning `null` when no codespace can be resolved.

Codespace resolution: use `participantRef`; if absent, take the prefix of `situationNumber` before the first `:`. If neither yields a value, return `null` so the repository can log and skip.

Expiration rules, in order:
1. `progress` is `closed` → `ZonedDateTime.now()`
2. any validity period carries an `endTime` → the latest such `endTime`
3. otherwise → `null` (never expires)

The avro `vehicleMode` field carries `VehicleModeEnum` symbols (`BUS`, `RAIL`, …), which is exactly the spelling the existing `VehicleModeEnumeration.fromValue(String)` expects — it delegates to `VehicleModeEnum.valueOf`. That method throws `IllegalArgumentException` on an unrecognised symbol, which `resolveMode` below catches. Do not modify `VehicleModeEnumeration`; VM and ET already depend on it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/repository/SituationMapperTest.java`:

```java
package org.entur.vehicles.repository;

import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedOperatorRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPlaceRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.avro.realtime.siri.model.ValidityPeriodRecord;
import org.entur.vehicles.data.SeverityEnumeration;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationMapperTest {

    private SituationMapper mapper;

    @BeforeEach
    public void init() {
        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));
        mapper = new SituationMapper(new LineService(false), nsrService);
    }

    private PtSituationElementRecord minimalRecord() {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber("TST:SituationNumber:1");
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusHours(3).toString());
        record.setReportType("general");
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        return record;
    }

    @Test
    public void testMapsCoreFields() {
        PtSituationElementRecord record = minimalRecord();
        record.setVersion(3);
        record.setSeverity("VERY_SEVERE");
        record.setProgress("PUBLISHED");
        record.setPriority(1);
        record.setPlanned(false);
        record.setKeywords(List.of("snow"));

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation);
        assertEquals("TST:SituationNumber:1", situation.getSituationNumber());
        assertEquals("TST", situation.getCodespace().getCodespaceId());
        assertEquals(3, situation.getVersion());
        assertEquals(SeverityEnumeration.verySevere, situation.getSeverity());
        assertEquals(WorkflowStatusEnumeration.published, situation.getProgress());
        assertEquals("general", situation.getReportType());
        assertEquals(List.of("snow"), situation.getKeywords());
        assertEquals(false, situation.getPlanned());
    }

    @Test
    public void testDerivesCodespaceFromSituationNumberWhenParticipantRefIsMissing() {
        PtSituationElementRecord record = minimalRecord();
        record.setParticipantRef(null);

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation);
        assertEquals("TST", situation.getCodespace().getCodespaceId());
    }

    @Test
    public void testReturnsNullWhenNoCodespaceCanBeResolved() {
        PtSituationElementRecord record = minimalRecord();
        record.setParticipantRef(null);
        record.setSituationNumber("no-codespace-here");

        assertNull(mapper.map(record));
    }

    @Test
    public void testMapsTranslatedText() {
        PtSituationElementRecord record = minimalRecord();
        TranslatedStringRecord summary = new TranslatedStringRecord();
        summary.setValue("Buss for tog");
        summary.setLanguage("no");
        record.setSummaries(List.of(summary));

        SituationUpdate situation = mapper.map(record);

        assertEquals(1, situation.getSummary().size());
        assertEquals("Buss for tog", situation.getSummary().get(0).getValue());
        assertEquals("no", situation.getSummary().get(0).getLanguage());
    }

    @Test
    public void testFlattensAffects() {
        PtSituationElementRecord record = minimalRecord();

        AffectedLineRecord affectedLine = new AffectedLineRecord();
        affectedLine.setLineRef("TST:Line:1");

        AffectedOperatorRecord affectedOperator = new AffectedOperatorRecord();
        affectedOperator.setOperatorRef("TST:Operator:1");

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setVehicleMode("BUS");
        network.setAffectedLines(List.of(affectedLine));
        network.setAffectedOperators(List.of(affectedOperator));

        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef("TST:Quay:1");
        stopPoint.setStopPointNames(List.of());
        stopPoint.setStopConditions(List.of());

        AffectedStopPlaceRecord stopPlace = new AffectedStopPlaceRecord();
        stopPlace.setStopPlaceRef("TST:StopPlace:9");
        stopPlace.setPlaceNames(List.of());

        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setLineRef("TST:Line:1");
        journey.setVehicleJourneyRefs(List.of("TST:ServiceJourney:1"));
        journey.setDatedVehicleJourneyRefs(List.of("TST:DatedServiceJourney:1"));
        journey.setRoutes(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        affects.setStopPoints(List.of(stopPoint));
        affects.setStopPlaces(List.of(stopPlace));
        affects.setVehicleJourneys(List.of(journey));
        record.setAffects(affects);

        SituationUpdate situation = mapper.map(record);

        // The line is named by both the network and the vehicle journey - it must appear once.
        assertEquals(1, situation.getAffects().getLines().size());
        assertTrue(situation.getAffects().getLineRefs().contains("TST:Line:1"));
        assertTrue(situation.getAffects().getStopRefs().contains("TST:Quay:1"));
        assertTrue(situation.getAffects().getStopRefs().contains("TST:StopPlace:9"));
        assertTrue(situation.getAffects().getServiceJourneyIds().contains("TST:ServiceJourney:1"));
        assertTrue(situation.getAffects().getDatedServiceJourneyIds().contains("TST:DatedServiceJourney:1"));
        assertTrue(situation.getAffects().getOperatorRefs().contains("TST:Operator:1"));
        assertTrue(situation.getAffects().getVehicleModes().contains(VehicleModeEnumeration.BUS));
    }

    @Test
    public void testExpirationIsLatestValidityEndTime() {
        PtSituationElementRecord record = minimalRecord();
        ZonedDateTime latest = ZonedDateTime.now().plusDays(5);

        ValidityPeriodRecord first = new ValidityPeriodRecord();
        first.setStartTime(ZonedDateTime.now().minusDays(1).toString());
        first.setEndTime(ZonedDateTime.now().plusDays(1).toString());

        ValidityPeriodRecord second = new ValidityPeriodRecord();
        second.setStartTime(ZonedDateTime.now().minusDays(1).toString());
        second.setEndTime(latest.toString());

        record.setValidityPeriods(List.of(first, second));

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation.getExpiration());
        assertEquals(latest.toEpochSecond(), situation.getExpiration().toEpochSecond());
    }

    @Test
    public void testOpenEndedSituationHasNullExpiration() {
        PtSituationElementRecord record = minimalRecord();

        ValidityPeriodRecord openEnded = new ValidityPeriodRecord();
        openEnded.setStartTime(ZonedDateTime.now().minusDays(400).toString());
        openEnded.setEndTime(null);
        record.setValidityPeriods(List.of(openEnded));

        SituationUpdate situation = mapper.map(record);

        assertNull(situation.getExpiration());
        assertTrue(situation.getOpenEnded());
    }

    @Test
    public void testClosedSituationExpiresImmediately() {
        PtSituationElementRecord record = minimalRecord();
        record.setProgress("CLOSED");

        ValidityPeriodRecord openEnded = new ValidityPeriodRecord();
        openEnded.setStartTime(ZonedDateTime.now().minusDays(1).toString());
        openEnded.setEndTime(null);
        record.setValidityPeriods(List.of(openEnded));

        SituationUpdate situation = mapper.map(record);

        assertNotNull(situation.getExpiration());
        assertTrue(situation.getExpiration().isBefore(ZonedDateTime.now().plusSeconds(5)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SituationMapperTest`
Expected: FAIL — compilation error, `SituationMapper` does not exist.

- [ ] **Step 3: Implement SituationMapper**

Create `src/main/java/org/entur/vehicles/repository/SituationMapper.java`:

```java
package org.entur.vehicles.repository;

import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedOperatorRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPlaceRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.InfoLinkRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.avro.realtime.siri.model.ValidityPeriodRecord;
import org.entur.vehicles.data.SeverityEnumeration;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.InfoLink;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.TranslatedString;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.OperatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.entur.vehicles.repository.helpers.Util.containsValues;
import static org.entur.vehicles.repository.helpers.Util.convert;

/**
 * Converts an avro {@link PtSituationElementRecord} into a {@link SituationUpdate},
 * enriching lines, operators and stops through the existing lookup services.
 */
@Component
public class SituationMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SituationMapper.class);

    private final LineService lineService;
    private final NSRService nsrService;

    public SituationMapper(@Autowired LineService lineService,
                           @Autowired NSRService nsrService) {
        this.lineService = lineService;
        this.nsrService = nsrService;
    }

    /** Returns null when no codespace can be resolved - the caller should skip the record. */
    public SituationUpdate map(PtSituationElementRecord record) {
        String situationNumber = asString(record.getSituationNumber());
        String participantRef = asString(record.getParticipantRef());

        Codespace codespace = resolveCodespace(participantRef, situationNumber);
        if (codespace == null) {
            return null;
        }

        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber(situationNumber);
        situation.setParticipantRef(participantRef);
        situation.setCodespace(codespace);
        situation.setVersion(record.getVersion());
        situation.setPriority(record.getPriority());
        situation.setPlanned(record.getPlanned());
        situation.setReportType(asString(record.getReportType()));
        situation.setSeverity(SeverityEnumeration.fromValue(asString(record.getSeverity())));
        situation.setProgress(WorkflowStatusEnumeration.fromValue(asString(record.getProgress())));

        if (record.getSource() != null) {
            situation.setSourceType(asString(record.getSource().getSourceType()));
        }

        if (containsValues(record.getKeywords())) {
            List<String> keywords = new ArrayList<>();
            record.getKeywords().forEach(keyword -> keywords.add(keyword.toString()));
            situation.setKeywords(keywords);
        }

        if (record.getCreationTime() != null) {
            situation.setCreationTime(convert(record.getCreationTime()));
        }
        if (record.getVersionedAtTime() != null) {
            situation.setVersionedAtTime(convert(record.getVersionedAtTime()));
        }

        situation.setValidityPeriods(mapValidityPeriods(record.getValidityPeriods()));
        situation.setSummary(mapTranslations(record.getSummaries()));
        situation.setDescription(mapTranslations(record.getDescriptions()));
        situation.setAdvice(mapTranslations(record.getAdvices()));
        situation.setDetail(mapTranslations(record.getDetails()));
        situation.setInfoLinks(mapInfoLinks(record.getInfoLinks()));
        situation.setAffects(mapAffects(record.getAffects()));

        if (situation.getVersionedAtTime() != null) {
            situation.setLastUpdated(situation.getVersionedAtTime());
        } else if (situation.getCreationTime() != null) {
            situation.setLastUpdated(situation.getCreationTime());
        } else {
            situation.setLastUpdated(ZonedDateTime.now());
        }

        situation.setExpiration(calculateExpiration(situation));

        return situation;
    }

    private Codespace resolveCodespace(String participantRef, String situationNumber) {
        if (participantRef != null && !participantRef.isBlank()) {
            return Codespace.getCodespace(participantRef);
        }
        if (situationNumber != null && situationNumber.contains(":")) {
            return Codespace.getCodespace(situationNumber.substring(0, situationNumber.indexOf(':')));
        }
        LOG.debug("Unable to resolve codespace for situation {} - ignoring.", situationNumber);
        return null;
    }

    /**
     * A closed situation expires now, so it is published once and then purged after the
     * grace period. Otherwise the latest validity end time wins. When no period carries
     * an end time the situation is open-ended and never expires - null.
     */
    private ZonedDateTime calculateExpiration(SituationUpdate situation) {
        if (situation.getProgress() != null && situation.getProgress().isClosed()) {
            return ZonedDateTime.now();
        }

        ZonedDateTime latest = null;
        if (situation.getValidityPeriods() != null) {
            for (ValidityPeriod period : situation.getValidityPeriods()) {
                ZonedDateTime endTime = period.getEndTime();
                if (endTime != null && (latest == null || endTime.isAfter(latest))) {
                    latest = endTime;
                }
            }
        }
        return latest;
    }

    private List<ValidityPeriod> mapValidityPeriods(List<ValidityPeriodRecord> records) {
        List<ValidityPeriod> periods = new ArrayList<>();
        if (containsValues(records)) {
            for (ValidityPeriodRecord record : records) {
                periods.add(new ValidityPeriod(
                        record.getStartTime() != null ? convert(record.getStartTime()) : null,
                        record.getEndTime() != null ? convert(record.getEndTime()) : null
                ));
            }
        }
        return periods;
    }

    private List<TranslatedString> mapTranslations(List<TranslatedStringRecord> records) {
        List<TranslatedString> translations = new ArrayList<>();
        if (containsValues(records)) {
            for (TranslatedStringRecord record : records) {
                translations.add(new TranslatedString(
                        asString(record.getValue()),
                        asString(record.getLanguage())
                ));
            }
        }
        return translations;
    }

    private List<InfoLink> mapInfoLinks(List<InfoLinkRecord> records) {
        List<InfoLink> links = new ArrayList<>();
        if (containsValues(records)) {
            for (InfoLinkRecord record : records) {
                links.add(new InfoLink(asString(record.getUri()), mapTranslations(record.getLabels())));
            }
        }
        return links;
    }

    private Affects mapAffects(AffectsRecord record) {
        Affects affects = new Affects();
        if (record == null) {
            return affects;
        }

        if (containsValues(record.getNetworks())) {
            for (AffectedNetworkRecord network : record.getNetworks()) {
                affects.addVehicleMode(resolveMode(asString(network.getVehicleMode())));

                if (containsValues(network.getAffectedLines())) {
                    for (AffectedLineRecord affectedLine : network.getAffectedLines()) {
                        affects.addLine(resolveLine(asString(affectedLine.getLineRef())));
                    }
                }
                if (containsValues(network.getAffectedOperators())) {
                    for (AffectedOperatorRecord affectedOperator : network.getAffectedOperators()) {
                        affects.addOperator(resolveOperator(asString(affectedOperator.getOperatorRef())));
                    }
                }
            }
        }

        if (containsValues(record.getStopPoints())) {
            for (AffectedStopPointRecord stopPoint : record.getStopPoints()) {
                StopPoint stop = resolveStop(asString(stopPoint.getStopPointRef()));
                if (stop != null && containsValues(stopPoint.getStopPointNames())) {
                    stop.setName(asString(stopPoint.getStopPointNames().get(0).getValue()));
                }
                affects.addStopPoint(stop);
            }
        }

        if (containsValues(record.getStopPlaces())) {
            for (AffectedStopPlaceRecord stopPlace : record.getStopPlaces()) {
                StopPoint stop = resolveStop(asString(stopPlace.getStopPlaceRef()));
                if (stop != null && containsValues(stopPlace.getPlaceNames())) {
                    stop.setName(asString(stopPlace.getPlaceNames().get(0).getValue()));
                }
                affects.addStopPlace(stop);
            }
        }

        if (containsValues(record.getVehicleJourneys())) {
            for (AffectedVehicleJourneyRecord journey : record.getVehicleJourneys()) {
                affects.addLine(resolveLine(asString(journey.getLineRef())));

                if (journey.getOperator() != null) {
                    affects.addOperator(resolveOperator(asString(journey.getOperator().getOperatorRef())));
                }
                if (containsValues(journey.getVehicleJourneyRefs())) {
                    journey.getVehicleJourneyRefs().forEach(ref ->
                            affects.addServiceJourney(new ServiceJourney(ref.toString())));
                }
                if (journey.getFramedVehicleJourneyRef() != null
                        && journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef() != null) {
                    affects.addServiceJourney(new ServiceJourney(
                            journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef().toString(),
                            asString(journey.getFramedVehicleJourneyRef().getDataFrameRef())));
                }
                if (containsValues(journey.getDatedVehicleJourneyRefs())) {
                    journey.getDatedVehicleJourneyRefs().forEach(ref ->
                            affects.addDatedServiceJourney(new DatedServiceJourney(ref.toString())));
                }
            }
        }

        return affects;
    }

    private Line resolveLine(String lineRef) {
        if (lineRef == null) {
            return null;
        }
        try {
            return lineService.getLine(lineRef);
        } catch (ExecutionException e) {
            return new Line(lineRef);
        }
    }

    private org.entur.vehicles.data.model.Operator resolveOperator(String operatorRef) {
        if (operatorRef == null) {
            return null;
        }
        return OperatorService.getOperator(operatorRef);
    }

    private StopPoint resolveStop(String stopRef) {
        if (stopRef == null) {
            return null;
        }
        return nsrService.getStop(stopRef);
    }

    private VehicleModeEnumeration resolveMode(String vehicleMode) {
        if (vehicleMode == null) {
            return null;
        }
        try {
            return VehicleModeEnumeration.fromValue(vehicleMode);
        } catch (IllegalArgumentException e) {
            LOG.debug("Unknown vehicle mode {} - ignoring.", vehicleMode);
            return null;
        }
    }

    private String asString(CharSequence value) {
        return value != null ? value.toString() : null;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SituationMapperTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/SituationMapper.java \
        src/test/java/org/entur/vehicles/repository/SituationMapperTest.java
git commit -m "Adding SituationMapper for avro to domain conversion"
```

---

### Task 7: SituationFilter

`QueryFilter` is untouched. SX matching tests membership in collections rather than equality against single values, and `QueryFilter` already carries a 16-argument constructor and two near-duplicate `isMatch` methods.

`SituationFilter` declares its own `bufferSize`/`bufferTimeMillis`. Sharing a base class with `QueryFilter` is impossible without restructuring the update hierarchy, since `QueryFilter extends AbstractUpdate` and Java has single inheritance.

**Files:**
- Create: `src/main/java/org/entur/vehicles/data/SituationFilter.java`
- Test: `src/test/java/org/entur/vehicles/data/SituationFilterTest.java`

**Interfaces:**
- Consumes: `SituationUpdate`, `Affects`, `SeverityEnumeration`, `WorkflowStatusEnumeration`, `VehicleModeEnumeration`.
- Produces: `SituationFilter` with this constructor and `boolean isMatch(SituationUpdate)`, `int getBufferSize()`, `int getBufferTimeMillis()`:

```java
public SituationFilter(PrometheusMetricsService metricsService,
                       MetricType metricType,
                       Set<String> situationNumbers,
                       String codespaceId,
                       String operatorRef,
                       String lineRef,
                       String stopRef,
                       String serviceJourneyId,
                       String datedServiceJourneyId,
                       VehicleModeEnumeration mode,
                       SeverityEnumeration severity,
                       String reportType,
                       Boolean validNow,
                       Boolean openEnded,
                       Duration minAge,
                       Boolean includeClosed,
                       Integer bufferSize,
                       Integer bufferTimeMillis)
```

Every criterion is skipped when null. `includeClosed` is treated as false when null. Matching short-circuits on the first failure, following `QueryFilter`'s style.

The metrics call on a successful match is `metricsService.markFilterMatch(Codespace, MetricType)` — confirmed at `PrometheusMetricsService.java:171`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/data/SituationFilterTest.java`:

```java
package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationFilterTest {

    private SituationUpdate situation() {
        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber("TST:SituationNumber:1");
        situation.setCodespace(Codespace.getCodespace("TST"));
        situation.setSeverity(SeverityEnumeration.severe);
        situation.setProgress(WorkflowStatusEnumeration.published);
        situation.setReportType("general");
        situation.setCreationTime(ZonedDateTime.now().minusDays(40));
        situation.setLastUpdated(ZonedDateTime.now());
        situation.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(1))));

        Affects affects = new Affects();
        affects.addLine(new Line("TST:Line:1"));
        affects.addStopPoint(new StopPoint("TST:Quay:1"));
        affects.addServiceJourney(new ServiceJourney("TST:ServiceJourney:1"));
        affects.addOperator(new Operator("TST:Operator:1"));
        affects.addVehicleMode(VehicleModeEnumeration.BUS);
        situation.setAffects(affects);

        return situation;
    }

    private SituationFilter filter(String codespaceId, String operatorRef, String lineRef, String stopRef,
                                   String serviceJourneyId, VehicleModeEnumeration mode,
                                   SeverityEnumeration severity, Boolean validNow, Boolean openEnded,
                                   Duration minAge, Boolean includeClosed) {
        return new SituationFilter(null, MetricType.QUERY, null, codespaceId, operatorRef, lineRef, stopRef,
                serviceJourneyId, null, mode, severity, null, validNow, openEnded, minAge, includeClosed,
                null, null);
    }

    @Test
    public void testEmptyFilterMatchesEverything() {
        assertTrue(filter(null, null, null, null, null, null, null, null, null, null, null)
                .isMatch(situation()));
    }

    @Test
    public void testMatchesOnAffectedObjects() {
        SituationUpdate situation = situation();
        assertTrue(filter("TST", null, null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, "TST:Operator:1", null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, "TST:Line:1", null, null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, "TST:Quay:1", null, null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, "TST:ServiceJourney:1", null, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, null, VehicleModeEnumeration.BUS, null, null, null, null, null).isMatch(situation));
        assertTrue(filter(null, null, null, null, null, null, SeverityEnumeration.severe, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testRejectsNonMatchingAffectedObjects() {
        SituationUpdate situation = situation();
        assertFalse(filter("ABC", null, null, null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, "TST:Line:999", null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, "TST:Quay:999", null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, null, null, VehicleModeEnumeration.RAIL, null, null, null, null, null).isMatch(situation));
        assertFalse(filter(null, null, null, null, null, null, SeverityEnumeration.slight, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testCombinedCriteriaMustAllMatch() {
        SituationUpdate situation = situation();
        assertTrue(filter("TST", null, "TST:Line:1", null, null, null, null, null, null, null, null).isMatch(situation));
        assertFalse(filter("TST", null, "TST:Line:999", null, null, null, null, null, null, null, null).isMatch(situation));
    }

    @Test
    public void testValidNow() {
        SituationUpdate current = situation();
        assertTrue(filter(null, null, null, null, null, null, null, true, null, null, null).isMatch(current));

        SituationUpdate future = situation();
        future.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().plusDays(1), ZonedDateTime.now().plusDays(2))));
        assertFalse(filter(null, null, null, null, null, null, null, true, null, null, null).isMatch(future));
    }

    @Test
    public void testOpenEnded() {
        SituationUpdate bounded = situation();
        assertFalse(filter(null, null, null, null, null, null, null, null, true, null, null).isMatch(bounded));

        SituationUpdate openEnded = situation();
        openEnded.setValidityPeriods(List.of(
                new ValidityPeriod(ZonedDateTime.now().minusDays(400), null)));
        assertTrue(filter(null, null, null, null, null, null, null, null, true, null, null).isMatch(openEnded));
    }

    @Test
    public void testMinAge() {
        SituationUpdate old = situation();
        assertTrue(filter(null, null, null, null, null, null, null, null, null, Duration.ofDays(30), null).isMatch(old));

        SituationUpdate fresh = situation();
        fresh.setCreationTime(ZonedDateTime.now().minusDays(1));
        assertFalse(filter(null, null, null, null, null, null, null, null, null, Duration.ofDays(30), null).isMatch(fresh));
    }

    @Test
    public void testClosedSituationsAreExcludedByDefault() {
        SituationUpdate closed = situation();
        closed.setProgress(WorkflowStatusEnumeration.closed);

        assertFalse(filter(null, null, null, null, null, null, null, null, null, null, null).isMatch(closed));
        assertFalse(filter(null, null, null, null, null, null, null, null, null, null, false).isMatch(closed));
        assertTrue(filter(null, null, null, null, null, null, null, null, null, null, true).isMatch(closed));
    }

    @Test
    public void testSituationNumbers() {
        SituationFilter byNumber = new SituationFilter(null, MetricType.QUERY,
                Set.of("TST:SituationNumber:1"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertTrue(byNumber.isMatch(situation()));

        SituationFilter byOtherNumber = new SituationFilter(null, MetricType.QUERY,
                Set.of("TST:SituationNumber:999"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertFalse(byOtherNumber.isMatch(situation()));
    }

    @Test
    public void testBufferDefaults() {
        SituationFilter defaults = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(defaults.getBufferSize() > 0);
        assertTrue(defaults.getBufferTimeMillis() > 0);

        SituationFilter explicit = new SituationFilter(null, MetricType.SUBSCRIPTION, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 5, 100);
        assertTrue(explicit.getBufferSize() == 5);
        assertTrue(explicit.getBufferTimeMillis() == 100);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SituationFilterTest`
Expected: FAIL — compilation error, `SituationFilter` does not exist.

- [ ] **Step 3: Implement SituationFilter**

Create `src/main/java/org/entur/vehicles/data/SituationFilter.java`:

```java
package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.StringJoiner;

import static org.entur.vehicles.data.MetricType.UNDEFINED;

/**
 * Filter for situations.
 * <p>
 * Kept separate from {@code QueryFilter} because SX matching tests membership in
 * collections of affected objects rather than equality against single values.
 * Buffer settings are duplicated rather than shared: {@code QueryFilter} extends
 * {@code AbstractUpdate}, so a common base class is not possible without
 * restructuring the update hierarchy.
 */
@SchemaMapping
public class SituationFilter {

    private static final int DEFAULT_BUFFER_SIZE = 20;
    private static final int DEFAULT_BUFFER_TIME_MILLIS = 250;

    private final PrometheusMetricsService metricsService;
    private MetricType metricType = UNDEFINED;

    private final Set<String> situationNumbers;
    private final Codespace codespace;
    private final String operatorRef;
    private final String lineRef;
    private final String stopRef;
    private final String serviceJourneyId;
    private final String datedServiceJourneyId;
    private final VehicleModeEnumeration mode;
    private final SeverityEnumeration severity;
    private final String reportType;
    private final Boolean validNow;
    private final Boolean openEnded;
    private final ZonedDateTime maxCreationTime;
    private final boolean includeClosed;

    private int bufferSize = DEFAULT_BUFFER_SIZE;
    private int bufferTimeMillis = DEFAULT_BUFFER_TIME_MILLIS;

    public SituationFilter(PrometheusMetricsService metricsService,
                           MetricType metricType,
                           Set<String> situationNumbers,
                           String codespaceId,
                           String operatorRef,
                           String lineRef,
                           String stopRef,
                           String serviceJourneyId,
                           String datedServiceJourneyId,
                           VehicleModeEnumeration mode,
                           SeverityEnumeration severity,
                           String reportType,
                           Boolean validNow,
                           Boolean openEnded,
                           Duration minAge,
                           Boolean includeClosed,
                           Integer bufferSize,
                           Integer bufferTimeMillis) {
        this.metricsService = metricsService;
        if (metricType != null) {
            this.metricType = metricType;
        }
        this.situationNumbers = situationNumbers;
        this.codespace = codespaceId != null ? Codespace.getCodespace(codespaceId) : null;
        this.operatorRef = operatorRef;
        this.lineRef = lineRef;
        this.stopRef = stopRef;
        this.serviceJourneyId = serviceJourneyId;
        this.datedServiceJourneyId = datedServiceJourneyId;
        this.mode = mode;
        this.severity = severity;
        this.reportType = reportType;
        this.validNow = validNow;
        this.openEnded = openEnded;
        this.maxCreationTime = minAge != null ? ZonedDateTime.now().minus(minAge) : null;
        this.includeClosed = includeClosed != null && includeClosed;

        if (bufferSize != null) {
            this.bufferSize = bufferSize;
        }
        if (bufferTimeMillis != null) {
            this.bufferTimeMillis = bufferTimeMillis;
        }
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferTimeMillis() {
        return bufferTimeMillis;
    }

    public boolean isMatch(SituationUpdate situation) {

        if (!includeClosed && situation.getProgress() != null && situation.getProgress().isClosed()) {
            return false;
        }
        if (situationNumbers != null && !situationNumbers.contains(situation.getSituationNumber())) {
            return false;
        }
        if (codespace != null && !codespace.equals(situation.getCodespace())) {
            return false;
        }
        if (severity != null && severity != situation.getSeverity()) {
            return false;
        }
        if (reportType != null && !reportType.equals(situation.getReportType())) {
            return false;
        }

        Affects affects = situation.getAffects();
        if (operatorRef != null && (affects == null || !affects.getOperatorRefs().contains(operatorRef))) {
            return false;
        }
        if (lineRef != null && (affects == null || !affects.getLineRefs().contains(lineRef))) {
            return false;
        }
        if (stopRef != null && (affects == null || !affects.getStopRefs().contains(stopRef))) {
            return false;
        }
        if (serviceJourneyId != null && (affects == null || !affects.getServiceJourneyIds().contains(serviceJourneyId))) {
            return false;
        }
        if (datedServiceJourneyId != null
                && (affects == null || !affects.getDatedServiceJourneyIds().contains(datedServiceJourneyId))) {
            return false;
        }
        if (mode != null && (affects == null || !affects.getVehicleModes().contains(mode))) {
            return false;
        }

        if (validNow != null && validNow != situation.isValidAt(ZonedDateTime.now())) {
            return false;
        }
        if (openEnded != null && !openEnded.equals(situation.getOpenEnded())) {
            return false;
        }
        if (maxCreationTime != null
                && (situation.getCreationTime() == null || situation.getCreationTime().isAfter(maxCreationTime))) {
            return false;
        }

        if (metricsService != null) {
            metricsService.markFilterMatch(situation.getCodespace(), metricType);
        }
        return true;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", SituationFilter.class.getSimpleName() + "[", "]")
                .add("situationNumbers=" + situationNumbers)
                .add("codespace=" + codespace)
                .add("operatorRef='" + operatorRef + "'")
                .add("lineRef='" + lineRef + "'")
                .add("stopRef='" + stopRef + "'")
                .add("serviceJourneyId='" + serviceJourneyId + "'")
                .add("mode=" + mode)
                .add("severity=" + severity)
                .add("validNow=" + validNow)
                .add("openEnded=" + openEnded)
                .add("includeClosed=" + includeClosed)
                .toString();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SituationFilterTest`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/SituationFilter.java \
        src/test/java/org/entur/vehicles/data/SituationFilterTest.java
git commit -m "Adding SituationFilter"
```

---

### Task 8: SituationRepository

Storage, the version guard, publishing and the update metric. Mapping lives in `SituationMapper` (Task 6).

**Files:**
- Create: `src/main/java/org/entur/vehicles/repository/SituationRepository.java`
- Create: `src/main/java/org/entur/vehicles/graphql/publishers/SituationUpdateRxPublisher.java`
- Modify: `src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java`
- Test: `src/test/java/org/entur/vehicles/repository/SituationRepositoryTest.java`

**Interfaces:**
- Consumes: `SituationMapper.map(PtSituationElementRecord)`, `AutoPurgingSituationMap`, `SituationKey`, `SituationFilter.isMatch(SituationUpdate)`, `PrometheusMetricsService`.
- Produces:
  - `SituationRepository(PrometheusMetricsService, SituationMapper, AutoPurgingSituationMap, SituationUpdateRxPublisher)` with `add(PtSituationElementRecord)`, `addAll(List<PtSituationElementRecord>)`, `Collection<SituationUpdate> getSituations(SituationFilter filter)` (a null filter returns everything).
  - `SituationUpdateRxPublisher` with `setRepository(SituationRepository)`, `publishUpdate(SituationUpdate)`, `Flux<List<SituationUpdate>> getPublisher(SituationFilter, String uuid)`, `int currentSubscribers()`.
  - `PrometheusMetricsService.markSituationUpdate(int count, Codespace codespace)`.

The publisher is created here because the repository constructor wires itself into it, exactly as `TimetableRepository` does with `EstimatedTimetableUpdateRxPublisher`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/repository/SituationRepositoryTest.java`:

```java
package org.entur.vehicles.repository;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.data.MetricType;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationRepositoryTest {

    private SituationRepository repository;

    @BeforeEach
    public void init() {
        PrometheusMetricsService metricsService =
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));

        repository = new SituationRepository(
                metricsService,
                new SituationMapper(new LineService(false), nsrService),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new SituationUpdateRxPublisher()
        );
    }

    private PtSituationElementRecord record(String situationNumber, Integer version, String progress) {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().toString());
        record.setReportType("general");
        record.setVersion(version);
        record.setProgress(progress);
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        return record;
    }

    private SituationFilter allSituations() {
        return new SituationFilter(null, MetricType.QUERY, null, null, null, null, null, null, null,
                null, null, null, null, null, null, true, null, null);
    }

    @Test
    public void testAddAndRetrieve() {
        repository.addAll(List.of(record("TST:SituationNumber:1", 1, "PUBLISHED")));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals("TST:SituationNumber:1", situations.iterator().next().getSituationNumber());
    }

    @Test
    public void testSameSituationNumberReplacesPreviousVersion() {
        repository.add(record("TST:SituationNumber:1", 1, "PUBLISHED"));
        repository.add(record("TST:SituationNumber:1", 2, "PUBLISHED"));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals(2, situations.iterator().next().getVersion());
    }

    @Test
    public void testOlderVersionIsIgnored() {
        repository.add(record("TST:SituationNumber:1", 5, "PUBLISHED"));
        repository.add(record("TST:SituationNumber:1", 2, "PUBLISHED"));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals(5, situations.iterator().next().getVersion());
    }

    @Test
    public void testNullVersionIsAlwaysAccepted() {
        repository.add(record("TST:SituationNumber:1", 5, "PUBLISHED"));
        repository.add(record("TST:SituationNumber:1", null, "CLOSED"));

        Collection<SituationUpdate> situations = repository.getSituations(null);
        assertEquals(1, situations.size());
        assertEquals(WorkflowStatusEnumeration.closed, situations.iterator().next().getProgress());
    }

    @Test
    public void testUnmappableRecordIsIgnoredWithoutThrowing() {
        PtSituationElementRecord broken = record("no-codespace-here", 1, "PUBLISHED");
        broken.setParticipantRef(null);

        repository.add(broken);

        assertTrue(repository.getSituations(null).isEmpty());
    }

    @Test
    public void testFilterIsApplied() {
        repository.addAll(List.of(
                record("TST:SituationNumber:1", 1, "PUBLISHED"),
                record("TST:SituationNumber:2", 1, "CLOSED")));

        assertEquals(2, repository.getSituations(allSituations()).size());

        SituationFilter excludingClosed = new SituationFilter(null, MetricType.QUERY, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false, null, null);
        assertEquals(1, repository.getSituations(excludingClosed).size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SituationRepositoryTest`
Expected: FAIL — compilation error, `SituationRepository` does not exist.

- [ ] **Step 3: Add the update metric**

In `PrometheusMetricsService.java`, add the counter name next to `TIMETABLE_DATA_COUNTER_NAME` (around line 43):

```java
    private static final String SITUATION_DATA_COUNTER_NAME = METRICS_PREFIX + "situation.data";
```

Add the counter fields next to the timetable ones (around line 64):

```java
    private final AtomicInteger situationCounter = new AtomicInteger(0);
    private final AtomicInteger lastLoggedSituationCount = new AtomicInteger(0);
    private final AtomicLong lastLoggedSituationCountTimeMillis = new AtomicLong(System.currentTimeMillis());
```

Add the method after `markTimetableUpdate`:

```java
    public void markSituationUpdate(int count, Codespace codespace) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(CODESPACE_TAG_NAME, codespace.getCodespaceId()));

        prometheusMeterRegistry.counter(SITUATION_DATA_COUNTER_NAME, counterTags).increment(count);
        if (situationCounter.addAndGet(count) % 1000 == 0) {
            final int currentCount = situationCounter.get();

            LOG.debug("Processed {} situation-updates. Current rate: {}/s", currentCount,
                calculateRate(currentCount, lastLoggedSituationCount, lastLoggedSituationCountTimeMillis));
        }
    }
```

- [ ] **Step 4: Implement the publisher**

Create `src/main/java/org/entur/vehicles/graphql/publishers/SituationUpdateRxPublisher.java`, mirroring `EstimatedTimetableUpdateRxPublisher`:

```java
package org.entur.vehicles.graphql.publishers;

import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.repository.SituationRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class SituationUpdateRxPublisher {

    private final Sinks.Many<SituationUpdate> sink = Sinks.many().multicast().directBestEffort();
    private SituationRepository repository;

    public void setRepository(SituationRepository repository) {
        this.repository = repository;
    }

    public void publishUpdate(SituationUpdate situationUpdate) {
        sink.tryEmitNext(situationUpdate);
    }

    public Flux<List<SituationUpdate>> getPublisher(SituationFilter template, String uuid) {
        List<SituationUpdate> initialdata = new ArrayList<>();
        if (repository != null) {
            initialdata.addAll(repository.getSituations(template));
        }

        return sink.asFlux()
                .startWith(initialdata)
                .filter(situationUpdate -> template == null || template.isMatch(situationUpdate))
                .bufferTimeout(template.getBufferSize(), Duration.of(template.getBufferTimeMillis(), ChronoUnit.MILLIS))
                .onBackpressureDrop();
    }

    public int currentSubscribers() {
        return sink.currentSubscriberCount();
    }
}
```

Note this passes `template` to `getSituations` rather than `null`, so the initial snapshot is filtered too. `EstimatedTimetableUpdateRxPublisher` passes `null` and filters afterwards; filtering up front avoids materialising the whole map per subscriber.

- [ ] **Step 5: Implement the repository**

Create `src/main/java/org/entur/vehicles/repository/SituationRepository.java`:

```java
package org.entur.vehicles.repository;

import com.google.common.collect.Maps;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.data.SituationFilter;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
public class SituationRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SituationRepository.class);

    private final PrometheusMetricsService metricsService;
    private final SituationMapper mapper;
    private final AutoPurgingSituationMap situationMap;
    private final SituationUpdateRxPublisher publisher;

    public SituationRepository(@Autowired PrometheusMetricsService metricsService,
                               @Autowired SituationMapper mapper,
                               @Autowired AutoPurgingSituationMap situationMap,
                               @Autowired SituationUpdateRxPublisher publisher) {
        this.metricsService = metricsService;
        this.mapper = mapper;
        this.situationMap = situationMap;
        this.publisher = publisher;
        this.publisher.setRepository(this);
    }

    public void addAll(List<PtSituationElementRecord> records) {
        for (PtSituationElementRecord record : records) {
            add(record);
        }
    }

    public void add(PtSituationElementRecord record) {
        try {
            SituationUpdate situation = mapper.map(record);
            if (situation == null) {
                return;
            }

            SituationKey key = new SituationKey(situation.getCodespace(), situation.getSituationNumber());

            if (isSupersededByStoredVersion(key, situation)) {
                LOG.debug("Ignoring out-of-order update for {} - version {} is older than the stored one.",
                        situation.getSituationNumber(), situation.getVersion());
                return;
            }

            situationMap.put(key, situation);
            publisher.publishUpdate(situation);

            metricsService.markSituationUpdate(1, situation.getCodespace());
        } catch (RuntimeException e) {
            LOG.warn("Update ignored.", e);
        }
    }

    /**
     * Pub/Sub gives no ordering guarantee, so a redelivered message can carry an older
     * version of a situation that is already stored.
     */
    private boolean isSupersededByStoredVersion(SituationKey key, SituationUpdate incoming) {
        SituationUpdate stored = situationMap.get(key);
        if (stored == null || stored.getVersion() == null || incoming.getVersion() == null) {
            return false;
        }
        return incoming.getVersion() < stored.getVersion();
    }

    public Collection<SituationUpdate> getSituations(SituationFilter filter) {
        if (filter != null) {
            final long filteringStart = System.currentTimeMillis();

            final Map<SituationKey, SituationUpdate> situations =
                    Maps.filterValues(situationMap, filter::isMatch);

            final long filteringDone = System.currentTimeMillis();
            if (filteringDone - filteringStart > 50) {
                LOG.info("Filtering situations took {} ms", (filteringDone - filteringStart));
            }
            return situations.values();
        }

        return situationMap.values();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=SituationRepositoryTest`
Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/SituationRepository.java \
        src/main/java/org/entur/vehicles/graphql/publishers/SituationUpdateRxPublisher.java \
        src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java \
        src/test/java/org/entur/vehicles/repository/SituationRepositoryTest.java
git commit -m "Adding SituationRepository with version guard and publishing"
```

---

### Task 9: GraphQL schema and wiring

Exposes the feed. Adding a constructor parameter to `Query` breaks both existing test classes, which construct it with three arguments — they are updated in this task.

**Files:**
- Modify: `src/main/resources/graphql/vehicle-updates.graphqls`
- Modify: `src/main/java/org/entur/vehicles/graphql/Query.java`
- Modify: `src/main/java/org/entur/vehicles/graphql/Subscription.java`
- Modify: `src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java`
- Modify: `src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java:53`
- Modify: `src/test/java/org/entur/vehicles/graphql/TimetableGraphQLTests.java:54`
- Test: `src/test/java/org/entur/vehicles/graphql/SituationGraphQLTests.java`

**Interfaces:**
- Consumes: `SituationRepository.getSituations(SituationFilter)`, `SituationUpdateRxPublisher.getPublisher(SituationFilter, String)`, `SituationFilter`'s 18-argument constructor from Task 7.
- Produces: `Query.getSituations(...)` returning `Collection<SituationUpdate>`; `Subscription.situations(...)` returning `Publisher<List<SituationUpdate>>`; `PrometheusMetricsService.markSituationsQuery()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/graphql/SituationGraphQLTests.java`:

```java
package org.entur.vehicles.graphql;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.avro.realtime.siri.model.ValidityPeriodRecord;
import org.entur.vehicles.data.SeverityEnumeration;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingSituationMap;
import org.entur.vehicles.repository.SituationMapper;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationGraphQLTests {

    private Query queryService;

    @BeforeEach
    public void initData() {
        PrometheusMetricsService metricsService =
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));

        SituationRepository repository = new SituationRepository(
                metricsService,
                new SituationMapper(new LineService(false), nsrService),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new SituationUpdateRxPublisher()
        );

        repository.addAll(List.of(
                busSituation(),
                openEndedSituation(),
                closedSituation()
        ));

        queryService = new Query(null, null, repository, metricsService);
    }

    private PtSituationElementRecord baseRecord(String situationNumber) {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusDays(60).toString());
        record.setReportType("general");
        record.setVersion(1);
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        return record;
    }

    private PtSituationElementRecord busSituation() {
        PtSituationElementRecord record = baseRecord("TST:SituationNumber:bus");
        record.setProgress("PUBLISHED");
        record.setSeverity("SEVERE");

        TranslatedStringRecord summary = new TranslatedStringRecord();
        summary.setValue("Forsinkelser");
        summary.setLanguage("no");
        record.setSummaries(List.of(summary));

        ValidityPeriodRecord period = new ValidityPeriodRecord();
        period.setStartTime(ZonedDateTime.now().minusHours(1).toString());
        period.setEndTime(ZonedDateTime.now().plusHours(1).toString());
        record.setValidityPeriods(List.of(period));

        AffectedLineRecord line = new AffectedLineRecord();
        line.setLineRef("TST:Line:1");

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setVehicleMode("BUS");
        network.setAffectedLines(List.of(line));

        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef("TST:Quay:1");
        stopPoint.setStopPointNames(List.of());
        stopPoint.setStopConditions(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        affects.setStopPoints(List.of(stopPoint));
        record.setAffects(affects);

        return record;
    }

    private PtSituationElementRecord openEndedSituation() {
        PtSituationElementRecord record = baseRecord("TST:SituationNumber:openended");
        record.setProgress("OPEN");
        record.setSeverity("NORMAL");

        ValidityPeriodRecord period = new ValidityPeriodRecord();
        period.setStartTime(ZonedDateTime.now().minusDays(60).toString());
        period.setEndTime(null);
        record.setValidityPeriods(List.of(period));

        return record;
    }

    private PtSituationElementRecord closedSituation() {
        PtSituationElementRecord record = baseRecord("TST:SituationNumber:closed");
        record.setProgress("CLOSED");
        record.setSeverity("SLIGHT");
        return record;
    }

    @Test
    public void testUnfilteredQueryExcludesClosedSituations() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(2, situations.size());
        assertTrue(situations.stream().noneMatch(s -> s.getSituationNumber().endsWith(":closed")));
    }

    @Test
    public void testIncludeClosed() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, null, null, null, true);

        assertEquals(3, situations.size());
    }

    @Test
    public void testFilterByCodespace() {
        assertEquals(2, queryService.getSituations(
                null, "TST", null, null, null, null, null, null, null, null, null, null, null, null).size());
        assertEquals(0, queryService.getSituations(
                null, "ABC", null, null, null, null, null, null, null, null, null, null, null, null).size());
    }

    @Test
    public void testFilterByLineStopAndMode() {
        assertEquals(1, queryService.getSituations(
                null, null, null, "TST:Line:1", null, null, null, null, null, null, null, null, null, null).size());
        assertEquals(1, queryService.getSituations(
                null, null, null, null, "TST:Quay:1", null, null, null, null, null, null, null, null, null).size());
        assertEquals(1, queryService.getSituations(
                null, null, null, null, null, null, null, VehicleModeEnumeration.BUS, null, null, null, null, null, null).size());
    }

    @Test
    public void testFilterBySeverity() {
        assertEquals(1, queryService.getSituations(
                null, null, null, null, null, null, null, null, SeverityEnumeration.severe, null, null, null, null, null).size());
        assertEquals(0, queryService.getSituations(
                null, null, null, null, null, null, null, null, SeverityEnumeration.verySevere, null, null, null, null, null).size());
    }

    @Test
    public void testFilterBySituationNumbers() {
        assertEquals(1, queryService.getSituations(
                Set.of("TST:SituationNumber:bus"), null, null, null, null, null, null, null, null, null,
                null, null, null, null).size());
    }

    @Test
    public void testQualityToolingQueryFindsLongLivedOpenEndedSituations() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, null, true,
                Duration.ofDays(30), null);

        assertEquals(1, situations.size());
        assertEquals("TST:SituationNumber:openended", situations.iterator().next().getSituationNumber());
        assertTrue(situations.iterator().next().getOpenEnded());
    }

    @Test
    public void testValidNow() {
        Collection<SituationUpdate> situations = queryService.getSituations(
                null, null, null, null, null, null, null, null, null, null, true, null, null, null);

        assertEquals(2, situations.size());
    }
}
```

The argument order of `getSituations` is fixed by Step 3 below: `situationNumbers, codespaceId, operatorRef, lineRef, stopRef, serviceJourneyId, datedServiceJourneyId, mode, severity, reportType, validNow, openEnded, minAge, includeClosed`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SituationGraphQLTests`
Expected: FAIL — compilation error, `Query` has no four-argument constructor and no `getSituations` method.

- [ ] **Step 3: Add the query resolver**

In `Query.java`, add the field and constructor parameter:

```java
    private final SituationRepository situationRepository;

    public Query(VehicleRepository vehicleRepository,
                 TimetableRepository timetableRepository,
                 SituationRepository situationRepository,
                 PrometheusMetricsService metricsService) {
        this.vehicleRepository = vehicleRepository;
        this.timetableRepository = timetableRepository;
        this.situationRepository = situationRepository;
        this.metricsService = metricsService;
    }
```

Add the resolver method:

```java
    @QueryMapping(name = "situations")
    Collection<SituationUpdate> getSituations(@Argument Set<String> situationNumbers,
                                              @Argument String codespaceId,
                                              @Argument String operatorRef,
                                              @Argument String lineRef,
                                              @Argument String stopRef,
                                              @Argument String serviceJourneyId,
                                              @Argument String datedServiceJourneyId,
                                              @Argument VehicleModeEnumeration mode,
                                              @Argument SeverityEnumeration severity,
                                              @Argument String reportType,
                                              @Argument Boolean validNow,
                                              @Argument Boolean openEnded,
                                              @Argument Duration minAge,
                                              @Argument Boolean includeClosed) {

        final SituationFilter filter = new SituationFilter(
                metricsService,
                MetricType.QUERY,
                situationNumbers,
                codespaceId,
                operatorRef,
                lineRef,
                stopRef,
                serviceJourneyId,
                datedServiceJourneyId,
                mode,
                severity,
                reportType,
                validNow,
                openEnded,
                minAge,
                includeClosed,
                null,
                null
        );
        LOG.debug("Requesting situations with filter: {}", filter);
        final long start = System.currentTimeMillis();
        final Collection<SituationUpdate> situations = situationRepository.getSituations(filter);
        LOG.debug("Returning {} situations in {} ms", situations.size(), System.currentTimeMillis() - start);

        metricsService.markSituationsQuery();
        return situations;
    }
```

Add the imports: `org.entur.vehicles.data.SituationFilter`, `org.entur.vehicles.data.SituationUpdate`, `org.entur.vehicles.data.SeverityEnumeration`, `org.entur.vehicles.repository.SituationRepository`.

- [ ] **Step 4: Add the query metric**

In `PrometheusMetricsService.java`, add the constant next to `CODESPACES` (around line 73):

```java
    private static final String SITUATIONS = "situations";
```

Add the method after `markCodespacesQuery()` (at `PrometheusMetricsService.java:215-217`). It delegates to the existing private `markQuery(String)` helper at line 184, exactly as the other query-marking methods do:

```java
    public void markSituationsQuery() {
        markQuery(SITUATIONS);
    }
```

- [ ] **Step 5: Add the subscription resolver**

In `Subscription.java`, add the field, constructor parameter and method:

```java
    private final SituationUpdateRxPublisher situationUpdater;

    Subscription(VehicleUpdateRxPublisher vehicleUpdater,
                 EstimatedTimetableUpdateRxPublisher timetableUpdater,
                 SituationUpdateRxPublisher situationUpdater,
                 PrometheusMetricsService metricsService) {
        this.vehicleUpdater = vehicleUpdater;
        this.timetableUpdater = timetableUpdater;
        this.situationUpdater = situationUpdater;
        this.metricsService = metricsService;
    }

    @SubscriptionMapping
    Publisher<List<SituationUpdate>> situations(@Argument Set<String> situationNumbers,
                                                @Argument String codespaceId,
                                                @Argument String operatorRef,
                                                @Argument String lineRef,
                                                @Argument String stopRef,
                                                @Argument String serviceJourneyId,
                                                @Argument String datedServiceJourneyId,
                                                @Argument VehicleModeEnumeration mode,
                                                @Argument SeverityEnumeration severity,
                                                @Argument String reportType,
                                                @Argument Boolean validNow,
                                                @Argument Boolean openEnded,
                                                @Argument Duration minAge,
                                                @Argument Boolean includeClosed,
                                                @Argument Integer bufferSize,
                                                @Argument Integer bufferTime) {
        final String uuid = UUID.randomUUID().toString();

        final SituationFilter filter = new SituationFilter(
                metricsService,
                MetricType.SUBSCRIPTION,
                situationNumbers,
                codespaceId,
                operatorRef,
                lineRef,
                stopRef,
                serviceJourneyId,
                datedServiceJourneyId,
                mode,
                severity,
                reportType,
                validNow,
                openEnded,
                minAge,
                includeClosed,
                bufferSize,
                bufferTime
        );
        LOG.debug("Creating new situation-subscription with filter: {}", filter);
        return situationUpdater.getPublisher(filter, uuid);
    }
```

Add the imports: `org.entur.vehicles.data.SituationFilter`, `org.entur.vehicles.data.SituationUpdate`, `org.entur.vehicles.data.SeverityEnumeration`, `org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher`.

- [ ] **Step 6: Extend the GraphQL schema**

In `src/main/resources/graphql/vehicle-updates.graphqls`, add to `type Query` (after the `timetables` field):

```graphql
    situations(
        situationNumbers: [String]
        codespaceId: String
        operatorRef: String
        lineRef: String
        # Matches affected stop points and stop places alike.
        stopRef: String
        serviceJourneyId: String
        datedServiceJourneyId: String
        mode: VehicleModeEnumeration
        severity: SeverityEnumeration
        reportType: String
        # When true, only situations that are valid at the time of the request.
        validNow: Boolean
        # When true, only situations where no validity period carries an end time.
        openEnded: Boolean
        # Only situations created longer ago than this, as Duration (e.g. `P30D`).
        minAge: Duration
        includeClosed: Boolean = false) : [Situation]
```

Add to `type Subscription` (after the `timetables` field):

```graphql
    situations(
        situationNumbers: [String]
        codespaceId: String
        operatorRef: String
        lineRef: String
        # Matches affected stop points and stop places alike.
        stopRef: String
        serviceJourneyId: String
        datedServiceJourneyId: String
        mode: VehicleModeEnumeration
        severity: SeverityEnumeration
        reportType: String
        validNow: Boolean
        openEnded: Boolean
        minAge: Duration
        includeClosed: Boolean = false
        # Number of updates buffered before data is pushed. May be used in combination with bufferTime.
        bufferSize: Int = 20
        # How long - in milliseconds - data is buffered before data is pushed. May be used in combination with bufferSize.
        bufferTime: Int = 250) : [Situation]
```

Add the types at the end of the file, before the enums:

```graphql
type Situation {
    situationNumber: String!
    participantRef: String
    codespace: Codespace
    version: Int
    sourceType: String
    progress: WorkflowStatusEnumeration
    severity: SeverityEnumeration
    priority: Int
    reportType: String
    keywords: [String]
    planned: Boolean
    creationTime: DateTime
    versionedAtTime: DateTime
    validityPeriods: [ValidityPeriod]
    summary: [TranslatedString]
    description: [TranslatedString]
    advice: [TranslatedString]
    detail: [TranslatedString]
    infoLinks: [InfoLink]
    affects: Affects
    lastUpdated: DateTime
    lastUpdatedEpochSecond: Float
    # Null when the situation never expires.
    expiration: DateTime
    expirationEpochSecond: Float

    # True when no validity period carries an end time.
    openEnded: Boolean
    # Time elapsed since creationTime. Null when creationTime is absent.
    age: Duration
}

type Affects {
    lines: [Line]
    stopPoints: [Stop]
    stopPlaces: [Stop]
    serviceJourneys: [ServiceJourney]
    datedServiceJourneys: [DatedServiceJourney]
    operators: [Operator]
    vehicleModes: [VehicleModeEnumeration]
}

type ValidityPeriod {
    startTime: DateTime
    endTime: DateTime
}

type TranslatedString {
    value: String
    language: String
}

type InfoLink {
    uri: String
    labels: [TranslatedString]
}
```

Add the two enums alongside the existing ones:

```graphql
enum WorkflowStatusEnumeration {
    draft
    pendingApproval
    approvedDraft
    open
    published
    closing
    closed
}

enum SeverityEnumeration {
    unknown
    verySlight
    slight
    normal
    severe
    verySevere
    noImpact
    undefined
}
```

- [ ] **Step 7: Fix the two existing test classes**

`VehicleGraphQLTests.java:53` — add the new `null` in third position:

```java
        queryService = new Query(repository, null, null, metricsService);
```

`TimetableGraphQLTests.java:54`:

```java
        queryService = new Query(null, repository, null, metricsService);
```

- [ ] **Step 8: Run the full suite**

Run: `mvn clean test`
Expected: PASS. `SituationGraphQLTests` contributes 8 tests; the VM and ET suites are unchanged in count.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/graphql/vehicle-updates.graphqls \
        src/main/java/org/entur/vehicles/graphql/Query.java \
        src/main/java/org/entur/vehicles/graphql/Subscription.java \
        src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java \
        src/test/java/org/entur/vehicles/graphql/SituationGraphQLTests.java \
        src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java \
        src/test/java/org/entur/vehicles/graphql/TimetableGraphQLTests.java
git commit -m "Exposing situations through GraphQL query and subscription"
```

---

### Task 10: Pub/Sub subscriber and configuration

Connects the pipeline to the live topic. Disabled by default, matching the ET subscriber.

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriber.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/Usage.md`

**Interfaces:**
- Consumes: `SituationRepository.add(PtSituationElementRecord)`, `PubSubSubscriber`'s nine-argument constructor.
- Produces: `PubSubSXSubscriber` Spring service. No other code consumes it.

There is no unit test for this task — the subscriber is a thin wiring class over `PubSubSubscriber`, which needs live Google credentials to construct. Its correctness is covered by the repository and mapper tests plus the manual verification in Step 4.

- [ ] **Step 1: Add configuration**

In `src/main/resources/application.properties`, add the topic and subscription names next to the existing `et` entries (lines 6 and 9):

```properties
entur.vehicle-positions.gcp.topic.name.sx=avro.situation_exchange
entur.vehicle-positions.gcp.subscription.name.sx=vehicle-positions.graphql-${random.uuid}
```

Add the purge settings next to the timetable ones (after line 29):

```properties
situation.updates.purge.interval=PT1M
situation.updates.expiry.grace.period=PT10M
```

The subscriber reads `entur.vehicle-positions.sx.enabled` with a default of `false`, so no property entry is required for it to stay off — matching how `entur.vehicle-positions.et.enabled` is handled.

- [ ] **Step 2: Implement the subscriber**

Create `src/main/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriber.java`, a direct mirror of `PubSubETSubscriber`:

```java
package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.service.pubsub.PubSubSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class PubSubSXSubscriber extends PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubSXSubscriber.class.getName());

  public PubSubSXSubscriber(@Autowired SituationRepository situationRepository,
                            @Value("${entur.vehicle-positions.gcp.subscription.project.name}") String subscriptionProjectName,
                            @Value("${entur.vehicle-positions.gcp.subscription.name.sx}") String subscriptionName,
                            @Value("${entur.vehicle-positions.gcp.topic.project.name}") String topicProjectName,
                            @Value("${entur.vehicle-positions.gcp.topic.name.sx}") String topicName,
                            @Value("${entur.vehicle-positions.pubsub.parallel.pullcount:1}") int parallelPullCount,
                            @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:5}") int executorThreadCount,
                            @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels,
                            @Value("${entur.vehicle-positions.sx.enabled:false}") boolean enabled) {
    super(subscriptionProjectName,
            subscriptionName,
            topicProjectName,
            topicName,
            parallelPullCount,
            executorThreadCount,
            appLabels,
            getMessageReceiver(situationRepository),
            enabled
    );
  }

  private static MessageReceiver getMessageReceiver(SituationRepository situationRepository) {
    return (pubsubMessage, ackReplyConsumer) -> {
      try {
        situationRepository.add(
                JsonReader.readPtSituationElement(pubsubMessage.getData().toStringUtf8())
        );
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    };
  }
}
```

- [ ] **Step 3: Run the full suite**

Run: `mvn clean install`
Expected: PASS. The new subscriber is disabled by default, so it must not affect any test.

- [ ] **Step 4: Manual verification against the live topic**

This step needs GCP credentials and cannot run in CI. If credentials are unavailable, note that and skip to Step 5 — do not mark this step complete without running it.

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--entur.vehicle-positions.sx.enabled=true
```

Then open `http://localhost:8080/` and run:

```graphql
query {
  situations(codespaceId: "RUT") {
    situationNumber
    progress
    severity
    openEnded
    age
    summary { value language }
    affects {
      lines { lineRef lineName }
      stopPoints { id name }
    }
  }
}
```

Expected: a non-empty list with populated `situationNumber`, `progress` and `summary`. Then confirm the quality-tooling query returns without error:

```graphql
query {
  situations(openEnded: true, minAge: "P30D") {
    situationNumber
    codespace { codespaceId }
    age
  }
}
```

- [ ] **Step 5: Document the new stream**

`src/main/resources/Usage.md` has a `## Query` section and a `## Subscription` section, each with a base URL, a fenced example and a list of example links. Add this as a new `## Situations` section at the end of the file, after the GraphQL-subscriptions link:

````markdown
## Situations
Service messages (SIRI-SX) describing disruptions and deviations. Available both as a query and as a
subscription, using the same filter arguments as the examples below.

Situations are filtered by the objects they affect — `lineRef`, `stopRef` (matching both affected stop
points and stop places), `serviceJourneyId`, `datedServiceJourneyId`, `operatorRef` and `mode` — as well
as by `codespaceId`, `severity`, `reportType` and `situationNumbers`.

Closed situations are excluded unless `includeClosed: true` is passed. A situation that is closed is
published to active subscribers once, with `progress: closed`, before it is removed — subscribers should
use that to drop it from display.

A situation published without a validity end time never expires and is retained indefinitely. `openEnded`
and `minAge` exist to find such situations:

```
{
  situations(codespaceId: "RUT") {
    situationNumber
    progress
    severity
    openEnded
    age
    summary { value language }
    affects {
      lines { lineRef lineName }
      stopPoints { id name }
    }
  }
}
```

Open-ended situations that have been active for more than 30 days:

```
{
  situations(openEnded: true, minAge: "P30D") {
    situationNumber
    codespace { codespaceId }
    age
  }
}
```
````

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriber.java \
        src/main/resources/application.properties \
        src/main/resources/Usage.md
git commit -m "Adding SX pubsub subscriber and configuration"
```

---

## Verification

After Task 10, confirm every success criterion from the spec:

1. `mvn clean install` passes.
2. `git log --oneline main..siri_sx_api` shows one commit per task plus the spec commit.
3. `grep -c "situations" src/main/resources/graphql/vehicle-updates.graphqls` returns at least 2 (query and subscription).
4. The VM and ET test suites run the same number of tests as they did before Task 1.
5. Task 10 Step 4 was either performed against the live topic or explicitly reported as skipped for lack of credentials.

Do not claim the feature is complete without the output of `mvn clean install` in hand.
