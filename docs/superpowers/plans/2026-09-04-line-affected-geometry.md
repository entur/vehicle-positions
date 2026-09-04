# Line-level Affected Geometry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `AffectedLine` an `affectedPointsOnLink` polyline - the span between its affected stops on the first journey pattern they project onto, or the line's longest pattern's whole route when the situation names no stops.

**Architecture:** `PlannedDataset` gains one derived map, line id -> that line's distinct journey patterns ordered by vertex count descending. `AffectedGeometryController` gains a second `@SchemaMapping` that walks that list, reusing the request-scoped stitched-geometry memo and `PolylineSlicer` the journey resolver already uses. No ingest change, no matching change, no new model state.

**Tech Stack:** Java 25+, Spring Boot / Spring GraphQL 2.x, JUnit 5, AssertJ, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-09-04-line-level-affected-geometry-design.md`

## Global Constraints

- Run Maven as `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test ...`; the shell's default `java` is Zulu 17 and the superpom enforcer requires JDK 25+.
- **No `PlannedDataSnapshot.FORMAT_VERSION` bump.** The new index is derived inside `PlannedDataset.Builder.build()` from maps the builder already holds, so a snapshot replay recomputes it. If a change here makes you want to persist it, stop and re-open the spec.
- The new GraphQL field is **additive and nullable**. No existing field, type or null semantic changes.
- Every failure mode is a null field, never an exception and never a partial span.
- Ordering must be deterministic across reloads: vertex count descending, then pattern id ascending.
- Follow the surrounding code's comment style: explain *why* a non-obvious choice was made, not what the line does.
- Work on branch `line-affected-geometry` (already checked out; it carries the spec commit).

---

### Task 1: `PlannedDataset` line -> ordered journey patterns

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java` (fields ~line 47, constructor ~line 50-70, accessors ~line 87, `Builder.build()` ~line 476-490 and the `new PlannedDataset(...)` call ~line 550-560)
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDatasetLinePatternsTest.java` (create)

**Interfaces:**
- Consumes: the existing builder API - `addServiceLink(String, int[])`, `addJourneyPattern(String, List<String>)`, `addLine(String, String, String)`, `addServiceJourney(String, String, String)`.
- Produces: `public String[] journeyPatternsOf(String lineId)` on `PlannedDataset` - the line's distinct journey patterns, most vertices first, ties by id ascending; patterns with no geometry excluded; an empty array (never null) for an unknown line or a null argument. Task 2 and Task 3 call this.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/service/planned/PlannedDatasetLinePatternsTest.java`:

```java
package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line -> journey pattern index exists so a situation tagged on a line can be given one
 * representative shape. Ordering is by vertex count descending (the most complete shape of the
 * line first), ties by id, so the representative a client sees is stable across a nightly reload
 * and changes only when the export's patterns change.
 */
class PlannedDatasetLinePatternsTest {

    /**
     * Two links: a six-point one and a four-point one, in different places. Two patterns on one
     * line, plus a pattern with no geometry at all - which can never yield a span and is therefore
     * excluded rather than wasting a slice attempt at query time.
     */
    private static PlannedDataset dataset() {
        int[] sixPoints = new int[12];
        for (int i = 0; i < 6; i++) {
            sixPoints[i * 2] = 59_000_000 + i * 1_000;
            sixPoints[i * 2 + 1] = 10_000_000;
        }
        int[] fourPoints = new int[8];
        for (int i = 0; i < 4; i++) {
            fourPoints[i * 2] = 59_000_000 + i * 1_000;
            fourPoints[i * 2 + 1] = 11_000_000;
        }
        return new PlannedDataset.Builder()
                .addServiceLink("TST:ServiceLink:long", sixPoints)
                .addServiceLink("TST:ServiceLink:short", fourPoints)
                .addServiceLink("TST:ServiceLink:nogeom", null)
                .addJourneyPattern("TST:JourneyPattern:long", List.of("TST:ServiceLink:long"))
                .addJourneyPattern("TST:JourneyPattern:short", List.of("TST:ServiceLink:short"))
                .addJourneyPattern("TST:JourneyPattern:none", List.of("TST:ServiceLink:nogeom"))
                .addLine("TST:Line:1", "One", "1")
                .addLine("TST:Line:2", "Two", "2")
                .addServiceJourney("TST:ServiceJourney:1a", "TST:JourneyPattern:short", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:1b", "TST:JourneyPattern:long", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:1c", "TST:JourneyPattern:long", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:1d", "TST:JourneyPattern:none", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:2a", "TST:JourneyPattern:long", "TST:Line:2")
                .build();
    }

    @Test
    void ordersALinesDistinctPatternsByVertexCountDescending() {
        assertThat(dataset().journeyPatternsOf("TST:Line:1"))
                .containsExactly("TST:JourneyPattern:long", "TST:JourneyPattern:short");
    }

    @Test
    void aPatternServedByManyJourneysAppearsOnce() {
        // 1b and 1c share the long pattern; 2a puts it on a second line too.
        assertThat(dataset().journeyPatternsOf("TST:Line:2"))
                .containsExactly("TST:JourneyPattern:long");
    }

    @Test
    void patternsWithoutGeometryAreExcluded() {
        assertThat(dataset().journeyPatternsOf("TST:Line:1"))
                .doesNotContain("TST:JourneyPattern:none");
    }

    /** Ties must not depend on HashMap iteration order, or the representative would drift on reload. */
    @Test
    void equalVertexCountsAreOrderedById() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceLink("TST:ServiceLink:x", new int[]{59_000_000, 10_000_000, 59_001_000, 10_000_000})
                .addJourneyPattern("TST:JourneyPattern:b", List.of("TST:ServiceLink:x"))
                .addJourneyPattern("TST:JourneyPattern:a", List.of("TST:ServiceLink:x"))
                .addLine("TST:Line:ties", "Ties", "T")
                .addServiceJourney("TST:ServiceJourney:tb", "TST:JourneyPattern:b", "TST:Line:ties")
                .addServiceJourney("TST:ServiceJourney:ta", "TST:JourneyPattern:a", "TST:Line:ties")
                .build();

        assertThat(dataset.journeyPatternsOf("TST:Line:ties"))
                .containsExactly("TST:JourneyPattern:a", "TST:JourneyPattern:b");
    }

    @Test
    void anUnknownOrNullLineYieldsAnEmptyArrayRatherThanNull() {
        assertThat(dataset().journeyPatternsOf("TST:Line:unknown")).isEmpty();
        assertThat(dataset().journeyPatternsOf(null)).isEmpty();
    }

    /**
     * A journey whose LineRef the export never declares as a Line is already absent from
     * lineServiceJourneys; the pattern index is built from the same resolved pairing, so the two
     * cannot disagree about what a line contains.
     */
    @Test
    void journeysOnAnUndeclaredLineContributeNoPatterns() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceLink("TST:ServiceLink:y", new int[]{59_000_000, 10_000_000, 59_001_000, 10_000_000})
                .addJourneyPattern("TST:JourneyPattern:y", List.of("TST:ServiceLink:y"))
                .addServiceJourney("TST:ServiceJourney:y", "TST:JourneyPattern:y", "TST:Line:undeclared")
                .build();

        assertThat(dataset.journeyPatternsOf("TST:Line:undeclared")).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest=PlannedDatasetLinePatternsTest`
Expected: compilation failure - `cannot find symbol: method journeyPatternsOf(String)`.

- [ ] **Step 3: Add the field, constructor parameter and accessor**

In `PlannedDataset`, next to `lineServiceJourneys`:

```java
    /** Line id -> the distinct journey patterns of its journeys, most vertices first. */
    private final Map<String, String[]> lineJourneyPatterns;
```

Add the matching constructor parameter immediately after `lineServiceJourneys` (and the assignment), then, next to the other accessors:

```java
    /** Shared with every caller and never copied - callers must not mutate it. */
    private static final String[] NO_PATTERNS = new String[0];

    /**
     * The line's distinct journey patterns, most vertices first and ties by id, so the pattern a
     * line-level situation is drawn on is stable across reloads. Patterns without usable geometry
     * are already excluded, so a caller can slice each in turn without re-checking. Empty, never
     * null, for a line the export does not declare.
     * <p>
     * The returned array is shared; callers must treat it as read-only.
     */
    public String[] journeyPatternsOf(String lineId) {
        if (lineId == null) {
            return NO_PATTERNS;
        }
        String[] patterns = lineJourneyPatterns.get(lineId);
        return patterns != null ? patterns : NO_PATTERNS;
    }
```

- [ ] **Step 4: Build the index in `Builder.build()`**

In `build()`, after the loop that fills `journeysByLine` and `lineServiceJourneys` (the pattern ids are canonicalised earlier in the method, so the strings stored here are the shared instances):

```java
            // Vertex count per pattern, summed from its links rather than stitched: ordering only
            // needs the size of the shape, and stitching every pattern at build time would cost
            // an array copy per pattern for a number we can add up.
            Map<String, Integer> patternVertices = new HashMap<>(patternLinks.size());
            for (Map.Entry<String, String[]> e : patternLinks.entrySet()) {
                int vertices = 0;
                for (String linkId : e.getValue()) {
                    int[] geometry = linkGeometry.get(linkId);
                    if (geometry != null) {
                        vertices += geometry.length / 2;
                    }
                }
                patternVertices.put(e.getKey(), vertices);
            }

            // A line's shape is the shape of its journeys' patterns. Ordered by vertex count
            // descending so the most complete variant is the representative, ties by id so a
            // reload does not silently move the representative around.
            Comparator<String> byShapeThenId = Comparator
                    .comparingInt((String patternId) -> patternVertices.getOrDefault(patternId, 0))
                    .reversed()
                    .thenComparing(Comparator.naturalOrder());
            Map<String, String[]> lineJourneyPatterns = new HashMap<>(journeysByLine.size());
            int linesWithoutGeometry = 0;
            for (Map.Entry<String, List<String>> e : journeysByLine.entrySet()) {
                TreeSet<String> patterns = new TreeSet<>(byShapeThenId);
                for (String serviceJourneyId : e.getValue()) {
                    String patternId = serviceJourneyPattern.get(serviceJourneyId);
                    // "" is the builder's marker for a journey whose JourneyPatternRef was absent;
                    // a zero vertex count is a pattern that can never yield a span.
                    if (patternId != null && !patternId.isEmpty()
                            && patternVertices.getOrDefault(patternId, 0) > 0) {
                        patterns.add(patternId);
                    }
                }
                if (patterns.isEmpty()) {
                    linesWithoutGeometry++;
                } else {
                    lineJourneyPatterns.put(e.getKey(), patterns.toArray(new String[0]));
                }
            }
            if (linesWithoutGeometry > 0) {
                LOG.info("Planned data: {} of {} lines have no journey pattern with geometry - "
                        + "line-level situations on them resolve no polyline.",
                        linesWithoutGeometry, journeysByLine.size());
            }
```

Pass `Map.copyOf(lineJourneyPatterns)` to the `new PlannedDataset(...)` call, directly after `Map.copyOf(lineServiceJourneys)`. Add `java.util.Comparator` and `java.util.TreeSet` to the imports if they are not already there (both are).

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest=PlannedDatasetLinePatternsTest`
Expected: PASS, 6 tests.

- [ ] **Step 6: Run the whole planned-data test package for regressions**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest='org.entur.vehicles.service.planned.*Test'`
Expected: PASS. `PlannedDataServiceSnapshotTest` passing unchanged is the check that snapshot replay still produces an equivalent dataset without a format bump.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java \
        src/test/java/org/entur/vehicles/service/planned/PlannedDatasetLinePatternsTest.java
git commit -m "Index a line's journey patterns by shape size"
```

---

### Task 2: `AffectedLine.affectedPointsOnLink`

**Files:**
- Modify: `src/main/resources/graphql/vehicle-updates.graphqls:403-407` (the `AffectedLine` type)
- Modify: `src/main/java/org/entur/vehicles/data/model/AffectedLine.java:10-12` (class javadoc only)
- Modify: `src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java` (class javadoc, new `@SchemaMapping` method)
- Test: `src/test/java/org/entur/vehicles/graphql/AffectedGeometryLineTest.java` (create)

**Interfaces:**
- Consumes: `PlannedDataset.journeyPatternsOf(String)` from Task 1; the existing `PlannedDataset.pointsOnLink(String)`, `PlannedDataset.stitchedGeometry(String)`, `PolylineSlicer.slice(int[], List<Location>, double)`, and the private `memo(GraphQLContext)` / `locationOf(AffectedStop)` helpers already in the controller.
- Produces: `public PointsOnLink affectedPointsOnLink(AffectedLine affectedLine, GraphQLContext context)` on `AffectedGeometryController`. Task 3 adds a cap inside it; Task 4 drives it through the real schema.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/graphql/AffectedGeometryLineTest.java`. A separate file from `AffectedGeometryControllerTest` because its fixture is a two-pattern line rather than a single shared pattern, and mixing the two would make every test read the wrong fixture:

```java
package org.entur.vehicles.graphql;

import graphql.GraphQLContext;
import org.entur.vehicles.data.model.AffectedLine;
import org.entur.vehicles.data.model.AffectedStop;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A line has many journey patterns, so a line-level situation is drawn on a representative: the
 * first pattern its affected stops actually locate on, tried longest-first. The fixture is a line
 * with two disjoint patterns - a six-point one along lon 10 and a four-point one along lon 11,
 * about 57 km apart - so "the stops fit the second pattern, not the first" is a real geometric
 * fact here rather than something the test asserts by construction.
 */
class AffectedGeometryLineTest {

    private static final String LINE = "TST:Line:affected-line";
    private static final String LONG_PATTERN = "TST:JourneyPattern:affected-line-long";
    private static final String SHORT_PATTERN = "TST:JourneyPattern:affected-line-short";
    private static final String LONG_LINK = "TST:ServiceLink:affected-line-long";
    private static final String SHORT_LINK = "TST:ServiceLink:affected-line-short";
    private static final String JOURNEY_ON_LONG = "TST:ServiceJourney:affected-line-long";
    private static final String JOURNEY_ON_SHORT = "TST:ServiceJourney:affected-line-short";
    private static final String STOP_ON_LONG_1 = "NSR:StopPlace:affected-line-long-1";
    private static final String STOP_ON_LONG_2 = "NSR:StopPlace:affected-line-long-2";
    private static final String STOP_ON_SHORT_1 = "NSR:StopPlace:affected-line-short-1";
    private static final String STOP_ON_SHORT_2 = "NSR:StopPlace:affected-line-short-2";
    private static final String STOP_NOWHERE = "NSR:StopPlace:affected-line-nowhere";
    private static final String STOP_UNKNOWN_TO_NSR = "NSR:StopPlace:affected-line-unknown";

    private PlannedDataset dataset;
    private PlannedDataService plannedDataService;
    private NSRService nsrService;
    private AffectedGeometryController controller;

    @BeforeEach
    void setUp() {
        int[] longGeometry = new int[12];
        for (int i = 0; i < 6; i++) {
            longGeometry[i * 2] = 59_000_000 + i * 1_000;
            longGeometry[i * 2 + 1] = 10_000_000;
        }
        int[] shortGeometry = new int[8];
        for (int i = 0; i < 4; i++) {
            shortGeometry[i * 2] = 59_000_000 + i * 1_000;
            shortGeometry[i * 2 + 1] = 11_000_000;
        }
        dataset = Mockito.spy(new PlannedDataset.Builder()
                .addServiceLink(LONG_LINK, longGeometry)
                .addServiceLink(SHORT_LINK, shortGeometry)
                .addJourneyPattern(LONG_PATTERN, List.of(LONG_LINK))
                .addJourneyPattern(SHORT_PATTERN, List.of(SHORT_LINK))
                .addLine(LINE, "Affected line", "31")
                .addServiceJourney(JOURNEY_ON_LONG, LONG_PATTERN, LINE)
                .addServiceJourney(JOURNEY_ON_SHORT, SHORT_PATTERN, LINE)
                .build());

        plannedDataService = Mockito.mock(PlannedDataService.class);
        when(plannedDataService.current()).thenReturn(dataset);

        nsrService = Mockito.mock(NSRService.class);
        when(nsrService.getStop(STOP_ON_LONG_1))
                .thenReturn(new StopPoint(STOP_ON_LONG_1, "Long one", new Location(10.0, 59.001)));
        when(nsrService.getStop(STOP_ON_LONG_2))
                .thenReturn(new StopPoint(STOP_ON_LONG_2, "Long two", new Location(10.0, 59.004)));
        when(nsrService.getStop(STOP_ON_SHORT_1))
                .thenReturn(new StopPoint(STOP_ON_SHORT_1, "Short one", new Location(11.0, 59.000)));
        when(nsrService.getStop(STOP_ON_SHORT_2))
                .thenReturn(new StopPoint(STOP_ON_SHORT_2, "Short two", new Location(11.0, 59.002)));
        when(nsrService.getStop(STOP_NOWHERE))
                .thenReturn(new StopPoint(STOP_NOWHERE, "Nowhere", new Location(5.0, 62.0)));
        // Unknown to NSR - also what every stop looks like when NSR lookup is disabled.
        when(nsrService.getStop(STOP_UNKNOWN_TO_NSR)).thenReturn(null);

        controller = new AffectedGeometryController(plannedDataService, nsrService, 500);
    }

    /**
     * A line affected as a whole gets its longest pattern's entire route - and exactly the value
     * ServiceJourney.pointsOnLink serves for a journey on that pattern, so a client drawing both
     * never sees two encodings of one shape.
     */
    @Test
    void aLineAffectedAsAWholeResolvesToItsLongestPatternsEntireRoute() {
        PointsOnLink resolved = controller.affectedPointsOnLink(
                affectedLine(LINE), GraphQLContext.newContext().build());

        assertThat(resolved).isNotNull();
        assertThat(resolved.getLength()).isEqualTo(6);
        assertThat(resolved).isSameAs(dataset.pointsOnLink(LONG_PATTERN));
    }

    /** The case the whole design exists for: the stops fit the shorter, second-tried pattern. */
    @Test
    void picksTheFirstPatternTheAffectedStopsActuallyLocateOn() {
        PointsOnLink resolved = controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_SHORT_1, STOP_ON_SHORT_2),
                GraphQLContext.newContext().build());

        assertThat(resolved).isNotNull();
        // Vertices 0..2 of the four-point pattern, not any part of the six-point one.
        assertThat(resolved.getLength()).isEqualTo(3);
        verify(dataset).stitchedGeometry(LONG_PATTERN);
        verify(dataset).stitchedGeometry(SHORT_PATTERN);
    }

    /** Stops on the longest pattern are answered by the first attempt - the later one is never stitched. */
    @Test
    void stopsOnTheLongestPatternStopTheSearchAtTheFirstPattern() {
        PointsOnLink resolved = controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_ON_LONG_2),
                GraphQLContext.newContext().build());

        assertThat(resolved).isNotNull();
        assertThat(resolved.getLength()).isEqualTo(4);
        verify(dataset, Mockito.never()).stitchedGeometry(SHORT_PATTERN);
    }

    @Test
    void aLineWithNoSpanToDrawResolvesToNull() {
        GraphQLContext context = GraphQLContext.newContext().build();

        assertThat(controller.affectedPointsOnLink(affectedLine(LINE, STOP_ON_LONG_1), context))
                .withFailMessage("a single named stop is a point, not a span")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                affectedLine("TST:Line:unknown", STOP_ON_LONG_1, STOP_ON_LONG_2), context))
                .withFailMessage("a line the planned data does not know has no pattern to cut")
                .isNull();
        assertThat(controller.affectedPointsOnLink(affectedLine("TST:Line:unknown"), context))
                .withFailMessage("nor when it is affected as a whole")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_NOWHERE), context))
                .withFailMessage("a stop off every pattern suppresses the span entirely")
                .isNull();
        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_UNKNOWN_TO_NSR), context))
                .withFailMessage("a stop NSR cannot locate suppresses the span, as on the journey field")
                .isNull();
    }

    /**
     * The one place the line field is more useful than the journey field: a line affected as a
     * whole needs no stop coordinates, so it still draws when NSR lookup is disabled.
     */
    @Test
    void aLineAffectedAsAWholeNeedsNoStopCoordinates() {
        NSRService withoutLookup = Mockito.mock(NSRService.class);
        when(withoutLookup.getStop(Mockito.anyString())).thenReturn(null);
        AffectedGeometryController withoutNsr = new AffectedGeometryController(
                plannedDataService, withoutLookup, 500);

        assertThat(withoutNsr.affectedPointsOnLink(affectedLine(LINE), GraphQLContext.newContext().build()))
                .isNotNull();
    }

    /** A line entry failing the cheap checks must not touch the planned data at all. */
    @Test
    void aLineWithTooFewStopsNeverReadsTheDataset() {
        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1), GraphQLContext.newContext().build())).isNull();

        Mockito.verifyNoInteractions(dataset);
    }

    /**
     * A situation naming a line and journeys on that line is the common shape - a line-wide
     * closure lists the line and its cancelled journeys. The line resolver shares the journey
     * resolver's per-request memo, so their shared pattern is stitched once for the request.
     */
    @Test
    void aLineAndAJourneyOnTheSamePatternStitchItOnceForTheRequest() {
        GraphQLContext context = GraphQLContext.newContext().build();

        assertThat(controller.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_LONG_1, STOP_ON_LONG_2), context)).isNotNull();
        assertThat(controller.affectedPointsOnLink(
                journeyOn(JOURNEY_ON_LONG, STOP_ON_LONG_1, STOP_ON_LONG_2), context)).isNotNull();

        verify(dataset, times(1)).stitchedGeometry(LONG_PATTERN);
    }

    private static AffectedLine affectedLine(String lineRef, String... stopRefs) {
        return new AffectedLine(new Line(lineRef), stops(stopRefs));
    }

    private static AffectedVehicleJourney journeyOn(String serviceJourneyId, String... stopRefs) {
        return new AffectedVehicleJourney(
                new ServiceJourney(serviceJourneyId), null, null, null, stops(stopRefs));
    }

    private static List<AffectedStop> stops(String... stopRefs) {
        List<AffectedStop> stops = new ArrayList<>();
        for (String stopRef : stopRefs) {
            stops.add(new AffectedStop(new StopPoint(stopRef), List.of()));
        }
        return stops;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest=AffectedGeometryLineTest`
Expected: compilation failure - `affectedPointsOnLink(AffectedLine, GraphQLContext)` does not exist.

- [ ] **Step 3: Add the schema field**

In `src/main/resources/graphql/vehicle-updates.graphqls`, replace the `AffectedLine` type:

```graphql
type AffectedLine {
    line: Line
    stops: [AffectedStop]
    # The part of this line's geometry the situation affects. A line has many journey
    # patterns, so this is one representative pattern rather than the line as a whole:
    # with stops, the span between the first and last affected stop on the first pattern
    # they locate on; without stops - meaning the line is affected as a whole - the entire
    # route of the line's longest pattern.
    # Null when the line has no pattern geometry, when exactly one stop is affected (a
    # point is not a span), or when the affected stops locate on none of its patterns.
    affectedPointsOnLink: PointsOnLink
}
```

- [ ] **Step 4: Update the `AffectedLine` class javadoc**

Replace the "Unlike a journey entry this carries no geometry" sentence in `src/main/java/org/entur/vehicles/data/model/AffectedLine.java`:

```java
/**
 * One line a situation names, with the stops it is affected at. A line has many journey
 * patterns, so its geometry is one representative pattern's, resolved lazily from the line ref
 * by {@code AffectedGeometryController} and never stored here - so equals/hashCode, and with
 * them the republisher's change detection, stay a function of the line ref and the stops alone.
 */
```

- [ ] **Step 5: Add the resolver**

In `AffectedGeometryController`, add the import for `org.entur.vehicles.data.model.AffectedLine` and this method after the journey resolver:

```java
    /**
     * The affected span of a line: the span between its affected stops on the first of the line's
     * journey patterns they locate on, tried longest-first, or - when the situation names no stops
     * - the whole route of its longest pattern.
     * <p>
     * A line has many patterns and the dataset holds no stop sequence for any of them (the export
     * parse keeps service links only), so "which pattern serves these stops" cannot be asked
     * directly. {@link PolylineSlicer} already answers the geometric form of that question: it
     * yields null unless every stop snaps within {@code maxSnapMeters}, which makes first fit a
     * search for the pattern the stops are actually on rather than a guess.
     * <p>
     * Null in exactly the cases the journey field is null for: one affected stop, a line without
     * pattern geometry, or stops that locate on none of its patterns.
     */
    @SchemaMapping(typeName = "AffectedLine", field = "affectedPointsOnLink")
    public PointsOnLink affectedPointsOnLink(AffectedLine affectedLine, GraphQLContext context) {
        List<AffectedStop> stops = affectedLine.getStops();
        String lineRef = affectedLine.getLine() != null ? affectedLine.getLine().getLineRef() : null;
        if (lineRef == null || stops.size() == 1) {
            return null;
        }
        PlannedDataset dataset = plannedDataService.current();
        String[] patterns = dataset.journeyPatternsOf(lineRef);
        if (patterns.length == 0) {
            return null;
        }
        if (stops.isEmpty()) {
            // Affected as a whole, so the affected part is the whole route - of the longest
            // pattern, which journeyPatternsOf orders first. From the dataset's own encoded
            // cache, so this is the identical value ServiceJourney.pointsOnLink serves.
            return dataset.pointsOnLink(patterns[0]);
        }
        List<Location> locations = new ArrayList<>(stops.size());
        for (AffectedStop stop : stops) {
            locations.add(locationOf(stop));
        }
        for (String journeyPatternId : patterns) {
            int[] geometry = memo(context).computeIfAbsent(journeyPatternId, dataset::stitchedGeometry);
            if (geometry.length < 4) {
                continue;
            }
            PointsOnLink sliced = PolylineSlicer.slice(geometry, locations, maxSnapMeters);
            if (sliced != null) {
                return sliced;
            }
        }
        return null;
    }
```

- [ ] **Step 6: Extend the controller's class javadoc**

The class javadoc opens "Resolves `AffectedVehicleJourney.affectedPointsOnLink` lazily". Widen it to name both resolvers and say why they share a class:

```java
 * Resolves {@code AffectedVehicleJourney.affectedPointsOnLink} and
 * {@code AffectedLine.affectedPointsOnLink} lazily, mirroring
 * {@link ServiceJourneyGeometryController}: a client that does not select the field pays
 * nothing, and situations are never enriched with geometry at ingest.
 * <p>
 * Both live here to share the per-request memo below: a line-wide closure names the line and
 * the journeys on it, and those resolve over the same handful of journey patterns.
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest=AffectedGeometryLineTest`
Expected: PASS, 8 tests.

- [ ] **Step 8: Run the journey resolver's tests for regressions**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest=AffectedGeometryControllerTest`
Expected: PASS, unchanged.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/graphql/vehicle-updates.graphqls \
        src/main/java/org/entur/vehicles/data/model/AffectedLine.java \
        src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java \
        src/test/java/org/entur/vehicles/graphql/AffectedGeometryLineTest.java
git commit -m "Resolve a representative polyline for line-level situations"
```

---

### Task 3: Bound the pattern search

**Files:**
- Modify: `src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java` (constructor, resolver loop)
- Modify: `src/main/resources/application.properties:62` (add the new property below the existing one)
- Test: `src/test/java/org/entur/vehicles/graphql/AffectedGeometryLineTest.java` (add one test and a second controller instance)

**Interfaces:**
- Consumes: the resolver from Task 2.
- Produces: constructor `AffectedGeometryController(PlannedDataService, NSRService, double maxSnapMeters, int maxLinePatterns)`. **This changes an existing 3-argument constructor, so `AffectedGeometryControllerTest` and any other direct instantiation must be updated in this task.**

- [ ] **Step 1: Write the failing test**

Add to `AffectedGeometryLineTest`:

```java
    /**
     * A line with many variants and stops that fit a late one must not stitch its way through all
     * of them on every request. The cap is a cost bound, not a correctness rule: longest-first
     * ordering means what it drops is the least representative shapes.
     */
    @Test
    void stopsFittingOnlyAPatternBeyondTheCapResolveToNull() {
        AffectedGeometryController capped = new AffectedGeometryController(
                plannedDataService, nsrService, 500, 1);

        assertThat(capped.affectedPointsOnLink(
                affectedLine(LINE, STOP_ON_SHORT_1, STOP_ON_SHORT_2),
                GraphQLContext.newContext().build()))
                .withFailMessage("only the longest pattern may be tried when the cap is 1")
                .isNull();
        verify(dataset, Mockito.never()).stitchedGeometry(SHORT_PATTERN);
    }

    /** The cap bounds the search, never the whole-line case - that reads one pattern by index. */
    @Test
    void theCapDoesNotAffectALineAffectedAsAWhole() {
        AffectedGeometryController capped = new AffectedGeometryController(
                plannedDataService, nsrService, 500, 1);

        assertThat(capped.affectedPointsOnLink(affectedLine(LINE), GraphQLContext.newContext().build()))
                .isNotNull();
    }
```

`plannedDataService` and `nsrService` are already fields from Task 2. Update every constructor call in this file to the new arity: `setUp()`'s to `(plannedDataService, nsrService, 500, 25)`, and `aLineAffectedAsAWholeNeedsNoStopCoordinates`'s to `(plannedDataService, withoutLookup, 500, 25)`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest=AffectedGeometryLineTest`
Expected: compilation failure - no 4-argument constructor.

- [ ] **Step 3: Add the constructor parameter**

In `AffectedGeometryController`:

```java
    private final int maxLinePatterns;

    public AffectedGeometryController(@Autowired PlannedDataService plannedDataService,
                                      @Autowired NSRService nsrService,
                                      @Value("${vehicle.situations.affected-geometry.max-snap-meters:500}")
                                      double maxSnapMeters,
                                      @Value("${vehicle.situations.affected-geometry.max-line-patterns:25}")
                                      int maxLinePatterns) {
        this.plannedDataService = plannedDataService;
        this.nsrService = nsrService;
        this.maxSnapMeters = maxSnapMeters;
        this.maxLinePatterns = maxLinePatterns;
    }
```

- [ ] **Step 4: Bound the loop**

Replace the `for (String journeyPatternId : patterns)` loop header in the line resolver with an indexed loop, and say why the bound is there:

```java
        // A line with dozens of variants whose stops fit none of them would otherwise stitch every
        // one of them per request. Ordered longest-first, so the cap drops the least representative
        // shapes rather than arbitrary ones.
        int limit = Math.min(patterns.length, maxLinePatterns);
        for (int i = 0; i < limit; i++) {
            String journeyPatternId = patterns[i];
            ...
        }
```

- [ ] **Step 5: Update the other constructor call sites**

In `AffectedGeometryControllerTest.setUp()`, change `new AffectedGeometryController(plannedDataService, nsrService, 500)` to `new AffectedGeometryController(plannedDataService, nsrService, 500, 25)`. Then grep for any remaining 3-argument call - `grep -rn "new AffectedGeometryController" src/` - and update it; the Spring context builds its own instance from the annotations, so production code needs no change.

- [ ] **Step 6: Add the property**

In `src/main/resources/application.properties`, below `vehicle.situations.affected-geometry.max-snap-meters=500`:

```properties
# Most journey patterns to try when locating a line-level situation's stops on the line's
# geometry. Ordered longest-first, so this drops the least representative variants.
vehicle.situations.affected-geometry.max-line-patterns=25
```

- [ ] **Step 7: Run both geometry test classes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest='AffectedGeometry*Test'`
Expected: PASS, 10 tests in `AffectedGeometryLineTest` and the existing 6 in `AffectedGeometryControllerTest`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java \
        src/main/resources/application.properties \
        src/test/java/org/entur/vehicles/graphql/AffectedGeometryLineTest.java \
        src/test/java/org/entur/vehicles/graphql/AffectedGeometryControllerTest.java
git commit -m "Bound the line-level pattern search"
```

---

### Task 4: The feature through the real schema

**Files:**
- Modify: `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java` (new constants near the other fixture constants ~line 149, one new test, one new fixture builder near `situationAffectingJourneyAtStops` ~line 1169)

**Interfaces:**
- Consumes: everything from Tasks 1-3, wired by Spring rather than constructed by hand.
- Produces: nothing further tasks depend on.

- [ ] **Step 1: Write the failing test**

Add the fixture constants alongside the existing `AFFECTED_GEOMETRY_*` ones:

```java
    private static final String AFFECTED_LINE_GEOMETRY_SITUATION = "TST:SituationNumber:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_LINE = "TST:Line:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_LINK = "TST:ServiceLink:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_PATTERN = "TST:JourneyPattern:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_SJ = "TST:ServiceJourney:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_STOP_1 = "NSR:StopPlace:affected-line-geometry-1";
    private static final String AFFECTED_LINE_GEOMETRY_STOP_2 = "NSR:StopPlace:affected-line-geometry-2";
```

Add the fixture builder next to `situationAffectingJourneyAtStops` - the line-level shape, which nests the stops under `Networks/AffectedNetwork/AffectedLine/Routes` rather than under a journey:

```java
    /** The line-level shape of a tagged situation: AffectedLine with its stops, no journeys. */
    private static PtSituationElementRecord situationAffectingLineAtStops(String situationNumber,
                                                                         String lineRef,
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

        AffectedLineRecord affectedLine = new AffectedLineRecord();
        affectedLine.setLineRef(lineRef);
        affectedLine.setRoutes(List.of(route));

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setAffectedLines(List.of(affectedLine));
        network.setAffectedOperators(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        affects.setStopPoints(List.of());
        affects.setStopPlaces(List.of());
        affects.setVehicleJourneys(List.of());

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

Add the test next to `anAffectedJourneysStopsResolveWithAPolylineCutToTheirSpan`:

```java
    /**
     * The line-level half of the feature, through the real schema: a situation tagged on a line
     * and two of its stops resolves to a span on one of the line's journey patterns, not to null
     * and not to the pattern's full geometry.
     */
    @Test
    void anAffectedLinesStopsResolveWithAPolylineCutToTheirSpan() {
        // Six points about 111 m apart, due north.
        int[] geometry = new int[12];
        for (int i = 0; i < 6; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        when(plannedDataService.current()).thenReturn(new PlannedDataset.Builder()
                .addServiceLink(AFFECTED_LINE_GEOMETRY_LINK, geometry)
                .addJourneyPattern(AFFECTED_LINE_GEOMETRY_PATTERN, List.of(AFFECTED_LINE_GEOMETRY_LINK))
                .addLine(AFFECTED_LINE_GEOMETRY_LINE, "Affected line", "31")
                .addServiceJourney(AFFECTED_LINE_GEOMETRY_SJ, AFFECTED_LINE_GEOMETRY_PATTERN,
                        AFFECTED_LINE_GEOMETRY_LINE)
                .build());
        when(nsrService.getStop(AFFECTED_LINE_GEOMETRY_STOP_1)).thenReturn(
                new StopPoint(AFFECTED_LINE_GEOMETRY_STOP_1, "One", new Location(10.0, 59.001)));
        when(nsrService.getStop(AFFECTED_LINE_GEOMETRY_STOP_2)).thenReturn(
                new StopPoint(AFFECTED_LINE_GEOMETRY_STOP_2, "Two", new Location(10.0, 59.004)));

        situationRepository.add(situationAffectingLineAtStops(
                AFFECTED_LINE_GEOMETRY_SITUATION, AFFECTED_LINE_GEOMETRY_LINE,
                AFFECTED_LINE_GEOMETRY_STOP_1, AFFECTED_LINE_GEOMETRY_STOP_2));

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects {
                      affectedLines {
                        line { id }
                        stops { stop { id } }
                        affectedPointsOnLink { length points }
                      }
                    }
                  }
                }
                """.formatted(AFFECTED_LINE_GEOMETRY_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-affected-line-geometry", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        String lineId = response.field("situations[0].affects.affectedLines[0].line.id").getValue();
        assertThat(lineId).isEqualTo(AFFECTED_LINE_GEOMETRY_LINE);
        // Vertices 1..4: the span between the two stops, not the pattern's full six points.
        Number length = response.field(
                "situations[0].affects.affectedLines[0].affectedPointsOnLink.length").getValue();
        assertThat(length.intValue()).isEqualTo(4);
    }
```

Add imports for `AffectedLineRecord` and `AffectedNetworkRecord` from `org.entur.avro.realtime.siri.model` if the file does not already have them.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test -Dtest=ApplicationGraphQlSchemaTests#anAffectedLinesStopsResolveWithAPolylineCutToTheirSpan`
Expected: FAIL. Tasks 1-3 make it pass, so if it already passes, check the fixture actually reaches the resolver (a wrong line ref resolves to null, which is also "no error").

- [ ] **Step 3: Make it pass**

No production change should be needed - Tasks 1-3 implement the behaviour. If it fails, the likely causes, in order: the `plannedDataService` mock is a full `@MockitoBean` whose `current()` must be stubbed *inside* this test (the `@BeforeEach` stubs `PlannedDataset.EMPTY`); `nsrService` stops must be stubbed or `locationOf` returns null and the slicer suppresses the span; and the situation must be open (`situationAffectingLineAtStops` sets `creationTime` and no validity periods, matching the journey fixture).

- [ ] **Step 4: Run the full test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -o test`
Expected: PASS. Read `target/surefire-reports/*.xml` for the counts.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java
git commit -m "Cover line-level affected geometry through the schema"
```

---

## Done when

- `AffectedLine.affectedPointsOnLink` is in the schema, resolves a span or a whole route, and is null in every case the spec's error table lists.
- `vehicle.situations.affected-geometry.max-line-patterns` is in `application.properties`.
- `mvn -o test` is green, `PlannedDataServiceSnapshotTest` included, with no `FORMAT_VERSION` change in the diff.
- The spec's Risks table still describes the shipped behaviour; if first fit was changed during implementation, update the spec in the same branch.
