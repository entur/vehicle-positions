# Situations tagged on journeys and stops together - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the SIRI-SX pairing between an affected journey (or line) and the stops it is affected at, stop the resulting over-matching, and expose the polyline for just the affected part of the journey.

**Architecture:** `Affects` keeps its existing flat lists and id sets untouched and gains two entry lists that hold the pairing. `SituationMatcher.match(Call)` gains a scoped rule that requires the entry to name the call's own journey; journey-level matching is unchanged. `AffectedVehicleJourney.affectedPointsOnLink` is resolved lazily by projecting the entry's stop coordinates onto the journey pattern's stitched geometry and cutting the tightest window that touches them all.

**Tech Stack:** Java 21 language level on JDK 26, Spring Boot + Spring GraphQL, Avro (`siri-avro-model` 2.0.1), JUnit 5, AssertJ, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-09-03-situation-affected-stops-design.md`

## Global Constraints

- **Build and test command:** `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=<TestClass>`. The shell's default JDK is 17 and the build will not run on it. There is no `mvnw` wrapper in this repo.
- **Branch:** `situation-affected-stops`, already created off `main` with the spec committed.
- **No change to the NeTEx extractor, `PlannedDataSnapshot.FORMAT_VERSION` or `NsrSnapshot.FORMAT_VERSION`.** If a task seems to need one, stop and re-read the spec's Approach section.
- **The flat view of `Affects` must not change.** `stopRefs`, `stopPoints` and `stopPlaces` stay top-level-only; `lines`, `serviceJourneys`, `datedServiceJourneys`, `operators` and `vehicleModes` keep exactly today's contents. Existing assertions in `AffectsTest`, `SituationMapperTest`, `SituationFilterTest`, `SituationMatcherTest` and `ApplicationGraphQlSchemaTests` must keep passing untouched - if one needs editing, you have changed the flat view and must revisit.
- **Config property:** `vehicle.situations.affected-geometry.max-snap-meters`, default `500`.
- **Coordinates are interleaved lat/lon microdegrees** (`Polyline`'s convention: `{lat, lon, lat, lon, …}`).
- Every geometry failure returns `null`, never an exception.
- Commit after each task, using the message given in the task's final step.

---

### Task 1: Model types for the pairing

The pairing has nowhere to live today. This task adds the types and the `Affects` fields, with nothing yet writing to them.

**Files:**
- Create: `src/main/java/org/entur/vehicles/data/StopConditionEnumeration.java`
- Create: `src/main/java/org/entur/vehicles/data/model/AffectedStop.java`
- Create: `src/main/java/org/entur/vehicles/data/model/AffectedVehicleJourney.java`
- Create: `src/main/java/org/entur/vehicles/data/model/AffectedLine.java`
- Modify: `src/main/java/org/entur/vehicles/data/model/Affects.java`
- Test: `src/test/java/org/entur/vehicles/data/model/AffectsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `StopConditionEnumeration.fromValue(String) -> StopConditionEnumeration` (null when unrecognised)
  - `new AffectedStop(StopPoint stop, List<StopConditionEnumeration> stopConditions)`, `getStop()`, `getStopConditions()`
  - `new AffectedVehicleJourney(ServiceJourney serviceJourney, DatedServiceJourney datedServiceJourney, Line line, Operator operator, List<AffectedStop> stops)` with `getServiceJourney()`, `getDatedServiceJourney()`, `getLine()`, `getOperator()`, `getStops()`
  - `new AffectedLine(Line line, List<AffectedStop> stops)` with `getLine()`, `getStops()`
  - `Affects.addVehicleJourney(AffectedVehicleJourney)`, `Affects.addAffectedLine(AffectedLine)`, `Affects.getVehicleJourneys()`, `Affects.getAffectedLines()`, `Affects.getAllStopRefs()`
  - `Affects.addLine`, `addServiceJourney`, `addDatedServiceJourney` change return type from `void` to `boolean` (true when the object was new)

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/data/model/AffectsTest.java` (keep every existing test method as it is):

```java
    @Test
    public void testScopedStopsReachAllStopRefsButNotStopRefs() {
        Affects affects = new Affects();
        affects.addStopPlace(new StopPoint("NSR:StopPlace:top"));

        AffectedStop scoped = new AffectedStop(new StopPoint("NSR:StopPlace:157"),
                List.of(StopConditionEnumeration.startPoint, StopConditionEnumeration.notStopping));
        affects.addVehicleJourney(new AffectedVehicleJourney(
                null, new DatedServiceJourney("TST:DatedServiceJourney:1"), null, null, List.of(scoped)));

        // The matcher's set stays top-level only - widening it would re-create the
        // over-matching this change exists to fix.
        assertThat(affects.getStopRefs()).containsExactly("NSR:StopPlace:top");
        // The filter's set is the union, so filtering by stop still finds the situation.
        assertThat(affects.getAllStopRefs())
                .containsExactlyInAnyOrder("NSR:StopPlace:top", "NSR:StopPlace:157");
        // The flat display lists are top-level only too.
        assertThat(affects.getStopPlaces()).hasSize(1);

        assertThat(affects.getVehicleJourneys()).hasSize(1);
        AffectedVehicleJourney entry = affects.getVehicleJourneys().get(0);
        assertThat(entry.getDatedServiceJourney().getId()).isEqualTo("TST:DatedServiceJourney:1");
        assertThat(entry.getStops()).hasSize(1);
        assertThat(entry.getStops().get(0).getStopConditions())
                .containsExactly(StopConditionEnumeration.startPoint, StopConditionEnumeration.notStopping);
    }

    @Test
    public void testAffectedLineEntryCarriesItsOwnStops() {
        Affects affects = new Affects();
        Line line = new Line("TST:Line:1");
        assertThat(affects.addLine(line)).isTrue();
        assertThat(affects.addLine(line)).isFalse();

        affects.addAffectedLine(new AffectedLine(line,
                List.of(new AffectedStop(new StopPoint("NSR:StopPlace:288"), List.of()))));

        assertThat(affects.getAffectedLines()).hasSize(1);
        assertThat(affects.getAffectedLines().get(0).getLine().getLineRef()).isEqualTo("TST:Line:1");
        assertThat(affects.getAllStopRefs()).containsExactly("NSR:StopPlace:288");
        assertThat(affects.getStopRefs()).isEmpty();
    }

    @Test
    public void testStopConditionFromValueIsNullForUnknownValues() {
        assertThat(StopConditionEnumeration.fromValue("startPoint"))
                .isEqualTo(StopConditionEnumeration.startPoint);
        assertThat(StopConditionEnumeration.fromValue("somethingElse")).isNull();
        assertThat(StopConditionEnumeration.fromValue(null)).isNull();
    }
```

`AffectsTest` currently imports only JUnit's `assertEquals`/`assertFalse`/`assertTrue`. AssertJ is on the test classpath (`SituationMatcherTest` uses it), so add these three imports: `org.entur.vehicles.data.StopConditionEnumeration`, `java.util.List`, and `static org.assertj.core.api.Assertions.assertThat`. Leave the existing JUnit imports and every existing test method alone.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=AffectsTest`
Expected: FAIL - compilation errors, `cannot find symbol: class AffectedStop`.

- [ ] **Step 3: Write the enum**

`src/main/java/org/entur/vehicles/data/StopConditionEnumeration.java`:

```java
package org.entur.vehicles.data;

/**
 * SIRI-SX {@code StopCondition}. Carried through to clients so they can tell a stop the
 * vehicle passes without stopping from one where the disruption starts; it deliberately
 * does not drive the affected-segment rule, because producers tag it inconsistently -
 * see the spec's Decisions section.
 */
public enum StopConditionEnumeration {
    exceptionalStop,
    destination,
    notStopping,
    requestStop,
    startPoint;

    /** Null for an unrecognised or absent value - callers drop it rather than failing. */
    public static StopConditionEnumeration fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (StopConditionEnumeration condition : values()) {
            if (condition.name().equalsIgnoreCase(value)) {
                return condition;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Write the three model types**

`src/main/java/org/entur/vehicles/data/model/AffectedStop.java`:

```java
package org.entur.vehicles.data.model;

import org.entur.vehicles.data.StopConditionEnumeration;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

/** A stop named inside an affected journey or line, with the conditions the producer tagged. */
@SchemaMapping
public class AffectedStop {

    private final StopPoint stop;
    private final List<StopConditionEnumeration> stopConditions;

    public AffectedStop(StopPoint stop, List<StopConditionEnumeration> stopConditions) {
        this.stop = stop;
        this.stopConditions = stopConditions == null ? List.of() : List.copyOf(stopConditions);
    }

    public StopPoint getStop() {
        return stop;
    }

    public List<StopConditionEnumeration> getStopConditions() {
        return stopConditions;
    }
}
```

`src/main/java/org/entur/vehicles/data/model/AffectedVehicleJourney.java`:

```java
package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

/**
 * One journey a situation names, with the stops it is affected at.
 * <p>
 * One entry per named journey: a SIRI {@code AffectedVehicleJourney} that names three dated
 * journeys becomes three entries sharing the same stop list, which is what lets the matcher
 * index an entry by a single journey id.
 * <p>
 * {@code line} and {@code operator} are display context only. An entry is never indexed by
 * its line - it is scoped to the journey it names, and indexing it by line would widen
 * matching back to every journey on that line.
 */
@SchemaMapping
public class AffectedVehicleJourney {

    private final ServiceJourney serviceJourney;
    private final DatedServiceJourney datedServiceJourney;
    private final Line line;
    private final Operator operator;
    private final List<AffectedStop> stops;

    public AffectedVehicleJourney(ServiceJourney serviceJourney,
                                  DatedServiceJourney datedServiceJourney,
                                  Line line,
                                  Operator operator,
                                  List<AffectedStop> stops) {
        this.serviceJourney = serviceJourney;
        this.datedServiceJourney = datedServiceJourney;
        this.line = line;
        this.operator = operator;
        this.stops = stops == null ? List.of() : List.copyOf(stops);
    }

    public ServiceJourney getServiceJourney() {
        return serviceJourney;
    }

    public DatedServiceJourney getDatedServiceJourney() {
        return datedServiceJourney;
    }

    public Line getLine() {
        return line;
    }

    public Operator getOperator() {
        return operator;
    }

    public List<AffectedStop> getStops() {
        return stops;
    }
}
```

`src/main/java/org/entur/vehicles/data/model/AffectedLine.java`:

```java
package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.List;

/**
 * One line a situation names, with the stops it is affected at. Unlike a journey entry this
 * carries no geometry: a line has many journey patterns, so there is no single polyline to cut.
 */
@SchemaMapping
public class AffectedLine {

    private final Line line;
    private final List<AffectedStop> stops;

    public AffectedLine(Line line, List<AffectedStop> stops) {
        this.line = line;
        this.stops = stops == null ? List.of() : List.copyOf(stops);
    }

    public Line getLine() {
        return line;
    }

    public List<AffectedStop> getStops() {
        return stops;
    }
}
```

- [ ] **Step 5: Extend `Affects`**

In `src/main/java/org/entur/vehicles/data/model/Affects.java`, add next to the existing fields:

```java
    private final List<AffectedVehicleJourney> vehicleJourneys = new ArrayList<>();
    private final List<AffectedLine> affectedLines = new ArrayList<>();

    /**
     * Every stop this situation mentions, top-level and scoped alike. Backs the {@code stopRef}
     * query filter - discovery. {@link #getStopRefs()} stays top-level-only and backs matching -
     * attachment. The two are deliberately different sets; see the spec.
     */
    private final Set<String> allStopRefs = new HashSet<>();
```

Change the three `add*` methods that the mapper needs to guard on, so they report whether the
object was new (bodies otherwise unchanged):

```java
    public boolean addLine(Line line) {
        if (line != null && line.getLineRef() != null && lineRefs.add(line.getLineRef())) {
            lines.add(line);
            return true;
        }
        return false;
    }

    public boolean addServiceJourney(ServiceJourney serviceJourney) {
        if (serviceJourney != null && serviceJourney.getId() != null
                && serviceJourneyIds.add(serviceJourney.getId())) {
            serviceJourneys.add(serviceJourney);
            return true;
        }
        return false;
    }

    public boolean addDatedServiceJourney(DatedServiceJourney datedServiceJourney) {
        if (datedServiceJourney != null && datedServiceJourney.getId() != null
                && datedServiceJourneyIds.add(datedServiceJourney.getId())) {
            datedServiceJourneys.add(datedServiceJourney);
            return true;
        }
        return false;
    }
```

Add `allStopRefs.add(...)` to the two top-level stop adders, inside the existing guard:

```java
    public void addStopPoint(StopPoint stopPoint) {
        if (stopPoint != null && stopPoint.getId() != null && stopRefs.add(stopPoint.getId())) {
            stopPoints.add(stopPoint);
            allStopRefs.add(stopPoint.getId());
        }
    }

    public void addStopPlace(StopPoint stopPlace) {
        if (stopPlace != null && stopPlace.getId() != null && stopRefs.add(stopPlace.getId())) {
            stopPlaces.add(stopPlace);
            allStopRefs.add(stopPlace.getId());
        }
    }
```

And add the entry adders and getters:

```java
    public void addVehicleJourney(AffectedVehicleJourney journey) {
        if (journey == null) {
            return;
        }
        vehicleJourneys.add(journey);
        indexScopedStops(journey.getStops());
    }

    public void addAffectedLine(AffectedLine affectedLine) {
        if (affectedLine == null) {
            return;
        }
        affectedLines.add(affectedLine);
        indexScopedStops(affectedLine.getStops());
    }

    private void indexScopedStops(List<AffectedStop> stops) {
        for (AffectedStop stop : stops) {
            if (stop.getStop() != null && stop.getStop().getId() != null) {
                allStopRefs.add(stop.getStop().getId());
            }
        }
    }

    public List<AffectedVehicleJourney> getVehicleJourneys() {
        return Collections.unmodifiableList(vehicleJourneys);
    }

    public List<AffectedLine> getAffectedLines() {
        return Collections.unmodifiableList(affectedLines);
    }

    /** Top-level and scoped stops together. See {@link #allStopRefs}. */
    public Set<String> getAllStopRefs() {
        return allStopRefs;
    }
```

Leave `isEmpty()` exactly as it is: an entry always mirrors a line, journey or dated journey
that is already in one of the lists it tests, so adding the new lists would not change any
result and would only add work.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest='AffectsTest'`
Expected: PASS.

Then confirm nothing that reads `Affects` regressed:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest='Situation*Test,Situation*Tests'`
Expected: PASS, with no edits to those test files.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/StopConditionEnumeration.java \
        src/main/java/org/entur/vehicles/data/model/AffectedStop.java \
        src/main/java/org/entur/vehicles/data/model/AffectedVehicleJourney.java \
        src/main/java/org/entur/vehicles/data/model/AffectedLine.java \
        src/main/java/org/entur/vehicles/data/model/Affects.java \
        src/test/java/org/entur/vehicles/data/model/AffectsTest.java
git commit -m "feat: model the journey-to-stops pairing a situation carries"
```

---

### Task 2: Read the pairing out of the SX message

`SituationMapper.mapAffects` currently drops `AffectedVehicleJourneyRecord.getRoutes()` and `AffectedLineRecord.getRoutes()` entirely. This task reads them into the Task 1 types while leaving the flat lists byte-identical.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/repository/SituationMapper.java:196-256`
- Test: `src/test/java/org/entur/vehicles/repository/SituationMapperTest.java`

**Interfaces:**
- Consumes: everything Task 1 produces.
- Produces: `Affects.getVehicleJourneys()` and `Affects.getAffectedLines()` populated from an Avro `PtSituationElementRecord`.

**Avro shape** (verified against `siri-avro-model` 2.0.1 - do not guess at these names):
`AffectedVehicleJourneyRecord.getRoutes()` and `AffectedLineRecord.getRoutes()` both return
`List<AffectedRouteRecord>`; `AffectedRouteRecord.getStopPoints()` returns a single
`StopPointsRecord`; `StopPointsRecord.getStopPoints()` returns `List<AffectedStopPointRecord>`;
`AffectedStopPointRecord.getStopConditions()` returns `List<CharSequence>`.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/repository/SituationMapperTest.java`:

```java
    private static AffectedStopPointRecord affectedStop(String stopRef, String... conditions) {
        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef(stopRef);
        stopPoint.setStopPointNames(List.of());
        stopPoint.setStopConditions(List.of(conditions));
        return stopPoint;
    }

    private static AffectedRouteRecord route(AffectedStopPointRecord... stopPoints) {
        StopPointsRecord stops = new StopPointsRecord();
        stops.setStopPoints(List.of(stopPoints));
        AffectedRouteRecord route = new AffectedRouteRecord();
        route.setStopPoints(stops);
        route.setSections(List.of());
        return route;
    }

    /**
     * The shape real producers send: the stops are nested inside the journey they apply to,
     * one journey listing three and another listing one. Both journeys must come back as
     * their own entry carrying their own stops - a flat union would lose which is which.
     */
    @Test
    public void testStopsNestedInAVehicleJourneyBecomeTheirOwnEntry() {
        AffectedVehicleJourneyRecord first = new AffectedVehicleJourneyRecord();
        first.setVehicleJourneyRefs(List.of());
        first.setDatedVehicleJourneyRefs(List.of("VYG:DatedServiceJourney:1123"));
        first.setRoutes(List.of(route(
                affectedStop("NSR:StopPlace:157", "startPoint", "notStopping"),
                affectedStop("NSR:StopPlace:152", "startPoint"),
                affectedStop("NSR:StopPlace:288", "startPoint"))));

        AffectedVehicleJourneyRecord second = new AffectedVehicleJourneyRecord();
        second.setVehicleJourneyRefs(List.of());
        second.setDatedVehicleJourneyRefs(List.of("VYG:DatedServiceJourney:518"));
        second.setRoutes(List.of(route(affectedStop("NSR:StopPlace:157", "startPoint"))));

        AffectsRecord affectsRecord = new AffectsRecord();
        affectsRecord.setNetworks(List.of());
        affectsRecord.setStopPoints(List.of());
        affectsRecord.setStopPlaces(List.of());
        affectsRecord.setVehicleJourneys(List.of(first, second));

        PtSituationElementRecord record = recordAffectingDatedServiceJourney("VYG:DatedServiceJourney:1123");
        record.setAffects(affectsRecord);

        SituationUpdate situation = mapper.map(record);

        assertEquals(2, situation.getAffects().getVehicleJourneys().size());

        AffectedVehicleJourney one = situation.getAffects().getVehicleJourneys().get(0);
        assertEquals("VYG:DatedServiceJourney:1123", one.getDatedServiceJourney().getId());
        assertEquals(3, one.getStops().size());
        assertEquals("NSR:StopPlace:157", one.getStops().get(0).getStop().getId());
        assertEquals(List.of(StopConditionEnumeration.startPoint, StopConditionEnumeration.notStopping),
                one.getStops().get(0).getStopConditions());

        AffectedVehicleJourney two = situation.getAffects().getVehicleJourneys().get(1);
        assertEquals("VYG:DatedServiceJourney:518", two.getDatedServiceJourney().getId());
        assertEquals(1, two.getStops().size());

        // The flat view is unchanged: scoped stops stay out of it, and both journeys are
        // still listed as dated service journeys exactly as before.
        assertTrue(situation.getAffects().getStopRefs().isEmpty());
        assertTrue(situation.getAffects().getStopPlaces().isEmpty());
        assertEquals(2, situation.getAffects().getDatedServiceJourneyIds().size());
        // ...but the union set carries them, so the stopRef filter can find this situation.
        assertEquals(3, situation.getAffects().getAllStopRefs().size());
    }

    @Test
    public void testAnUnknownStopConditionIsDroppedRatherThanFailingTheMessage() {
        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setVehicleJourneyRefs(List.of());
        journey.setDatedVehicleJourneyRefs(List.of("VYG:DatedServiceJourney:1123"));
        journey.setRoutes(List.of(route(affectedStop("NSR:StopPlace:157", "startPoint", "notAThing"))));

        AffectsRecord affectsRecord = new AffectsRecord();
        affectsRecord.setNetworks(List.of());
        affectsRecord.setStopPoints(List.of());
        affectsRecord.setStopPlaces(List.of());
        affectsRecord.setVehicleJourneys(List.of(journey));

        PtSituationElementRecord record = recordAffectingDatedServiceJourney("VYG:DatedServiceJourney:1123");
        record.setAffects(affectsRecord);

        SituationUpdate situation = mapper.map(record);

        assertEquals(List.of(StopConditionEnumeration.startPoint),
                situation.getAffects().getVehicleJourneys().get(0).getStops().get(0).getStopConditions());
    }

    @Test
    public void testStopsNestedInAnAffectedLineBecomeALineEntry() {
        AffectedLineRecord affectedLine = new AffectedLineRecord();
        affectedLine.setLineRef("TST:Line:1");
        affectedLine.setRoutes(List.of(route(affectedStop("NSR:StopPlace:288"))));
        affectedLine.setSections(List.of());

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setVehicleMode("bus");
        network.setAffectedLines(List.of(affectedLine));
        network.setAffectedOperators(List.of());

        AffectsRecord affectsRecord = new AffectsRecord();
        affectsRecord.setNetworks(List.of(network));
        affectsRecord.setStopPoints(List.of());
        affectsRecord.setStopPlaces(List.of());
        affectsRecord.setVehicleJourneys(List.of());

        PtSituationElementRecord record = recordAffectingDatedServiceJourney("VYG:DatedServiceJourney:1123");
        record.setAffects(affectsRecord);

        SituationUpdate situation = mapper.map(record);

        assertEquals(1, situation.getAffects().getAffectedLines().size());
        assertEquals("TST:Line:1", situation.getAffects().getAffectedLines().get(0).getLine().getLineRef());
        assertEquals("NSR:StopPlace:288",
                situation.getAffects().getAffectedLines().get(0).getStops().get(0).getStop().getId());
        assertTrue(situation.getAffects().getVehicleJourneys().isEmpty());
    }
```

Add the imports the file is missing: `org.entur.avro.realtime.siri.model.AffectedRouteRecord`,
`org.entur.avro.realtime.siri.model.StopPointsRecord`,
`org.entur.vehicles.data.StopConditionEnumeration`,
`org.entur.vehicles.data.model.AffectedVehicleJourney`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=SituationMapperTest`
Expected: FAIL - `getVehicleJourneys()` returns an empty list, so `assertEquals(2, …)` fails
with "expected: <2> but was: <0>".

- [ ] **Step 3: Add the stop-mapping helpers**

In `SituationMapper`, add:

```java
    /**
     * The stops nested under an affected journey or line. SIRI allows several routes per
     * object; their stops are flattened into one list, because the affected segment is a span
     * over the journey and a per-route split would have no meaning for it.
     */
    private List<AffectedStop> mapRouteStops(List<AffectedRouteRecord> routes) {
        List<AffectedStop> stops = new ArrayList<>();
        if (!containsValues(routes)) {
            return stops;
        }
        for (AffectedRouteRecord route : routes) {
            if (route.getStopPoints() == null || !containsValues(route.getStopPoints().getStopPoints())) {
                continue;
            }
            for (AffectedStopPointRecord stopPoint : route.getStopPoints().getStopPoints()) {
                StopPoint stop = resolveStop(asString(stopPoint.getStopPointRef()));
                if (stop == null) {
                    continue;
                }
                stops.add(new AffectedStop(stop, mapStopConditions(stopPoint.getStopConditions())));
            }
        }
        return stops;
    }

    private List<StopConditionEnumeration> mapStopConditions(List<CharSequence> values) {
        List<StopConditionEnumeration> conditions = new ArrayList<>();
        if (!containsValues(values)) {
            return conditions;
        }
        for (CharSequence value : values) {
            StopConditionEnumeration condition = StopConditionEnumeration.fromValue(asString(value));
            if (condition != null) {
                conditions.add(condition);
            } else {
                LOG.debug("Unknown stop condition {} - ignoring.", value);
            }
        }
        return conditions;
    }
```

- [ ] **Step 4: Emit line entries**

In `mapAffects`, replace the affected-lines loop inside the networks block with:

```java
                if (containsValues(network.getAffectedLines())) {
                    for (AffectedLineRecord affectedLine : network.getAffectedLines()) {
                        Line line = resolveLine(asString(affectedLine.getLineRef()));
                        if (affects.addLine(line)) {
                            affects.addAffectedLine(new AffectedLine(line, mapRouteStops(affectedLine.getRoutes())));
                        }
                    }
                }
```

- [ ] **Step 5: Emit journey entries**

Replace the whole `record.getVehicleJourneys()` block with:

```java
        if (containsValues(record.getVehicleJourneys())) {
            for (AffectedVehicleJourneyRecord journey : record.getVehicleJourneys()) {
                Line line = resolveLine(asString(journey.getLineRef()));
                affects.addLine(line);

                Operator operator = null;
                if (journey.getOperator() != null) {
                    operator = resolveOperator(asString(journey.getOperator().getOperatorRef()));
                    affects.addOperator(operator);
                }

                // Shared by every entry this record produces: the producer nests one stop
                // list per affected journey, and a record naming several journeys means all
                // of them are affected at those same stops.
                List<AffectedStop> stops = mapRouteStops(journey.getRoutes());

                // Affects.addServiceJourney dedupes on getId() alone, so when the same id
                // appears both as a bare ref and as a framed ref, whichever is added first
                // wins. The framed ref carries the dataFrameRef date, so it must be added
                // before the bare vehicleJourneyRefs - otherwise the date is silently lost.
                // Entries are guarded on the same return value, so the duplicate does not
                // produce a second entry for the same journey either.
                if (journey.getFramedVehicleJourneyRef() != null
                        && journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef() != null) {
                    ServiceJourney serviceJourney = new ServiceJourney(
                            journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef().toString(),
                            asString(journey.getFramedVehicleJourneyRef().getDataFrameRef()));
                    if (affects.addServiceJourney(serviceJourney)) {
                        affects.addVehicleJourney(
                                new AffectedVehicleJourney(serviceJourney, null, line, operator, stops));
                    }
                }
                if (containsValues(journey.getVehicleJourneyRefs())) {
                    for (CharSequence ref : journey.getVehicleJourneyRefs()) {
                        ServiceJourney serviceJourney = new ServiceJourney(ref.toString());
                        if (affects.addServiceJourney(serviceJourney)) {
                            affects.addVehicleJourney(
                                    new AffectedVehicleJourney(serviceJourney, null, line, operator, stops));
                        }
                    }
                }
                if (containsValues(journey.getDatedVehicleJourneyRefs())) {
                    for (CharSequence ref : journey.getDatedVehicleJourneyRefs()) {
                        DatedServiceJourney dated = resolveDatedServiceJourney(ref.toString());
                        if (affects.addDatedServiceJourney(dated)) {
                            affects.addVehicleJourney(
                                    new AffectedVehicleJourney(null, dated, line, operator, stops));
                        }
                    }
                }
            }
        }
```

Add the imports: `org.entur.avro.realtime.siri.model.AffectedRouteRecord`,
`org.entur.vehicles.data.StopConditionEnumeration`,
`org.entur.vehicles.data.model.AffectedLine`, `org.entur.vehicles.data.model.AffectedStop`,
`org.entur.vehicles.data.model.AffectedVehicleJourney`, `org.entur.vehicles.data.model.Operator`.
Note the class currently spells `Operator` fully qualified in two method signatures; leave
those as they are rather than reformatting unrelated lines.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=SituationMapperTest`
Expected: PASS, including every pre-existing method in the class.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/SituationMapper.java \
        src/test/java/org/entur/vehicles/repository/SituationMapperTest.java
git commit -m "feat: read the stops nested inside affected journeys and lines"
```

---

### Task 3: Filtering by stop finds scoped mentions

**Files:**
- Modify: `src/main/java/org/entur/vehicles/data/SituationFilter.java:137`
- Test: `src/test/java/org/entur/vehicles/data/SituationFilterTest.java`

**Interfaces:**
- Consumes: `Affects.getAllStopRefs()` from Task 1, `Affects.addVehicleJourney` from Task 1.
- Produces: no new API.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/data/SituationFilterTest.java`:

```java
    /**
     * Filtering is discovery, matching is attachment. A client asking "what is going on at
     * Oslo S" must find a situation that names the stop only inside an affected journey,
     * even though that scoped stop deliberately never widens what the journey matcher attaches.
     */
    @Test
    public void testStopRefFindsAStopNamedOnlyInsideAnAffectedJourney() {
        SituationUpdate situation = situation();
        situation.getAffects().addVehicleJourney(new AffectedVehicleJourney(
                null,
                new DatedServiceJourney("TST:DatedServiceJourney:1"),
                null,
                null,
                List.of(new AffectedStop(new StopPoint("NSR:StopPlace:157"), List.of()))));

        SituationFilter matching = new SituationFilter(null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:157"), null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(matching.isMatch(situation));

        SituationFilter nonMatching = new SituationFilter(null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:999"), null, null, null, null, null, null, null, null, null, null, null);
        assertFalse(nonMatching.isMatch(situation));
    }
```

`situation()` is the class's existing no-argument helper (`SituationFilterTest.java:24`). It
already adds a top-level `TST:Quay:1` stop point, so the situation under test has both an
unscoped and a scoped stop - which is exactly the mix the union set has to handle. Add the
imports for `AffectedStop` and `AffectedVehicleJourney`; `DatedServiceJourney`, `StopPoint`,
`List` and `Set` are already imported.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=SituationFilterTest`
Expected: FAIL on the first assertion - `isMatch` returns false, because the filter still
reads `getStopRefs()`, which is top-level-only.

- [ ] **Step 3: Point the filter at the union set**

In `SituationFilter.isMatch`, change the stop clause:

```java
        // allStopRefs, not stopRefs: filtering is discovery - a client asking about a stop
        // wants situations that mention it anywhere, including inside an affected journey.
        // Matching (SituationMatcher) deliberately uses the narrower stopRefs instead.
        if (stopRefs != null && (affects == null || Collections.disjoint(affects.getAllStopRefs(), stopRefs))) {
            return false;
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=SituationFilterTest`
Expected: PASS, including every pre-existing method.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/SituationFilter.java \
        src/test/java/org/entur/vehicles/data/SituationFilterTest.java
git commit -m "feat: find situations by a stop named inside an affected journey"
```

---

### Task 4: Scoped stops only match their own journey's calls

The behaviour fix. `match(Call)` today matches on stop ref alone, so a situation naming three dated journeys plus Oslo S would attach to every journey calling at Oslo S. A `Call` has no back-reference to its journey, so the scoped rule needs one.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/data/model/Call.java`
- Modify: `src/main/java/org/entur/vehicles/data/EstimatedTimetableUpdate.java:140-145`
- Modify: `src/main/java/org/entur/vehicles/data/SituationMatcher.java`
- Test: `src/test/java/org/entur/vehicles/data/SituationMatcherTest.java`

**Interfaces:**
- Consumes: `Affects.getVehicleJourneys()`, `Affects.getAffectedLines()`, `AffectedVehicleJourney.getStops()`, `AffectedLine.getStops()` from Task 1.
- Produces: `Call.getOwner() -> EstimatedTimetableUpdate` (null when the call was never added to a journey), `Call.setOwner(EstimatedTimetableUpdate)`.

**Why `addCall` is the place:** both call sites in `TimetableRepository` (lines 211 and 243) go
through `EstimatedTimetableUpdate.addCall`, and so does every test helper. Setting the owner
there covers every construction path at once.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/data/SituationMatcherTest.java`:

```java
    private AffectedVehicleJourney journeyEntry(String datedServiceJourneyId, String... stopRefs) {
        List<AffectedStop> stops = new ArrayList<>();
        for (String stopRef : stopRefs) {
            stops.add(new AffectedStop(new StopPoint(stopRef), List.of()));
        }
        return new AffectedVehicleJourney(
                null, new DatedServiceJourney(datedServiceJourneyId), null, null, stops);
    }

    private EstimatedTimetableUpdate datedTimetable(String lineRef,
                                                    String serviceJourneyId,
                                                    String datedServiceJourneyId,
                                                    Call... calls) {
        EstimatedTimetableUpdate timetable = timetable(lineRef, serviceJourneyId, calls);
        timetable.setDatedServiceJourney(new DatedServiceJourney(
                datedServiceJourneyId, new ServiceJourney(serviceJourneyId)));
        return timetable;
    }

    /**
     * The whole point of the change: a situation naming journey A *at* a stop must not attach
     * to journey B's call at that same stop. Before scoped matching this was a flat stop-ref
     * lookup, so every journey calling there picked it up.
     */
    @Test
    public void testAStopScopedToOneJourneyDoesNotMatchAnotherJourneysCallThere() {
        SituationUpdate situation = situation("TST:SituationNumber:scoped");
        situation.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:A"));
        situation.getAffects().addVehicleJourney(
                journeyEntry("TST:DatedServiceJourney:A", "NSR:Quay:1", "NSR:Quay:2"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        Call ownCall = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        datedTimetable("TST:Line:1", "TST:ServiceJourney:A", "TST:DatedServiceJourney:A", ownCall);
        assertThat(matcher.match(ownCall))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:scoped");

        Call foreignCall = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        datedTimetable("TST:Line:2", "TST:ServiceJourney:B", "TST:DatedServiceJourney:B", foreignCall);
        assertThat(matcher.match(foreignCall)).isEmpty();
    }

    /** A top-level stop is unscoped and keeps matching any journey calling there. */
    @Test
    public void testAnUnscopedStopStillMatchesAnyJourney() {
        SituationUpdate situation = situation("TST:SituationNumber:unscoped");
        situation.getAffects().addStopPoint(new StopPoint("NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        Call foreignCall = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        datedTimetable("TST:Line:2", "TST:ServiceJourney:B", "TST:DatedServiceJourney:B", foreignCall);
        assertThat(matcher.match(foreignCall))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:unscoped");
    }

    /**
     * The safety net: an entry whose stops the journey never calls at keeps the situation on
     * the journey rather than dropping it. Nothing is removed because nothing matched a call.
     */
    @Test
    public void testAScopedEntryMatchingNoCallStaysOnTheJourney() {
        SituationUpdate situation = situation("TST:SituationNumber:elsewhere-on-this-journey");
        situation.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:A"));
        situation.getAffects().addVehicleJourney(
                journeyEntry("TST:DatedServiceJourney:A", "NSR:Quay:not-called-at"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        EstimatedTimetableUpdate timetable = datedTimetable("TST:Line:1", "TST:ServiceJourney:A",
                "TST:DatedServiceJourney:A", call("NSR:Quay:1", noon, noon.plusMinutes(1)));

        assertThat(matcher.match(timetable))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:elsewhere-on-this-journey");
    }

    /** A scoped stop matched through the hierarchy: situation on the stop place, call on a quay. */
    @Test
    public void testAScopedStopMatchesThroughTheAncestorClimb() {
        SituationUpdate situation = situation("TST:SituationNumber:scoped-place");
        situation.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:A"));
        situation.getAffects().addVehicleJourney(
                journeyEntry("TST:DatedServiceJourney:A", "NSR:StopPlace:157"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation),
                ref -> "NSR:Quay:1".equals(ref) ? Set.of("NSR:StopPlace:157") : Set.of());

        Call ownCall = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        datedTimetable("TST:Line:1", "TST:ServiceJourney:A", "TST:DatedServiceJourney:A", ownCall);

        assertThat(matcher.match(ownCall))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:scoped-place");
    }

    /** A line entry's stops scope to that line, not to one journey. */
    @Test
    public void testALineEntrysStopsMatchAnyJourneyOnThatLineOnly() {
        SituationUpdate situation = situation("TST:SituationNumber:line-scoped");
        Line line = new Line("TST:Line:1");
        situation.getAffects().addLine(line);
        situation.getAffects().addAffectedLine(new AffectedLine(line,
                List.of(new AffectedStop(new StopPoint("NSR:Quay:1"), List.of()))));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        Call onLine = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        datedTimetable("TST:Line:1", "TST:ServiceJourney:A", "TST:DatedServiceJourney:A", onLine);
        assertThat(matcher.match(onLine))
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:line-scoped");

        Call offLine = call("NSR:Quay:1", noon, noon.plusMinutes(1));
        datedTimetable("TST:Line:2", "TST:ServiceJourney:B", "TST:DatedServiceJourney:B", offLine);
        assertThat(matcher.match(offLine)).isEmpty();
    }

    /** A call never added to a journey has no owner; the scoped rule must not throw. */
    @Test
    public void testACallWithNoOwnerIsSafe() {
        SituationUpdate situation = situation("TST:SituationNumber:scoped");
        situation.getAffects().addDatedServiceJourney(new DatedServiceJourney("TST:DatedServiceJourney:A"));
        situation.getAffects().addVehicleJourney(journeyEntry("TST:DatedServiceJourney:A", "NSR:Quay:1"));

        SituationMatcher matcher = new SituationMatcher(List.of(situation));

        assertThat(matcher.match(call("NSR:Quay:1", noon, noon.plusMinutes(1)))).isEmpty();
    }
```

Add the imports: `org.entur.vehicles.data.model.AffectedLine`,
`org.entur.vehicles.data.model.AffectedStop`,
`org.entur.vehicles.data.model.AffectedVehicleJourney`, `java.util.ArrayList`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=SituationMatcherTest`
Expected: FAIL - `testAStopScopedToOneJourneyDoesNotMatchAnotherJourneysCallThere` fails on the
first assertion (the scoped situation is not found at all, because scoped stops are not indexed).

- [ ] **Step 3: Give a call its owner**

In `src/main/java/org/entur/vehicles/data/model/Call.java`, add the field and accessors:

```java
    /**
     * The journey this call belongs to, set by {@link org.entur.vehicles.data.EstimatedTimetableUpdate#addCall}.
     * Read by SituationMatcher so a stop scoped to a journey can be checked against the call's
     * own journey. Deliberately not part of the GraphQL schema, and deliberately excluded from
     * equals/hashCode/toString - it is a back-reference, and including it would recurse.
     */
    private EstimatedTimetableUpdate owner;

    public EstimatedTimetableUpdate getOwner() {
        return owner;
    }

    public void setOwner(EstimatedTimetableUpdate owner) {
        this.owner = owner;
    }
```

Import `org.entur.vehicles.data.EstimatedTimetableUpdate`. If `Call` defines
`equals`/`hashCode`/`toString`, leave them untouched - do not add `owner` to them.

In `src/main/java/org/entur/vehicles/data/EstimatedTimetableUpdate.java`, replace `addCall`:

```java
  public void addCall(Call call) {
      if (this.calls == null) {
          this.calls = new ArrayList<>();
      }
      // The single funnel every construction path goes through - TimetableRepository and the
      // tests alike - so setting the back-reference here covers all of them.
      call.setOwner(this);
      this.calls.add(call);
  }
```

- [ ] **Step 4: Index the scoped entries**

In `SituationMatcher`, add the index next to the existing four maps:

```java
    /**
     * Stops that only apply to the object they were nested under, keyed by that object's
     * service journey id, dated service journey id or line ref.
     * <p>
     * A journey entry is keyed by its journey refs only, never by its line: the line on an
     * AffectedVehicleJourney is context for display, and keying on it would widen the
     * situation back to every journey on that line - the exact over-matching this index exists
     * to remove. Only an AffectedLine entry is keyed by line.
     */
    private final Map<String, List<ScopedStops>> scopedByObject = new HashMap<>();

    private record ScopedStops(SituationUpdate situation, Set<String> stopRefs) {}
```

In the constructor loop, after the four existing `index(...)` calls:

```java
            indexScoped(situation, affects);
```

and add the two methods:

```java
    private void indexScoped(SituationUpdate situation, Affects affects) {
        for (AffectedVehicleJourney journey : affects.getVehicleJourneys()) {
            Set<String> stopRefs = stopRefsOf(journey.getStops());
            if (stopRefs.isEmpty()) {
                // No stops means the journey is affected as a whole - journey-level matching
                // through the flat id sets already covers it.
                continue;
            }
            ScopedStops scoped = new ScopedStops(situation, stopRefs);
            if (journey.getServiceJourney() != null && journey.getServiceJourney().getId() != null) {
                scopedByObject.computeIfAbsent(journey.getServiceJourney().getId(), key -> new ArrayList<>()).add(scoped);
            }
            if (journey.getDatedServiceJourney() != null && journey.getDatedServiceJourney().getId() != null) {
                scopedByObject.computeIfAbsent(journey.getDatedServiceJourney().getId(), key -> new ArrayList<>()).add(scoped);
            }
        }
        for (AffectedLine affectedLine : affects.getAffectedLines()) {
            Set<String> stopRefs = stopRefsOf(affectedLine.getStops());
            if (stopRefs.isEmpty() || affectedLine.getLine() == null || affectedLine.getLine().getLineRef() == null) {
                continue;
            }
            scopedByObject.computeIfAbsent(affectedLine.getLine().getLineRef(), key -> new ArrayList<>())
                    .add(new ScopedStops(situation, stopRefs));
        }
    }

    private static Set<String> stopRefsOf(List<AffectedStop> stops) {
        Set<String> refs = new HashSet<>();
        for (AffectedStop stop : stops) {
            if (stop.getStop() != null && stop.getStop().getId() != null) {
                refs.add(stop.getStop().getId());
            }
        }
        return refs;
    }
```

- [ ] **Step 5: Apply the scoped rule in `match(Call)`**

Replace `match(Call)` with:

```java
    public List<SituationUpdate> match(Call call) {
        if (call.getStopPoint() == null || call.getStopPoint().getId() == null) {
            return List.of();
        }
        String stopId = call.getStopPoint().getId();
        Set<String> ancestors = ancestorResolver.apply(stopId);
        Map<Identity, SituationUpdate> matched = new LinkedHashMap<>();
        collect(byStopRef.get(stopId), call.getWindowStart(), call.getWindowEnd(), matched);
        for (String ancestor : ancestors) {
            collect(byStopRef.get(ancestor), call.getWindowStart(), call.getWindowEnd(), matched);
        }
        collectScoped(call, stopId, ancestors, matched);
        return new ArrayList<>(matched.values());
    }

    /**
     * Situations whose stops were nested under this call's own journey, dated journey or line.
     * The temporal rule is the same as for an unscoped stop: the situation still has to be in
     * force while the vehicle is at this call.
     */
    private void collectScoped(Call call,
                               String stopId,
                               Set<String> ancestors,
                               Map<Identity, SituationUpdate> matched) {
        EstimatedTimetableUpdate owner = call.getOwner();
        if (owner == null) {
            return;
        }
        if (owner.getServiceJourney() != null) {
            collectScopedFor(owner.getServiceJourney().getId(), call, stopId, ancestors, matched);
        }
        if (owner.getDatedServiceJourney() != null) {
            collectScopedFor(owner.getDatedServiceJourney().getId(), call, stopId, ancestors, matched);
        }
        if (owner.getLine() != null) {
            collectScopedFor(owner.getLine().getLineRef(), call, stopId, ancestors, matched);
        }
    }

    private void collectScopedFor(String key,
                                  Call call,
                                  String stopId,
                                  Set<String> ancestors,
                                  Map<Identity, SituationUpdate> matched) {
        if (key == null) {
            return;
        }
        List<ScopedStops> candidates = scopedByObject.get(key);
        if (candidates == null) {
            return;
        }
        for (ScopedStops scoped : candidates) {
            if (scoped.stopRefs().contains(stopId) || !Collections.disjoint(scoped.stopRefs(), ancestors)) {
                collect(List.of(scoped.situation()), call.getWindowStart(), call.getWindowEnd(), matched);
            }
        }
    }
```

Add the imports: `org.entur.vehicles.data.model.AffectedLine`,
`org.entur.vehicles.data.model.AffectedStop`,
`org.entur.vehicles.data.model.AffectedVehicleJourney`, `java.util.Collections`, `java.util.HashSet`.

Leave `match(EstimatedTimetableUpdate)` completely alone, including the `!matched.isEmpty()`
shortcut. Add this comment above that shortcut so the next reader knows it was considered:

```java
        // Still safe with scoped matching: a scoped match requires an entry naming this call's
        // journey, dated journey or line, and every such ref is also in the flat id sets that
        // drove the journey-level pass above. So match(Call) cannot return a situation the
        // journey did not already match, and skipping the loop on an empty result loses nothing.
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=SituationMatcherTest`
Expected: PASS, all 19 pre-existing methods included.

Then the join tests that exercise the same rule through Spring:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest='ApplicationGraphQlSchemaTests,SituationTriggeredRepublisherTest,TimetableGraphQLTests'`
Expected: PASS with no edits to those files. They use top-level (unscoped) stops throughout, so
the scoped rule must not change their outcome.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/model/Call.java \
        src/main/java/org/entur/vehicles/data/EstimatedTimetableUpdate.java \
        src/main/java/org/entur/vehicles/data/SituationMatcher.java \
        src/test/java/org/entur/vehicles/data/SituationMatcherTest.java
git commit -m "fix: scope a situation's stops to the journey it named them under"
```

---

### Task 5: Cut the affected span out of a pattern's geometry

Pure geometry, no Spring, no GraphQL. `PlannedDataset` exposes the stitched vertex array; `PolylineSlicer` projects the stops onto it and returns the tightest window touching all of them.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java:243-267`
- Create: `src/main/java/org/entur/vehicles/service/planned/PolylineSlicer.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PolylineSlicerTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `PlannedDataset.stitchedGeometry(String journeyPatternId) -> int[]` (empty array when the pattern is unknown or carries no geometry; never null)
  - `PolylineSlicer.slice(int[] geometry, List<Location> stops, double maxSnapMeters) -> PointsOnLink` (null when it cannot be computed)

**Why `PolylineSlicer` lives in `service.planned`:** `Polyline.stitch` and `Polyline.encode` are
package-private there. Do not widen their visibility.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/service/planned/PolylineSlicerTest.java`:

```java
package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PolylineSlicerTest {

    private static final double MAX_SNAP_METERS = 500;

    /**
     * A due-north line of points one thousandth of a degree apart - about 111 m per step, so
     * every vertex is well outside the snap radius of its neighbours and a stop placed on one
     * can only project onto that one.
     */
    private static int[] straightLine(int points) {
        int[] geometry = new int[points * 2];
        for (int i = 0; i < points; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        return geometry;
    }

    private static Location at(int[] geometry, int index) {
        return new Location(geometry[index * 2 + 1] / 1e6, geometry[index * 2] / 1e6);
    }

    @Test
    public void testSpansTheTwoNamedStops() {
        int[] geometry = straightLine(6);

        PointsOnLink result = PolylineSlicer.slice(
                geometry, List.of(at(geometry, 1), at(geometry, 4)), MAX_SNAP_METERS);

        assertThat(result).isNotNull();
        // Vertices 1..4 inclusive.
        assertThat(result.getLength()).isEqualTo(4);
        assertThat(result.getPoints()).isNotEmpty();
    }

    /**
     * The producer named the ends and skipped a middle stop. The span is still continuous -
     * first to last - rather than two disconnected pieces.
     */
    @Test
    public void testAGapBetweenNamedStopsStillYieldsOneContinuousSpan() {
        int[] geometry = straightLine(8);

        PointsOnLink result = PolylineSlicer.slice(
                geometry, List.of(at(geometry, 1), at(geometry, 6)), MAX_SNAP_METERS);

        assertThat(result).isNotNull();
        assertThat(result.getLength()).isEqualTo(6);
    }

    /**
     * The case projection is weakest at. The route runs north then doubles straight back, so
     * every stop has two candidate vertices. Nearest-per-stop would be free to pick one from
     * the outbound leg and one from the return and span nearly the whole route; the tightest
     * window must stay on one leg.
     */
    @Test
    public void testAnOutAndBackRouteChoosesTheTightestWindow() {
        int[] out = straightLine(6);
        int[] geometry = new int[out.length * 2];
        System.arraycopy(out, 0, geometry, 0, out.length);
        for (int i = 0; i < 6; i++) {
            geometry[out.length + i * 2] = out[(5 - i) * 2];
            geometry[out.length + i * 2 + 1] = out[(5 - i) * 2 + 1];
        }

        PointsOnLink result = PolylineSlicer.slice(
                geometry, List.of(at(out, 1), at(out, 3)), MAX_SNAP_METERS);

        assertThat(result).isNotNull();
        // Vertices 1..3 of the outbound leg - three points. Picking one stop from the
        // outbound leg and the other from the return would give eight or more.
        assertThat(result.getLength()).isEqualTo(3);
    }

    @Test
    public void testAStopBeyondTheSnapRadiusSuppressesTheWholeSpan() {
        int[] geometry = straightLine(6);
        Location farAway = new Location(11.0, 59.0);

        assertThat(PolylineSlicer.slice(geometry, List.of(at(geometry, 1), farAway), MAX_SNAP_METERS))
                .isNull();
    }

    @Test
    public void testAStopWithoutCoordinatesSuppressesTheWholeSpan() {
        int[] geometry = straightLine(6);

        assertThat(PolylineSlicer.slice(geometry, Arrays.asList(at(geometry, 1), null), MAX_SNAP_METERS))
                .isNull();
    }

    @Test
    public void testASingleStopIsAPointNotASegment() {
        int[] geometry = straightLine(6);

        assertThat(PolylineSlicer.slice(geometry, List.of(at(geometry, 2)), MAX_SNAP_METERS)).isNull();
    }

    @Test
    public void testTwoStopsOnTheSameVertexYieldNothing() {
        int[] geometry = straightLine(6);

        assertThat(PolylineSlicer.slice(geometry, List.of(at(geometry, 2), at(geometry, 2)), MAX_SNAP_METERS))
                .isNull();
    }

    @Test
    public void testEmptyOrTooShortGeometryYieldsNothing() {
        List<Location> stops = new ArrayList<>(List.of(new Location(10.0, 59.0), new Location(10.0, 59.001)));

        assertThat(PolylineSlicer.slice(new int[0], stops, MAX_SNAP_METERS)).isNull();
        assertThat(PolylineSlicer.slice(new int[]{59_000_000, 10_000_000}, stops, MAX_SNAP_METERS)).isNull();
        assertThat(PolylineSlicer.slice(null, stops, MAX_SNAP_METERS)).isNull();
    }
}
```

Note `new Location(longitude, latitude)` - longitude first. That constructor argument order is
easy to get backwards; the `at(...)` helper above is written to match it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=PolylineSlicerTest`
Expected: FAIL - compilation error, `cannot find symbol: class PolylineSlicer`.

- [ ] **Step 3: Expose the stitched geometry**

In `PlannedDataset`, add the method and refactor `buildPointsOnLink` onto it:

```java
    /**
     * The route geometry of a journey pattern as interleaved lat/lon microdegrees, stitched
     * from its service links. Empty when the pattern is unknown or none of its links carry
     * geometry; never null.
     * <p>
     * Deliberately not cached: this is built per request on the situation path, which is rare
     * next to ingestion, and caching the arrays alongside the encoded strings in
     * {@code patternPolylines} would double that memory for no steady-state gain.
     */
    public int[] stitchedGeometry(String journeyPatternId) {
        if (journeyPatternId == null) {
            return new int[0];
        }
        String[] linkIds = patternLinks.get(journeyPatternId);
        if (linkIds == null) {
            return new int[0];
        }
        List<int[]> geometries = new ArrayList<>(linkIds.length);
        for (String linkId : linkIds) {
            int[] geometry = linkGeometry.get(linkId);
            if (geometry != null && geometry.length > 0) {
                geometries.add(geometry);
            }
        }
        return Polyline.stitch(geometries);
    }

    private PointsOnLink buildPointsOnLink(String journeyPatternId) {
        int[] stitched = stitchedGeometry(journeyPatternId);
        if (stitched.length == 0) {
            return NO_GEOMETRY;
        }
        PointsOnLink pointsOnLink = new PointsOnLink();
        pointsOnLink.setLength(stitched.length / 2);
        pointsOnLink.setPoints(Polyline.encode(stitched));
        return pointsOnLink;
    }
```

- [ ] **Step 4: Write the slicer**

Create `src/main/java/org/entur/vehicles/service/planned/PolylineSlicer.java`:

```java
package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Cuts the part of a route geometry that a set of stops spans.
 * <p>
 * Stops are located by projection rather than by an extracted stop sequence - see the design
 * spec's Approach section for why, and for the trade-off. Projection alone has no concept of
 * route order, so two rules guard it: candidates are every local minimum within a snap radius
 * rather than the single nearest vertex, and the window chosen is the shortest run of the
 * route touching all of them. A stop that cannot be located suppresses the result entirely
 * rather than silently shrinking the span.
 * <p>
 * Coordinates are interleaved lat/lon microdegrees, as everywhere else in this package.
 */
public final class PolylineSlicer {

    /** Guards step 5 against a pathological geometry: the nearest candidates are kept. */
    private static final int MAX_CANDIDATES_PER_STOP = 64;

    private static final double METERS_PER_DEGREE = 111_320.0;

    private PolylineSlicer() {}

    /**
     * @param geometry      interleaved lat/lon microdegrees
     * @param stops         the affected stops' locations, in any order; a null entry means the
     *                      stop has no known location
     * @param maxSnapMeters how far a stop may sit from the geometry and still count as on it
     * @return the span between the first and last stop, or null when it cannot be computed
     */
    public static PointsOnLink slice(int[] geometry, List<Location> stops, double maxSnapMeters) {
        if (geometry == null || geometry.length < 4 || stops == null || stops.size() < 2) {
            return null;
        }
        List<int[]> candidates = new ArrayList<>(stops.size());
        for (Location stop : stops) {
            if (stop == null || stop.getLatitude() == null || stop.getLongitude() == null) {
                return null;
            }
            int[] forStop = candidatesFor(geometry, stop, maxSnapMeters);
            if (forStop.length == 0) {
                return null;
            }
            candidates.add(forStop);
        }

        int[] window = tightestWindow(candidates);
        if (window[0] >= window[1]) {
            return null;
        }

        int[] cut = Arrays.copyOfRange(geometry, window[0] * 2, window[1] * 2 + 2);
        PointsOnLink pointsOnLink = new PointsOnLink();
        pointsOnLink.setLength(cut.length / 2);
        pointsOnLink.setPoints(Polyline.encode(cut));
        return pointsOnLink;
    }

    /**
     * Every local minimum of the distance to this stop that lies within the snap radius, in
     * ascending index order. A plateau of equal distances contributes its first index only.
     */
    private static int[] candidatesFor(int[] geometry, Location stop, double maxSnapMeters) {
        int points = geometry.length / 2;
        double latitude = stop.getLatitude();
        double longitude = stop.getLongitude();
        // Hoisted out of the loop, so the scan itself needs neither trigonometry nor a square
        // root: distances are compared squared.
        double lonScale = METERS_PER_DEGREE * Math.cos(Math.toRadians(latitude));
        double limitSquared = maxSnapMeters * maxSnapMeters;

        double[] distances = new double[points];
        for (int i = 0; i < points; i++) {
            double dLat = (geometry[i * 2] / 1e6 - latitude) * METERS_PER_DEGREE;
            double dLon = (geometry[i * 2 + 1] / 1e6 - longitude) * lonScale;
            distances[i] = dLat * dLat + dLon * dLon;
        }

        List<Integer> minima = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            boolean risingFromTheLeft = i == 0 || distances[i] < distances[i - 1];
            boolean notRisingToTheRight = i == points - 1 || distances[i] <= distances[i + 1];
            if (risingFromTheLeft && notRisingToTheRight && distances[i] <= limitSquared) {
                minima.add(i);
            }
        }
        if (minima.size() > MAX_CANDIDATES_PER_STOP) {
            minima.sort(Comparator.comparingDouble(index -> distances[index]));
            minima = new ArrayList<>(minima.subList(0, MAX_CANDIDATES_PER_STOP));
            minima.sort(Comparator.naturalOrder());
        }

        int[] result = new int[minima.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = minima.get(i);
        }
        return result;
    }

    /**
     * The shortest index window containing one candidate from every stop - the classic
     * smallest-range-covering-k-lists problem, by k-way merge. This is what keeps an
     * out-and-back route from spanning both legs.
     */
    private static int[] tightestWindow(List<int[]> candidates) {
        int lists = candidates.size();
        int[] pointer = new int[lists];
        // Safe despite the mutable pointer array: a list's key changes only while that list is
        // outside the heap, between the poll that removed it and the add that returns it.
        PriorityQueue<Integer> heap =
                new PriorityQueue<>(Comparator.comparingInt(i -> candidates.get(i)[pointer[i]]));

        int high = Integer.MIN_VALUE;
        for (int i = 0; i < lists; i++) {
            heap.add(i);
            high = Math.max(high, candidates.get(i)[0]);
        }

        int bestLow = candidates.get(heap.peek())[0];
        int bestHigh = high;

        while (true) {
            int list = heap.poll();
            int low = candidates.get(list)[pointer[list]];
            if (high - low < bestHigh - bestLow) {
                bestLow = low;
                bestHigh = high;
            }
            pointer[list]++;
            if (pointer[list] == candidates.get(list).length) {
                break;
            }
            high = Math.max(high, candidates.get(list)[pointer[list]]);
            heap.add(list);
        }
        return new int[]{bestLow, bestHigh};
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=PolylineSlicerTest`
Expected: PASS - all eight methods.

Then check the refactored `buildPointsOnLink` did not change existing geometry behaviour:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest='PlannedData*Test,Polyline*Test,VehicleGraphQLTests'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java \
        src/main/java/org/entur/vehicles/service/planned/PolylineSlicer.java \
        src/test/java/org/entur/vehicles/service/planned/PolylineSlicerTest.java
git commit -m "feat: cut the span a set of stops covers out of a route geometry"
```

---

### Task 6: Expose the pairing and the affected polyline through GraphQL

Nothing added so far is reachable by a client. This task adds the schema types, the lazy geometry resolver and the config property.

**Files:**
- Modify: `src/main/resources/graphql/vehicle-updates.graphqls:370-378`
- Create: `src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java`

**Interfaces:**
- Consumes: `Affects.getVehicleJourneys()`, `Affects.getAffectedLines()`, `AffectedVehicleJourney.getStops()` (Task 1); the mapper populating them (Task 2); `PlannedDataset.stitchedGeometry` and `PolylineSlicer.slice` (Task 5).
- Produces: the `affects { vehicleJourneys { … affectedPointsOnLink } affectedLines { … } }` selection.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java`. Follow the
class's existing convention of identifiers unique to the test - the repositories are shared
singletons that are never reset between methods:

```java
    private static final String AFFECTED_GEOMETRY_SITUATION = "TST:SituationNumber:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_DSJ = "TST:DatedServiceJourney:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_SJ = "TST:ServiceJourney:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_PATTERN = "TST:JourneyPattern:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_LINK = "TST:ServiceLink:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_STOP_1 = "NSR:StopPlace:affected-geometry-probe-1";
    private static final String AFFECTED_GEOMETRY_STOP_2 = "NSR:StopPlace:affected-geometry-probe-2";
    private static final String AFFECTED_GEOMETRY_OPT_OUT_SITUATION = "TST:SituationNumber:affected-geometry-opt-out";
    private static final String AFFECTED_GEOMETRY_OPT_OUT_DSJ = "TST:DatedServiceJourney:affected-geometry-opt-out";

    /**
     * The whole feature, through the real schema: stops nested under a dated journey come back
     * as that journey's own entry, and the polyline is cut to the span between them rather than
     * being the journey's full geometry.
     */
    @Test
    void anAffectedJourneysStopsResolveWithAPolylineCutToTheirSpan() {
        // Six points about 111 m apart, due north.
        int[] geometry = new int[12];
        for (int i = 0; i < 6; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        when(plannedDataService.current()).thenReturn(new PlannedDataset.Builder()
                .addServiceLink(AFFECTED_GEOMETRY_LINK, geometry)
                .addJourneyPattern(AFFECTED_GEOMETRY_PATTERN, List.of(AFFECTED_GEOMETRY_LINK))
                .addServiceJourney(AFFECTED_GEOMETRY_SJ, AFFECTED_GEOMETRY_PATTERN)
                .build());
        when(nsrService.getStop(AFFECTED_GEOMETRY_STOP_1))
                .thenReturn(new StopPoint(AFFECTED_GEOMETRY_STOP_1, "One", new Location(10.0, 59.001)));
        when(nsrService.getStop(AFFECTED_GEOMETRY_STOP_2))
                .thenReturn(new StopPoint(AFFECTED_GEOMETRY_STOP_2, "Two", new Location(10.0, 59.004)));

        situationRepository.add(situationAffectingJourneyAtStops(
                AFFECTED_GEOMETRY_SITUATION, AFFECTED_GEOMETRY_DSJ,
                AFFECTED_GEOMETRY_STOP_1, AFFECTED_GEOMETRY_STOP_2));

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects {
                      vehicleJourneys {
                        datedServiceJourney { id }
                        stops { stop { id } stopConditions }
                        affectedPointsOnLink { length points }
                      }
                    }
                  }
                }
                """.formatted(AFFECTED_GEOMETRY_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-affected-geometry", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        String datedId = response.field(
                "situations[0].affects.vehicleJourneys[0].datedServiceJourney.id").getValue();
        assertThat(datedId).isEqualTo(AFFECTED_GEOMETRY_DSJ);

        List<Map<String, Object>> stops = response.field(
                "situations[0].affects.vehicleJourneys[0].stops").getValue();
        assertThat(stops).hasSize(2);
        assertThat(stops.get(0).get("stopConditions")).isEqualTo(List.of("startPoint"));

        // Vertices 1..4: the span between the two stops, not the pattern's full six points.
        Number length = response.field(
                "situations[0].affects.vehicleJourneys[0].affectedPointsOnLink.length").getValue();
        assertThat(length.intValue()).isEqualTo(4);
    }

    /**
     * The mirror of the existing opt-out test for journey situations: the cut is lazy, so a
     * client that selects the stops but not the polyline must not make the resolver touch the
     * planned dataset at all.
     */
    @Test
    void anAffectsSelectionWithoutTheGeometryFieldDoesNoGeometryWork() {
        situationRepository.add(situationAffectingJourneyAtStops(
                AFFECTED_GEOMETRY_OPT_OUT_SITUATION, AFFECTED_GEOMETRY_OPT_OUT_DSJ,
                AFFECTED_GEOMETRY_STOP_1, AFFECTED_GEOMETRY_STOP_2));
        // The mapper resolves the dated journey through the dataset at ingest, so only what
        // happens from here on is under test.
        clearInvocations(plannedDataService);

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects { vehicleJourneys { stops { stop { id } } } }
                  }
                }
                """.formatted(AFFECTED_GEOMETRY_OPT_OUT_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-affected-geometry-opt-out", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();
        verify(plannedDataService, never()).current();
    }
```

Add this fixture builder next to the class's other `situation…` helpers. Like them it returns a
`PtSituationElementRecord`, which is what `situationRepository.add(...)` takes:

```java
    private static PtSituationElementRecord situationAffectingJourneyAtStops(String situationNumber,
                                                                            String datedServiceJourneyId,
                                                                            String... stopRefs) {
        List<AffectedStopPointRecord> stopPoints = new ArrayList<>();
        for (String stopRef : stopRefs) {
            AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
            stopPoint.setStopPointRef(stopRef);
            stopPoint.setStopPointNames(List.of());
            stopPoint.setStopConditions(List.of("startPoint"));
            stopPoints.add(stopPoint);
        }
        StopPointsRecord stops = new StopPointsRecord();
        stops.setStopPoints(stopPoints);
        AffectedRouteRecord route = new AffectedRouteRecord();
        route.setStopPoints(stops);
        route.setSections(List.of());

        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setVehicleJourneyRefs(List.of());
        journey.setDatedVehicleJourneyRefs(List.of(datedServiceJourneyId));
        journey.setRoutes(List.of(route));

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of());
        affects.setStopPoints(List.of());
        affects.setStopPlaces(List.of());
        affects.setVehicleJourneys(List.of(journey));

        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusHours(1).toString());
        record.setReportType("general");
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        record.setAffects(affects);
        return record;
    }
```

The dated journey must resolve to `AFFECTED_GEOMETRY_SJ`, or the controller has no pattern to
cut: `SituationMapper.resolveDatedServiceJourney` goes through `ServiceJourneyService`, which
reads the very dataset this test stubs. So the `PlannedDataset.Builder` chain above needs two
more lines before `.build()` - the signatures are
`addOperatingDay(String id, String calendarDate)` and
`addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId)`:

```java
                .addOperatingDay("TST:OperatingDay:affected-geometry-probe", "2026-09-03")
                .addDatedServiceJourney(AFFECTED_GEOMETRY_DSJ, AFFECTED_GEOMETRY_SJ,
                        "TST:OperatingDay:affected-geometry-probe")
```

Stub the dataset **before** `situationRepository.add(...)`: the mapper resolves the dated
journey at ingest, not at query time, so a dataset installed afterwards is too late.

Add the imports the file is missing: `org.entur.avro.realtime.siri.model.AffectedRouteRecord`,
`org.entur.avro.realtime.siri.model.StopPointsRecord`, `org.entur.vehicles.data.model.Location`,
and `static org.mockito.Mockito.clearInvocations`. `never` and `verify` are already imported.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=ApplicationGraphQlSchemaTests`
Expected: FAIL - the GraphQL document is invalid, "Field 'vehicleJourneys' in type 'Affects' is
undefined".

- [ ] **Step 3: Extend the schema**

In `src/main/resources/graphql/vehicle-updates.graphqls`, add to `type Affects` (leave every
existing field exactly as it is):

```graphql
    # The journey-to-stops pairing SIRI carries: a situation is often tagged on a journey
    # AND the stops it is affected at, and is only relevant to a traveller whose trip visits
    # them. Every journey listed here also appears in serviceJourneys/datedServiceJourneys,
    # and every line in lines, so existing clients see no change.
    vehicleJourneys: [AffectedVehicleJourney]
    affectedLines: [AffectedLine]
```

and add the three types and the enum next to the existing `type Affects`:

```graphql
type AffectedVehicleJourney {
    serviceJourney: ServiceJourney
    datedServiceJourney: DatedServiceJourney
    # Context for display. A journey entry is scoped to the journey it names, never to this line.
    line: Line
    operator: Operator
    stops: [AffectedStop]
    # The part of this journey's geometry between the first and last affected stop.
    # Null when the journey has no pattern geometry, or any affected stop cannot be located
    # on it - a partial span would draw a confident line over the wrong part of the route.
    affectedPointsOnLink: PointsOnLink
}

type AffectedLine {
    line: Line
    # A line has many journey patterns, so a line entry carries no geometry.
    stops: [AffectedStop]
}

type AffectedStop {
    stop: Stop
    stopConditions: [StopConditionEnumeration]
}

enum StopConditionEnumeration {
    exceptionalStop
    destination
    notStopping
    requestStop
    startPoint
}
```

- [ ] **Step 4: Write the geometry controller**

Create `src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java`:

```java
package org.entur.vehicles.graphql;

import org.entur.vehicles.data.model.AffectedStop;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.entur.vehicles.service.planned.PolylineSlicer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code AffectedVehicleJourney.affectedPointsOnLink} lazily, mirroring
 * {@link ServiceJourneyGeometryController}: a client that does not select the field pays
 * nothing, and situations are never enriched with geometry at ingest.
 */
@Controller
public class AffectedGeometryController {

    private final PlannedDataService plannedDataService;
    private final NSRService nsrService;
    private final double maxSnapMeters;

    public AffectedGeometryController(@Autowired PlannedDataService plannedDataService,
                                      @Autowired NSRService nsrService,
                                      @Value("${vehicle.situations.affected-geometry.max-snap-meters:500}")
                                      double maxSnapMeters) {
        this.plannedDataService = plannedDataService;
        this.nsrService = nsrService;
        this.maxSnapMeters = maxSnapMeters;
    }

    @SchemaMapping(typeName = "AffectedVehicleJourney", field = "affectedPointsOnLink")
    public PointsOnLink affectedPointsOnLink(AffectedVehicleJourney journey) {
        List<AffectedStop> stops = journey.getStops();
        if (stops.size() < 2) {
            return null;
        }
        String serviceJourneyId = serviceJourneyIdOf(journey);
        if (serviceJourneyId == null) {
            return null;
        }
        PlannedDataset dataset = plannedDataService.current();
        int[] geometry = dataset.stitchedGeometry(dataset.journeyPatternOf(serviceJourneyId));
        if (geometry.length < 4) {
            return null;
        }
        List<Location> locations = new ArrayList<>(stops.size());
        for (AffectedStop stop : stops) {
            locations.add(locationOf(stop));
        }
        return PolylineSlicer.slice(geometry, locations, maxSnapMeters);
    }

    /**
     * A dated journey the planned data knows already carries its service journey - the mapper
     * resolved it at ingest - so this needs no further lookup. A journey named by a bare
     * service journey ref is used directly.
     */
    private String serviceJourneyIdOf(AffectedVehicleJourney journey) {
        if (journey.getDatedServiceJourney() != null
                && journey.getDatedServiceJourney().getServiceJourney() != null) {
            return journey.getDatedServiceJourney().getServiceJourney().getId();
        }
        return journey.getServiceJourney() != null ? journey.getServiceJourney().getId() : null;
    }

    /** Null when the stop is unknown to NSR or NSR lookup is disabled - the slicer then yields null. */
    private Location locationOf(AffectedStop stop) {
        if (stop.getStop() == null || stop.getStop().getId() == null) {
            return null;
        }
        StopPoint resolved = nsrService.getStop(stop.getStop().getId());
        return resolved != null ? resolved.getLocation() : null;
    }
}
```

- [ ] **Step 5: Add the property default**

In `src/main/resources/application.properties`, next to the other `vehicle.*` entries:

```properties
# How far an affected stop may sit from a journey's route geometry and still be considered on
# it, when cutting Situation.affects.vehicleJourneys[].affectedPointsOnLink. Sized for rail: a
# large stop place's centroid sits a few hundred metres from the track it is measured against.
vehicle.situations.affected-geometry.max-snap-meters=500
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test -Dtest=ApplicationGraphQlSchemaTests`
Expected: PASS. `contextLoads` passing is itself meaningful here - it proves every new schema
field wires to a resolver or getter.

- [ ] **Step 7: Run the whole suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test`
Expected: BUILD SUCCESS, no failures, no errors. Paste the `Tests run:` summary line into the
commit discussion rather than asserting success without it.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/graphql/vehicle-updates.graphqls \
        src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java \
        src/main/resources/application.properties \
        src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java
git commit -m "feat: expose affected journeys, their stops and the affected polyline"
```

---

## After the plan

Update `CLAUDE.md`'s GraphQL section to mention that `Situation.affects` carries per-journey and
per-line entries and that `affectedPointsOnLink` resolves lazily, in the same style as the
existing `ServiceJourney.pointsOnLink` note. `CLAUDE.md` is currently untracked in this repo -
check with the repository owner before committing it.
