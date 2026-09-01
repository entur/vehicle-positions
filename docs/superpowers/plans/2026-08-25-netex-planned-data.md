# NeTEx Planned Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every JourneyPlanner GraphQL lookup (lines, operators, service journeys, dated service journeys, route geometry) with an in-memory dataset extracted from the aggregated NeTEx export, loaded at startup and reloaded nightly.

**Architecture:** A StAX extractor streams each zip entry and keeps only seven element types in compact maps inside an immutable `PlannedDataset`. `PlannedDataService` owns an `AtomicReference<PlannedDataset>`, loads it in `@PostConstruct` (gating readiness) and swaps a fresh one in from a nightly `@Scheduled` job. `LineService`, `OperatorService` and `ServiceJourneyService` keep their public methods and become O(1) lookups over the current dataset, so the repositories don't change. The JourneyPlanner client and all its config are deleted.

**Tech Stack:** Java (JDK 25+, records, switch expressions), Spring Boot (`@Scheduled`, `@Value`, `@PostConstruct`), `javax.xml.stream` (JDK StAX, no new dependency), `java.util.zip.ZipFile`, Micrometer/Prometheus, JUnit 5 + AssertJ + Mockito (already in `spring-boot-starter-test`), Maven.

**Spec:** `docs/superpowers/specs/2026-08-25-netex-planned-data-design.md`

## Global Constraints

- Build/test with the newer JDK: prefix every `mvn` with `JAVA_HOME=$(/usr/libexec/java_home -v 25+)` (the shell default is JDK 17, which the superpom rejects).
- All new production code lives in package `org.entur.vehicles.service.planned` under `src/main/java/org/entur/vehicles/service/planned/`; tests mirror it under `src/test/java/`.
- Coordinates are stored as `int` microdegrees (`Math.round(value * 1e6)`), interleaved `[lat, lon, lat, lon, ...]`.
- Polyline encoding is Google encoded polyline at 5-decimal precision; `PointsOnLink.length` = number of points.
- Misses must return exactly today's fallbacks: `new Line(ref)`, `null` operator, `new ServiceJourney(id)`, `new DatedServiceJourney(id, new ServiceJourney(id))`.
- Never throw from a lookup. Never throw from a nightly reload. Throw from the startup load.
- Config keys: `vehicle.planned.data.enabled` (default `false`), `vehicle.planned.data.url` (default `https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip`), `vehicle.planned.data.reload.cron` (default `0 0 8 * * *`, zone `Europe/Oslo`).
- Metric names use the existing `app.vehicles.` prefix in `PrometheusMetricsService`.
- Commit messages: plain, no Claude attribution (see the project's `commit` skill).
- The test fixture zip `rb_goa-aggregated-netex.zip` (1.3 MB) is checked in at `src/test/resources/netex/rb_goa-aggregated-netex.zip`. No test may download anything.

---

## File structure

| File | Responsibility |
|---|---|
| `service/planned/DatedJourneyRef.java` | Record: `(serviceJourneyId, operatingDate)` |
| `service/planned/PlannedDataset.java` | Immutable snapshot + `Builder` + `Stats`; typed lookups; lazy `pointsOnLink` |
| `service/planned/Polyline.java` | Pure functions: stitch link geometries, encode as Google polyline |
| `service/planned/PosListParser.java` | Pure function: `gis:posList` text → `int[]` microdegrees |
| `service/planned/NetexPlannedDataExtractor.java` | StAX pass over one XML stream into a `Builder` |
| `service/planned/PlannedDataLoader.java` | Zip → dataset: iterates entries, per-entry error isolation, zero-line-file check |
| `service/planned/PlannedDataLoadException.java` | Checked exception for a failed load |
| `service/planned/PlannedDataService.java` | Download, startup load, scheduled reload, shrunk guard, swap, miss-counted lookups |
| `service/LineService.java`, `OperatorService.java`, `ServiceJourneyService.java` | Rewritten as thin lookups |
| `metrics/PrometheusMetricsService.java` | Remove JourneyPlanner counters; add planned-data metrics |
| `Application.java` | `@EnableScheduling` |
| `repository/VehicleRepository.java`, `TimetableRepository.java`, `SituationMapper.java` | Drop `ExecutionException` handling around the lookups |
| `service/JourneyPlannerGraphQLClient.java`, `service/graphql/Data.java`, `service/graphql/Response.java` | Deleted |
| `application.properties`, `local_config/application.properties`, `src/test/resources/application.properties`, helm `values.yaml`, `env/values-*.yaml`, `templates/configmap.yaml`, `templates/deployment.yaml`, `CLAUDE.md` | Config + docs |

---

### Task 1: `PlannedDataset`, `DatedJourneyRef`, `Builder`, `Stats`

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/planned/DatedJourneyRef.java`
- Create: `src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDatasetTest.java`

**Interfaces:**
- Produces:
  - `record DatedJourneyRef(String serviceJourneyId, String operatingDate)` — `operatingDate` may be `null`.
  - `PlannedDataset.Builder` with `addOperator(String id, String name)`, `addLine(String id, String name, String publicCode)`, `addServiceLink(String id, int[] geometry)`, `addJourneyPattern(String id, List<String> serviceLinkIds)`, `addServiceJourney(String id, String journeyPatternId)`, `addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId)`, `addOperatingDay(String id, String calendarDate)`, and `PlannedDataset build()`.
  - `PlannedDataset` with `Operator operator(String id)`, `Line line(String id)`, `boolean hasServiceJourney(String id)`, `String journeyPatternOf(String serviceJourneyId)`, `DatedJourneyRef datedServiceJourney(String id)`, `int serviceJourneyCount()`, `Stats stats()`, and constant `PlannedDataset.EMPTY`. (`pointsOnLink` is added in Task 2.)
  - `record PlannedDataset.Stats(int operators, int lines, int serviceJourneys, int datedServiceJourneys, int journeyPatterns, int serviceLinks, int duplicateIds, int unresolvedPatternRefs, int unresolvedLinkRefs, int unresolvedServiceJourneyRefs, int unresolvedOperatingDayRefs)`

- [ ] **Step 1: Write the failing tests**

```java
package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PlannedDatasetTest {

    @Test
    public void lookupsResolveWhatTheBuilderWasGiven() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addOperator("GOA:Operator:GOA", "Go-Ahead Nordic AS")
                .addLine("GOA:Line:59", "Jærbanen", "L5")
                .addServiceLink("GOA:ServiceLink:1", new int[]{58000000, 5000000, 58001000, 5001000})
                .addJourneyPattern("GOA:JourneyPattern:1", List.of("GOA:ServiceLink:1"))
                .addServiceJourney("GOA:ServiceJourney:1", "GOA:JourneyPattern:1")
                .addOperatingDay("GOA:OperatingDay:2024-01-20", "2024-01-20")
                .addDatedServiceJourney("GOA:DatedServiceJourney:1", "GOA:ServiceJourney:1", "GOA:OperatingDay:2024-01-20")
                .build();

        assertThat(dataset.operator("GOA:Operator:GOA").getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(dataset.line("GOA:Line:59").getLineName()).isEqualTo("Jærbanen");
        assertThat(dataset.line("GOA:Line:59").getPublicCode()).isEqualTo("L5");
        assertThat(dataset.hasServiceJourney("GOA:ServiceJourney:1")).isTrue();
        assertThat(dataset.journeyPatternOf("GOA:ServiceJourney:1")).isEqualTo("GOA:JourneyPattern:1");
        assertThat(dataset.datedServiceJourney("GOA:DatedServiceJourney:1"))
                .isEqualTo(new DatedJourneyRef("GOA:ServiceJourney:1", "2024-01-20"));
        assertThat(dataset.serviceJourneyCount()).isEqualTo(1);
    }

    @Test
    public void missesReturnNull() {
        PlannedDataset dataset = new PlannedDataset.Builder().build();

        assertThat(dataset.operator("X:Operator:1")).isNull();
        assertThat(dataset.line("X:Line:1")).isNull();
        assertThat(dataset.hasServiceJourney("X:ServiceJourney:1")).isFalse();
        assertThat(dataset.journeyPatternOf("X:ServiceJourney:1")).isNull();
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNull();
        assertThat(dataset.operator(null)).isNull();
    }

    @Test
    public void emptyDatasetHasNothing() {
        assertThat(PlannedDataset.EMPTY.serviceJourneyCount()).isZero();
        assertThat(PlannedDataset.EMPTY.line("X:Line:1")).isNull();
    }

    @Test
    public void datedServiceJourneyWithUnknownOperatingDayKeepsTheServiceJourneyAndCountsIt() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceJourney("X:ServiceJourney:1", "X:JourneyPattern:1")
                .addJourneyPattern("X:JourneyPattern:1", List.of())
                .addDatedServiceJourney("X:DatedServiceJourney:1", "X:ServiceJourney:1", "X:OperatingDay:missing")
                .build();

        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1"))
                .isEqualTo(new DatedJourneyRef("X:ServiceJourney:1", null));
        assertThat(dataset.stats().unresolvedOperatingDayRefs()).isEqualTo(1);
    }

    @Test
    public void unresolvedRefsAreCountedNotThrown() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceJourney("X:ServiceJourney:1", "X:JourneyPattern:missing")
                .addJourneyPattern("X:JourneyPattern:1", List.of("X:ServiceLink:missing"))
                .addDatedServiceJourney("X:DatedServiceJourney:1", "X:ServiceJourney:missing", "X:OperatingDay:missing")
                .build();

        PlannedDataset.Stats stats = dataset.stats();
        assertThat(stats.unresolvedPatternRefs()).isEqualTo(1);
        assertThat(stats.unresolvedLinkRefs()).isEqualTo(1);
        assertThat(stats.unresolvedServiceJourneyRefs()).isEqualTo(1);
        assertThat(stats.unresolvedOperatingDayRefs()).isEqualTo(1);
        // The SJ is still known, even though its pattern is not
        assertThat(dataset.hasServiceJourney("X:ServiceJourney:1")).isTrue();
        // A DSJ whose SJ is unknown is still resolvable to that SJ id
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1").serviceJourneyId())
                .isEqualTo("X:ServiceJourney:missing");
    }

    @Test
    public void duplicateIdsLastOneWinsAndAreCounted() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addLine("X:Line:1", "first", "1")
                .addLine("X:Line:1", "second", "1")
                .build();

        assertThat(dataset.line("X:Line:1").getLineName()).isEqualTo("second");
        assertThat(dataset.stats().duplicateIds()).isEqualTo(1);
        assertThat(dataset.stats().lines()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDatasetTest`
Expected: compilation failure — `PlannedDataset` and `DatedJourneyRef` do not exist.

- [ ] **Step 3: Implement `DatedJourneyRef` and `PlannedDataset`**

`src/main/java/org/entur/vehicles/service/planned/DatedJourneyRef.java`:

```java
package org.entur.vehicles.service.planned;

/**
 * What a DatedServiceJourney resolves to: the service journey it dates, and the calendar
 * date (ISO yyyy-MM-dd) of its operating day. {@code operatingDate} is null when the
 * OperatingDayRef could not be resolved in the export.
 */
public record DatedJourneyRef(String serviceJourneyId, String operatingDate) {
}
```

`src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java`:

```java
package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.PointsOnLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One immutable snapshot of the planned data this service needs from the NeTEx export.
 * Built once per load by {@link Builder}; replaced wholesale on reload, never mutated.
 * <p>
 * The only mutable member is the per-pattern polyline cache, which is filled lazily on
 * first request and dies with the snapshot.
 */
public final class PlannedDataset {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataset.class);

    public static final PlannedDataset EMPTY = new Builder().build();

    private final Map<String, Operator> operators;
    private final Map<String, Line> lines;
    private final Map<String, String> serviceJourneyPattern;
    private final Map<String, DatedJourneyRef> datedServiceJourneys;
    private final Map<String, String[]> patternLinks;
    private final Map<String, int[]> linkGeometry;
    private final ConcurrentHashMap<String, PointsOnLink> patternPolylines = new ConcurrentHashMap<>();
    private final Stats stats;

    private PlannedDataset(Map<String, Operator> operators,
                           Map<String, Line> lines,
                           Map<String, String> serviceJourneyPattern,
                           Map<String, DatedJourneyRef> datedServiceJourneys,
                           Map<String, String[]> patternLinks,
                           Map<String, int[]> linkGeometry,
                           Stats stats) {
        this.operators = operators;
        this.lines = lines;
        this.serviceJourneyPattern = serviceJourneyPattern;
        this.datedServiceJourneys = datedServiceJourneys;
        this.patternLinks = patternLinks;
        this.linkGeometry = linkGeometry;
        this.stats = stats;
    }

    public Operator operator(String id) {
        return id == null ? null : operators.get(id);
    }

    public Line line(String id) {
        return id == null ? null : lines.get(id);
    }

    public boolean hasServiceJourney(String id) {
        return id != null && serviceJourneyPattern.containsKey(id);
    }

    /** The journey pattern id of a service journey, or null if unknown or unresolved. */
    public String journeyPatternOf(String serviceJourneyId) {
        return serviceJourneyId == null ? null : serviceJourneyPattern.get(serviceJourneyId);
    }

    public DatedJourneyRef datedServiceJourney(String id) {
        return id == null ? null : datedServiceJourneys.get(id);
    }

    public int serviceJourneyCount() {
        return serviceJourneyPattern.size();
    }

    public Stats stats() {
        return stats;
    }

    // pointsOnLink(patternId) is added in Task 2.

    /**
     * Counts from one load. The unresolved counters are the summary of dangling refs found
     * while building - they are logged, never thrown.
     */
    public record Stats(int operators,
                        int lines,
                        int serviceJourneys,
                        int datedServiceJourneys,
                        int journeyPatterns,
                        int serviceLinks,
                        int duplicateIds,
                        int unresolvedPatternRefs,
                        int unresolvedLinkRefs,
                        int unresolvedServiceJourneyRefs,
                        int unresolvedOperatingDayRefs) {
    }

    /**
     * Collects raw refs from any number of files in any order and resolves them once in
     * {@link #build()}. Not thread-safe; one builder per load, driven by one thread.
     */
    public static final class Builder {

        private record RawDatedServiceJourney(String serviceJourneyId, String operatingDayId) {}

        private final Map<String, Operator> operators = new HashMap<>();
        private final Map<String, Line> lines = new HashMap<>();
        private final Map<String, String> serviceJourneyPattern = new HashMap<>();
        private final Map<String, RawDatedServiceJourney> rawDatedServiceJourneys = new HashMap<>();
        private final Map<String, String[]> patternLinks = new HashMap<>();
        private final Map<String, int[]> linkGeometry = new HashMap<>();
        private final Map<String, String> operatingDays = new HashMap<>();
        private int duplicateIds = 0;

        public Builder addOperator(String id, String name) {
            Operator operator = new Operator(id);
            operator.setName(name);
            countDuplicate(operators.put(id, operator));
            return this;
        }

        public Builder addLine(String id, String name, String publicCode) {
            Line line = new Line(id, name);
            line.setPublicCode(publicCode);
            countDuplicate(lines.put(id, line));
            return this;
        }

        /** @param geometry interleaved lat/lon microdegrees; null when the link has no gis:posList */
        public Builder addServiceLink(String id, int[] geometry) {
            countDuplicate(linkGeometry.put(id, geometry == null ? new int[0] : geometry));
            return this;
        }

        public Builder addJourneyPattern(String id, List<String> serviceLinkIds) {
            countDuplicate(patternLinks.put(id, serviceLinkIds.toArray(new String[0])));
            return this;
        }

        public Builder addServiceJourney(String id, String journeyPatternId) {
            // Map.copyOf in build() rejects null values; "" is never a real pattern id, so it
            // still counts as unresolved there.
            countDuplicate(serviceJourneyPattern.put(id, journeyPatternId == null ? "" : journeyPatternId));
            return this;
        }

        public Builder addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId) {
            countDuplicate(rawDatedServiceJourneys.put(id, new RawDatedServiceJourney(serviceJourneyId, operatingDayId)));
            return this;
        }

        public Builder addOperatingDay(String id, String calendarDate) {
            countDuplicate(operatingDays.put(id, calendarDate));
            return this;
        }

        private void countDuplicate(Object previous) {
            if (previous != null) {
                duplicateIds++;
            }
        }

        public PlannedDataset build() {
            int unresolvedPatternRefs = 0;
            int unresolvedLinkRefs = 0;
            int unresolvedServiceJourneyRefs = 0;
            int unresolvedOperatingDayRefs = 0;

            // SJ -> pattern: keep the SJ (it is a known id) but count a dangling pattern ref.
            // The entry is kept as-is so journeyPatternOf still returns the (unresolvable) id;
            // pointsOnLink handles an unknown pattern by returning null.
            for (String patternId : serviceJourneyPattern.values()) {
                if (patternId == null || !patternLinks.containsKey(patternId)) {
                    unresolvedPatternRefs++;
                }
            }

            for (String[] links : patternLinks.values()) {
                for (String linkId : links) {
                    if (!linkGeometry.containsKey(linkId)) {
                        unresolvedLinkRefs++;
                    }
                }
            }

            Map<String, DatedJourneyRef> datedServiceJourneys = new HashMap<>(rawDatedServiceJourneys.size());
            for (Map.Entry<String, RawDatedServiceJourney> e : rawDatedServiceJourneys.entrySet()) {
                RawDatedServiceJourney raw = e.getValue();
                if (raw.serviceJourneyId() == null || !serviceJourneyPattern.containsKey(raw.serviceJourneyId())) {
                    unresolvedServiceJourneyRefs++;
                }
                String date = raw.operatingDayId() == null ? null : operatingDays.get(raw.operatingDayId());
                if (date == null) {
                    unresolvedOperatingDayRefs++;
                }
                datedServiceJourneys.put(e.getKey(), new DatedJourneyRef(raw.serviceJourneyId(), date));
            }

            Stats stats = new Stats(
                    operators.size(), lines.size(), serviceJourneyPattern.size(), datedServiceJourneys.size(),
                    patternLinks.size(), linkGeometry.size(), duplicateIds,
                    unresolvedPatternRefs, unresolvedLinkRefs, unresolvedServiceJourneyRefs, unresolvedOperatingDayRefs);

            if (duplicateIds + unresolvedPatternRefs + unresolvedLinkRefs
                    + unresolvedServiceJourneyRefs + unresolvedOperatingDayRefs > 0) {
                LOG.info("Planned data build summary: {}", stats);
            }

            return new PlannedDataset(
                    Map.copyOf(operators),
                    Map.copyOf(lines),
                    Map.copyOf(serviceJourneyPattern),
                    Map.copyOf(datedServiceJourneys),
                    Map.copyOf(patternLinks),
                    Map.copyOf(linkGeometry),
                    stats);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDatasetTest`
Expected: all 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/DatedJourneyRef.java \
        src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java \
        src/test/java/org/entur/vehicles/service/planned/PlannedDatasetTest.java
git commit -m "Add immutable PlannedDataset with builder and build-time ref resolution"
```

---

### Task 2: Geometry — `Polyline` stitch/encode and `PlannedDataset.pointsOnLink`

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/planned/Polyline.java`
- Modify: `src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PolylineTest.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDatasetGeometryTest.java`

**Interfaces:**
- Consumes: `PlannedDataset.Builder`, `PointsOnLink` (`org.entur.vehicles.data.model`, has `setLength(int)`, `setPoints(String)`).
- Produces:
  - `static int[] Polyline.stitch(List<int[]> links)` — concatenates, dropping a duplicated join point.
  - `static String Polyline.encode(int[] microDegrees)` — Google encoded polyline, 5 decimals.
  - `PointsOnLink PlannedDataset.pointsOnLink(String journeyPatternId)` — `null` if the pattern is unknown or yields zero points; cached per pattern.

- [ ] **Step 1: Write the failing tests**

`src/test/java/org/entur/vehicles/service/planned/PolylineTest.java`:

```java
package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PolylineTest {

    /**
     * The worked example from Google's encoded polyline algorithm documentation:
     * (38.5, -120.2), (40.7, -120.95), (43.252, -126.453).
     */
    @Test
    public void encodesGoogleReferenceExample() {
        int[] points = {38_500_000, -120_200_000, 40_700_000, -120_950_000, 43_252_000, -126_453_000};

        assertThat(Polyline.encode(points)).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
    }

    @Test
    public void encodesEmptyAsEmptyString() {
        assertThat(Polyline.encode(new int[0])).isEmpty();
    }

    @Test
    public void roundsMicrodegreesToFiveDecimals() {
        // 59.722150 -> 59.72215 ; 59.722154 -> 59.72215 ; 59.722155 -> 59.72216
        assertThat(Polyline.encode(new int[]{59_722_150, 10_512_689}))
                .isEqualTo(Polyline.encode(new int[]{59_722_154, 10_512_689}));
        assertThat(Polyline.encode(new int[]{59_722_150, 10_512_689}))
                .isNotEqualTo(Polyline.encode(new int[]{59_722_155, 10_512_689}));
    }

    @Test
    public void stitchDropsTheSharedJoinPoint() {
        int[] a = {1, 1, 2, 2};
        int[] b = {2, 2, 3, 3};

        assertThat(Polyline.stitch(List.of(a, b))).containsExactly(1, 1, 2, 2, 3, 3);
    }

    @Test
    public void stitchKeepsBothPointsWhenLinksDoNotTouch() {
        int[] a = {1, 1, 2, 2};
        int[] b = {5, 5, 6, 6};

        assertThat(Polyline.stitch(List.of(a, b))).containsExactly(1, 1, 2, 2, 5, 5, 6, 6);
    }

    @Test
    public void stitchSkipsEmptyLinks() {
        int[] a = {1, 1, 2, 2};
        int[] gap = {};
        int[] b = {2, 2, 3, 3};

        assertThat(Polyline.stitch(List.of(a, gap, b))).containsExactly(1, 1, 2, 2, 3, 3);
        assertThat(Polyline.stitch(List.of(gap))).isEmpty();
        assertThat(Polyline.stitch(List.of())).isEmpty();
    }
}
```

`src/test/java/org/entur/vehicles/service/planned/PlannedDatasetGeometryTest.java`:

```java
package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.PointsOnLink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PlannedDatasetGeometryTest {

    private static PlannedDataset dataset() {
        return new PlannedDataset.Builder()
                .addServiceLink("L:1", new int[]{38_500_000, -120_200_000, 40_700_000, -120_950_000})
                .addServiceLink("L:2", new int[]{40_700_000, -120_950_000, 43_252_000, -126_453_000})
                .addServiceLink("L:nogeom", null)
                .addJourneyPattern("JP:full", List.of("L:1", "L:2"))
                .addJourneyPattern("JP:gap", List.of("L:1", "L:nogeom", "L:2"))
                .addJourneyPattern("JP:dangling", List.of("L:1", "L:missing"))
                .addJourneyPattern("JP:none", List.of("L:nogeom"))
                .build();
    }

    @Test
    public void stitchesLinksInPatternOrderAndEncodes() {
        PointsOnLink points = dataset().pointsOnLink("JP:full");

        assertThat(points.getPoints()).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        assertThat(points.getLength()).isEqualTo(3);
    }

    @Test
    public void linksWithoutGeometryLeaveAGapButDoNotBreakThePattern() {
        assertThat(dataset().pointsOnLink("JP:gap").getLength()).isEqualTo(3);
    }

    @Test
    public void danglingLinkRefsAreSkipped() {
        assertThat(dataset().pointsOnLink("JP:dangling").getLength()).isEqualTo(2);
    }

    @Test
    public void patternWithNoGeometryAtAllYieldsNull() {
        assertThat(dataset().pointsOnLink("JP:none")).isNull();
        assertThat(dataset().pointsOnLink("JP:unknown")).isNull();
        assertThat(dataset().pointsOnLink(null)).isNull();
    }

    @Test
    public void resultIsCachedPerPattern() {
        PlannedDataset dataset = dataset();

        assertThat(dataset.pointsOnLink("JP:full")).isSameAs(dataset.pointsOnLink("JP:full"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='PolylineTest,PlannedDatasetGeometryTest'`
Expected: compilation failure — `Polyline` and `pointsOnLink` do not exist.

- [ ] **Step 3: Implement `Polyline`**

`src/main/java/org/entur/vehicles/service/planned/Polyline.java`:

```java
package org.entur.vehicles.service.planned;

import java.util.List;

/**
 * Pure geometry helpers. Coordinates are interleaved lat/lon in microdegrees (1e-6).
 */
final class Polyline {

    private Polyline() {}

    /**
     * Concatenates link geometries in order. When a link starts exactly where the previous
     * geometry ended, that shared join point is emitted once. Empty links are skipped, so a
     * link without geometry leaves a gap rather than breaking the sequence.
     */
    static int[] stitch(List<int[]> links) {
        int total = 0;
        for (int[] link : links) {
            total += link.length;
        }
        int[] out = new int[total];
        int n = 0;
        for (int[] link : links) {
            if (link.length == 0) {
                continue;
            }
            int from = 0;
            if (n >= 2 && link.length >= 2 && link[0] == out[n - 2] && link[1] == out[n - 1]) {
                from = 2;
            }
            System.arraycopy(link, from, out, n, link.length - from);
            n += link.length - from;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * Google encoded polyline at 5-decimal precision - the format OTP/JourneyPlanner
     * returns in {@code pointsOnLink.points}.
     */
    static String encode(int[] microDegrees) {
        StringBuilder sb = new StringBuilder(microDegrees.length * 3);
        int prevLat = 0;
        int prevLon = 0;
        for (int i = 0; i + 1 < microDegrees.length; i += 2) {
            int lat = toFiveDecimals(microDegrees[i]);
            int lon = toFiveDecimals(microDegrees[i + 1]);
            encodeValue(lat - prevLat, sb);
            encodeValue(lon - prevLon, sb);
            prevLat = lat;
            prevLon = lon;
        }
        return sb.toString();
    }

    private static int toFiveDecimals(int microDegrees) {
        return (int) Math.round(microDegrees / 10.0);
    }

    private static void encodeValue(int value, StringBuilder sb) {
        int v = value << 1;
        if (value < 0) {
            v = ~v;
        }
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }
}
```

- [ ] **Step 4: Add `pointsOnLink` to `PlannedDataset`**

Replace the `// pointsOnLink(patternId) is added in Task 2.` comment in `PlannedDataset.java` with:

```java
    /** Marker for "computed, and there is nothing" - ConcurrentHashMap cannot store null. */
    private static final PointsOnLink NO_GEOMETRY = new PointsOnLink();

    /**
     * The encoded route geometry of a journey pattern, stitched from its service links on
     * first request and cached for the life of this snapshot. Null when the pattern is
     * unknown or none of its links carry geometry.
     */
    public PointsOnLink pointsOnLink(String journeyPatternId) {
        if (journeyPatternId == null || !patternLinks.containsKey(journeyPatternId)) {
            return null;
        }
        PointsOnLink result = patternPolylines.computeIfAbsent(journeyPatternId, this::buildPointsOnLink);
        return result == NO_GEOMETRY ? null : result;
    }

    private PointsOnLink buildPointsOnLink(String journeyPatternId) {
        String[] linkIds = patternLinks.get(journeyPatternId);
        List<int[]> geometries = new ArrayList<>(linkIds.length);
        for (String linkId : linkIds) {
            int[] geometry = linkGeometry.get(linkId);
            if (geometry != null && geometry.length > 0) {
                geometries.add(geometry);
            }
        }
        int[] stitched = Polyline.stitch(geometries);
        if (stitched.length == 0) {
            return NO_GEOMETRY;
        }
        PointsOnLink pointsOnLink = new PointsOnLink();
        pointsOnLink.setLength(stitched.length / 2);
        pointsOnLink.setPoints(Polyline.encode(stitched));
        return pointsOnLink;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest='PolylineTest,PlannedDatasetGeometryTest,PlannedDatasetTest'`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/Polyline.java \
        src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java \
        src/test/java/org/entur/vehicles/service/planned/PolylineTest.java \
        src/test/java/org/entur/vehicles/service/planned/PlannedDatasetGeometryTest.java
git commit -m "Stitch journey pattern geometry from service links and encode as polyline"
```

---

### Task 3: `PosListParser`

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/planned/PosListParser.java`
- Test: `src/test/java/org/entur/vehicles/service/planned/PosListParserTest.java`

**Interfaces:**
- Produces: `static int[] PosListParser.parse(CharSequence text)` — whitespace-separated decimals → microdegrees, in document order (lat lon lat lon for srsName 4326). Rounds half away from zero at the 7th decimal. Never allocates per-token strings.

- [ ] **Step 1: Write the failing tests**

```java
package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PosListParserTest {

    @Test
    public void parsesLatLonPairsToMicrodegrees() {
        assertThat(PosListParser.parse("59.72215 10.512689 59.722111 10.512651"))
                .containsExactly(59_722_150, 10_512_689, 59_722_111, 10_512_651);
    }

    @Test
    public void handlesIntegersNegativesAndExtraWhitespace() {
        assertThat(PosListParser.parse("  60 -5.5\n\t-0.000001 0 "))
                .containsExactly(60_000_000, -5_500_000, -1, 0);
    }

    @Test
    public void roundsBeyondSixDecimals() {
        assertThat(PosListParser.parse("1.0000004 1.0000005 -1.0000005"))
                .containsExactly(1_000_000, 1_000_001, -1_000_001);
    }

    @Test
    public void emptyOrBlankYieldsEmpty() {
        assertThat(PosListParser.parse("")).isEmpty();
        assertThat(PosListParser.parse("   ")).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PosListParserTest`
Expected: compilation failure — `PosListParser` does not exist.

- [ ] **Step 3: Implement `PosListParser`**

```java
package org.entur.vehicles.service.planned;

/**
 * Parses a {@code gis:posList} text node into interleaved microdegrees without allocating
 * a String per token. The full export holds ~20 million coordinates, so the per-token cost
 * of {@code split()} + {@code Double.parseDouble()} is what this avoids.
 */
final class PosListParser {

    private PosListParser() {}

    static int[] parse(CharSequence text) {
        int n = text.length();
        // Count tokens first so the result array is exact.
        int tokens = 0;
        boolean inToken = false;
        for (int i = 0; i < n; i++) {
            boolean ws = Character.isWhitespace(text.charAt(i));
            if (!ws && !inToken) {
                tokens++;
            }
            inToken = !ws;
        }
        int[] out = new int[tokens];
        int k = 0;
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            boolean negative = false;
            if (text.charAt(i) == '-') {
                negative = true;
                i++;
            } else if (text.charAt(i) == '+') {
                i++;
            }
            long integerPart = 0;
            while (i < n && isDigit(text.charAt(i))) {
                integerPart = integerPart * 10 + (text.charAt(i) - '0');
                i++;
            }
            long micro = integerPart * 1_000_000L;
            if (i < n && text.charAt(i) == '.') {
                i++;
                long scale = 100_000L;
                while (i < n && isDigit(text.charAt(i))) {
                    int digit = text.charAt(i) - '0';
                    if (scale > 0) {
                        micro += digit * scale;
                        scale /= 10;
                    } else if (scale == 0) {
                        // 7th decimal: round half up, then ignore the rest
                        if (digit >= 5) {
                            micro++;
                        }
                        scale = -1;
                    }
                    i++;
                }
            }
            // Skip anything else up to the next whitespace (e.g. an exponent we do not expect)
            while (i < n && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            out[k++] = (int) (negative ? -micro : micro);
        }
        return out;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PosListParserTest`
Expected: all 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/PosListParser.java \
        src/test/java/org/entur/vehicles/service/planned/PosListParserTest.java
git commit -m "Add allocation-free gis:posList parser to microdegrees"
```

---

### Task 4: `NetexPlannedDataExtractor` (StAX)

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/planned/NetexPlannedDataExtractor.java`
- Create: `src/test/resources/netex/fragment-line-file.xml`
- Create: `src/test/resources/netex/fragment-shared-data.xml`
- Create: `src/test/resources/netex/fragment-malformed.xml`
- Test: `src/test/java/org/entur/vehicles/service/planned/NetexPlannedDataExtractorTest.java`

**Interfaces:**
- Consumes: `PlannedDataset.Builder` (Task 1), `PosListParser.parse` (Task 3).
- Produces: `void NetexPlannedDataExtractor.extract(InputStream in, PlannedDataset.Builder builder) throws XMLStreamException`.

- [ ] **Step 1: Write the fixtures**

`src/test/resources/netex/fragment-shared-data.xml` (shape copied from the real `_RUT_shared_data.xml` / `_SJN_shared_data.xml`, trimmed):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PublicationDelivery xmlns="http://www.netex.org.uk/netex" xmlns:gis="http://www.opengis.net/gml/3.2" version="1.15:NO-NeTEx-networktimetable:1.5">
  <dataObjects>
    <CompositeFrame version="1" id="TST:CompositeFrame:1">
      <frames>
        <ResourceFrame version="1" id="TST:ResourceFrame:1">
          <organisations>
            <Operator version="1" id="TST:Operator:1">
              <CompanyNumber>917132577</CompanyNumber>
              <Name>Test Operator AS</Name>
              <ContactDetails>
                <Url>https://example.org</Url>
              </ContactDetails>
              <OrganisationType>operator</OrganisationType>
            </Operator>
          </organisations>
        </ResourceFrame>
        <ServiceCalendarFrame version="1" id="TST:ServiceCalendarFrame:1">
          <operatingDays>
            <OperatingDay version="1" id="TST:OperatingDay:2024-01-20">
              <CalendarDate>2024-01-20</CalendarDate>
            </OperatingDay>
          </operatingDays>
        </ServiceCalendarFrame>
        <ServiceFrame version="1" id="TST:ServiceFrame:1">
          <serviceLinks>
            <ServiceLink version="0" id="TST:ServiceLink:A-B">
              <Distance>508.8</Distance>
              <projections>
                <LinkSequenceProjection version="1" id="TST:LinkSequenceProjection:1">
                  <gis:LineString srsName="4326" srsDimension="2" gis:id="LS_1">
                    <gis:posList count="2" srsDimension="2">59.72215 10.512689 59.722111 10.512651</gis:posList>
                  </gis:LineString>
                </LinkSequenceProjection>
              </projections>
              <FromPointRef ref="TST:ScheduledStopPoint:A" version="1"></FromPointRef>
              <ToPointRef ref="TST:ScheduledStopPoint:B" version="1"></ToPointRef>
            </ServiceLink>
            <ServiceLink version="0" id="TST:ServiceLink:B-C">
              <Distance>100</Distance>
              <FromPointRef ref="TST:ScheduledStopPoint:B" version="1"></FromPointRef>
              <ToPointRef ref="TST:ScheduledStopPoint:C" version="1"></ToPointRef>
            </ServiceLink>
          </serviceLinks>
        </ServiceFrame>
      </frames>
    </CompositeFrame>
  </dataObjects>
</PublicationDelivery>
```

`src/test/resources/netex/fragment-line-file.xml` (shape copied from `RUT_RUT-Line-204...xml` and `SJN_SJN-Line-22...xml`, trimmed; note the `DatedServiceJourneyRef` inside the DSJ, which must be ignored):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.15:NO-NeTEx-networktimetable:1.5">
  <dataObjects>
    <CompositeFrame version="1" id="TST:CompositeFrame:2">
      <frames>
        <ServiceFrame version="1" id="TST:ServiceFrame:2">
          <lines>
            <Line version="0" id="TST:Line:204">
              <Name>Rykkinn - Kolsås - Sandvika</Name>
              <TransportMode>bus</TransportMode>
              <TransportSubmode>
                <BusSubmode>localBus</BusSubmode>
              </TransportSubmode>
              <PublicCode>204</PublicCode>
              <OperatorRef ref="TST:Operator:1"></OperatorRef>
              <Presentation>
                <Colour>76A300</Colour>
              </Presentation>
            </Line>
            <FlexibleLine version="0" id="TST:FlexibleLine:8202">
              <Name>Bestillingsrute</Name>
              <TransportMode>bus</TransportMode>
              <PublicCode>8202</PublicCode>
              <FlexibleLineType>flexibleAreasOnly</FlexibleLineType>
            </FlexibleLine>
          </lines>
          <journeyPatterns>
            <JourneyPattern version="0" id="TST:JourneyPattern:1">
              <Name>Outbound</Name>
              <RouteRef ref="TST:Route:1" version="0"></RouteRef>
              <pointsInSequence>
                <StopPointInJourneyPattern version="0" id="TST:StopPointInJourneyPattern:1" order="1">
                  <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="0"></ScheduledStopPointRef>
                  <ForAlighting>false</ForAlighting>
                </StopPointInJourneyPattern>
              </pointsInSequence>
              <linksInSequence>
                <ServiceLinkInJourneyPattern version="0" id="TST:ServiceLinkInJourneyPattern:1" order="1">
                  <ServiceLinkRef ref="TST:ServiceLink:A-B" version="0"></ServiceLinkRef>
                </ServiceLinkInJourneyPattern>
                <ServiceLinkInJourneyPattern version="0" id="TST:ServiceLinkInJourneyPattern:2" order="2">
                  <ServiceLinkRef ref="TST:ServiceLink:B-C" version="0"></ServiceLinkRef>
                </ServiceLinkInJourneyPattern>
              </linksInSequence>
            </JourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <TimetableFrame version="1" id="TST:TimetableFrame:1">
          <vehicleJourneys>
            <ServiceJourney version="1" id="TST:ServiceJourney:1">
              <Name>Ignored</Name>
              <keyList>
                <KeyValue>
                  <Key>Busfile</Key>
                  <Value>x</Value>
                </KeyValue>
              </keyList>
              <PrivateCode>1001</PrivateCode>
              <TransportMode>bus</TransportMode>
              <dayTypes>
                <DayTypeRef ref="TST:DayType:1"></DayTypeRef>
              </dayTypes>
              <JourneyPatternRef ref="TST:JourneyPattern:1" version="0"></JourneyPatternRef>
              <OperatorRef ref="TST:Operator:1"></OperatorRef>
              <LineRef ref="TST:Line:204" version="0"></LineRef>
              <passingTimes>
                <TimetabledPassingTime version="0" id="TST:TimetabledPassingTime:1">
                  <StopPointInJourneyPatternRef ref="TST:StopPointInJourneyPattern:1" version="0"></StopPointInJourneyPatternRef>
                  <DepartureTime>08:00:00</DepartureTime>
                </TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <DatedServiceJourney version="1" id="TST:DatedServiceJourney:1">
              <ServiceAlteration>cancellation</ServiceAlteration>
              <ServiceJourneyRef ref="TST:ServiceJourney:1" version="1"></ServiceJourneyRef>
              <DatedServiceJourneyRef ref="TST:DatedServiceJourney:replaced" version="1"></DatedServiceJourneyRef>
              <OperatingDayRef ref="TST:OperatingDay:2024-01-20"></OperatingDayRef>
            </DatedServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
      </frames>
    </CompositeFrame>
  </dataObjects>
</PublicationDelivery>
```

`src/test/resources/netex/fragment-malformed.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PublicationDelivery xmlns="http://www.netex.org.uk/netex">
  <dataObjects>
    <Line version="0" id="TST:Line:before">
      <Name>Parsed before the error</Name>
    </Line>
    <Line version="0" id="TST:Line:broken">
      <Name>Unclosed
```

- [ ] **Step 2: Write the failing tests**

```java
package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NetexPlannedDataExtractorTest {

    private static PlannedDataset extract(String... resources) throws IOException, XMLStreamException {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        NetexPlannedDataExtractor extractor = new NetexPlannedDataExtractor();
        for (String resource : resources) {
            try (InputStream in = NetexPlannedDataExtractorTest.class.getResourceAsStream("/netex/" + resource)) {
                assertThat(in).withFailMessage("missing test resource " + resource).isNotNull();
                extractor.extract(in, builder);
            }
        }
        return builder.build();
    }

    @Test
    public void extractsOperatorsOperatingDaysAndServiceLinksFromSharedData() throws Exception {
        PlannedDataset dataset = extract("fragment-shared-data.xml");

        assertThat(dataset.operator("TST:Operator:1").getName()).isEqualTo("Test Operator AS");
        assertThat(dataset.stats().serviceLinks()).isEqualTo(2);
        assertThat(dataset.stats().operators()).isEqualTo(1);
    }

    @Test
    public void extractsLinesPatternsJourneysAndDatedJourneysFromLineFile() throws Exception {
        PlannedDataset dataset = extract("fragment-line-file.xml");

        assertThat(dataset.line("TST:Line:204").getLineName()).isEqualTo("Rykkinn - Kolsås - Sandvika");
        assertThat(dataset.line("TST:Line:204").getPublicCode()).isEqualTo("204");
        assertThat(dataset.line("TST:FlexibleLine:8202").getLineName()).isEqualTo("Bestillingsrute");
        assertThat(dataset.line("TST:FlexibleLine:8202").getPublicCode()).isEqualTo("8202");
        assertThat(dataset.journeyPatternOf("TST:ServiceJourney:1")).isEqualTo("TST:JourneyPattern:1");
        assertThat(dataset.stats().journeyPatterns()).isEqualTo(1);
        assertThat(dataset.stats().datedServiceJourneys()).isEqualTo(1);
    }

    @Test
    public void crossFileRefsResolveRegardlessOfFileOrder() throws Exception {
        PlannedDataset dataset = extract("fragment-line-file.xml", "fragment-shared-data.xml");

        assertThat(dataset.datedServiceJourney("TST:DatedServiceJourney:1"))
                .withFailMessage("the nested DatedServiceJourneyRef must not be mistaken for the ServiceJourneyRef")
                .isEqualTo(new DatedJourneyRef("TST:ServiceJourney:1", "2024-01-20"));
        assertThat(dataset.stats().unresolvedLinkRefs()).isZero();
        assertThat(dataset.stats().unresolvedPatternRefs()).isZero();
        assertThat(dataset.stats().unresolvedOperatingDayRefs()).isZero();

        // Pattern links are in linksInSequence order; the second link has no geometry
        assertThat(dataset.pointsOnLink("TST:JourneyPattern:1").getLength()).isEqualTo(2);
        assertThat(dataset.pointsOnLink("TST:JourneyPattern:1").getPoints())
                .isEqualTo(Polyline.encode(new int[]{59_722_150, 10_512_689, 59_722_111, 10_512_651}));
    }

    @Test
    public void nestedNameElementsDoNotLeakIntoTheParent() throws Exception {
        // ServiceJourney has a <Name> that must not be mistaken for anything; JourneyPattern's
        // <Name> is not extracted at all. Both would show up as bogus lines/operators if the
        // extractor matched on element name without regard to nesting.
        PlannedDataset dataset = extract("fragment-line-file.xml");

        assertThat(dataset.stats().lines()).isEqualTo(2);
        assertThat(dataset.stats().operators()).isZero();
    }

    @Test
    public void malformedXmlThrowsAfterKeepingWhatParsed() throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        try (InputStream in = getClass().getResourceAsStream("/netex/fragment-malformed.xml")) {
            assertThatThrownBy(() -> new NetexPlannedDataExtractor().extract(in, builder))
                    .isInstanceOf(XMLStreamException.class);
        }

        assertThat(builder.build().line("TST:Line:before")).isNotNull();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=NetexPlannedDataExtractorTest`
Expected: compilation failure — `NetexPlannedDataExtractor` does not exist.

- [ ] **Step 4: Implement the extractor**

```java
package org.entur.vehicles.service.planned;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One StAX pass over a NeTEx XML stream, keeping only the seven element types the service
 * needs. Everything else is skipped at the token level, so memory is bounded by what is
 * kept, not by the size of the file.
 * <p>
 * Each handled element is read by a method that consumes exactly that element (from its
 * START_ELEMENT to its END_ELEMENT) and only looks at the children it needs, tracking depth
 * so a nested {@code <Name>} several levels down never masquerades as the element's own.
 */
public final class NetexPlannedDataExtractor {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    static {
        FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        FACTORY.setProperty(XMLInputFactory.IS_COALESCING, true);
        FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    }

    public void extract(InputStream in, PlannedDataset.Builder builder) throws XMLStreamException {
        XMLStreamReader r = FACTORY.createXMLStreamReader(in);
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "Operator" -> readOperator(r, builder);
                    case "Line", "FlexibleLine" -> readLine(r, builder);
                    case "ServiceLink" -> readServiceLink(r, builder);
                    case "JourneyPattern", "ServiceJourneyPattern" -> readJourneyPattern(r, builder);
                    case "ServiceJourney" -> readServiceJourney(r, builder);
                    case "DatedServiceJourney" -> readDatedServiceJourney(r, builder);
                    case "OperatingDay" -> readOperatingDay(r, builder);
                    default -> { /* skip */ }
                }
            }
        } finally {
            r.close();
        }
    }

    private void readOperator(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        String[] name = new String[1];
        scan(r, (reader, localName, depth) -> {
            if (depth == 1 && localName.equals("Name")) {
                name[0] = reader.getElementText();
                return true;
            }
            return false;
        });
        if (id != null) {
            builder.addOperator(id, name[0]);
        }
    }

    private void readLine(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        String[] fields = new String[2]; // name, publicCode
        scan(r, (reader, localName, depth) -> {
            if (depth != 1) {
                return false;
            }
            switch (localName) {
                case "Name" -> { fields[0] = reader.getElementText(); return true; }
                case "PublicCode" -> { fields[1] = reader.getElementText(); return true; }
                default -> { return false; }
            }
        });
        if (id != null) {
            builder.addLine(id, fields[0], fields[1]);
        }
    }

    private void readServiceLink(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        int[][] geometry = new int[1][];
        scan(r, (reader, localName, depth) -> {
            if (localName.equals("posList")) {
                geometry[0] = PosListParser.parse(reader.getElementText());
                return true;
            }
            return false;
        });
        if (id != null) {
            builder.addServiceLink(id, geometry[0]);
        }
    }

    private void readJourneyPattern(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        List<String> links = new ArrayList<>();
        scan(r, (reader, localName, depth) -> {
            if (localName.equals("ServiceLinkRef")) {
                String ref = ref(reader);
                if (ref != null) {
                    links.add(ref);
                }
            }
            return false;
        });
        if (id != null) {
            builder.addJourneyPattern(id, links);
        }
    }

    private void readServiceJourney(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        String[] pattern = new String[1];
        scan(r, (reader, localName, depth) -> {
            if (depth == 1 && localName.equals("JourneyPatternRef")) {
                pattern[0] = ref(reader);
            }
            return false;
        });
        if (id != null) {
            builder.addServiceJourney(id, pattern[0]);
        }
    }

    private void readDatedServiceJourney(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        String[] refs = new String[2]; // serviceJourneyId, operatingDayId
        scan(r, (reader, localName, depth) -> {
            if (depth != 1) {
                return false;
            }
            switch (localName) {
                case "ServiceJourneyRef" -> refs[0] = ref(reader);
                case "OperatingDayRef" -> refs[1] = ref(reader);
                default -> { /* DatedServiceJourneyRef and others are ignored */ }
            }
            return false;
        });
        if (id != null) {
            builder.addDatedServiceJourney(id, refs[0], refs[1]);
        }
    }

    private void readOperatingDay(XMLStreamReader r, PlannedDataset.Builder builder) throws XMLStreamException {
        String id = id(r);
        String[] date = new String[1];
        scan(r, (reader, localName, depth) -> {
            if (depth == 1 && localName.equals("CalendarDate")) {
                date[0] = reader.getElementText();
                return true;
            }
            return false;
        });
        if (id != null) {
            builder.addOperatingDay(id, date[0]);
        }
    }

    /**
     * Invoked at every START_ELEMENT below the element being read, with the depth relative
     * to it (direct children are depth 1). Return true if the handler consumed the child
     * (i.e. called {@code getElementText()}, which leaves the reader on the child's
     * END_ELEMENT); return false if the reader is still positioned on the START_ELEMENT.
     */
    @FunctionalInterface
    private interface ChildHandler {
        boolean handle(XMLStreamReader reader, String localName, int depth) throws XMLStreamException;
    }

    /**
     * Walks from the current START_ELEMENT to its matching END_ELEMENT, calling the handler
     * for every nested START_ELEMENT. Leaves the reader on the matching END_ELEMENT.
     */
    private static void scan(XMLStreamReader r, ChildHandler handler) throws XMLStreamException {
        int depth = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (handler.handle(r, r.getLocalName(), depth)) {
                    depth--; // handler consumed through the child's END_ELEMENT
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) {
                    return;
                }
                depth--;
            }
        }
    }

    private static String id(XMLStreamReader r) {
        return r.getAttributeValue(null, "id");
    }

    private static String ref(XMLStreamReader r) {
        return r.getAttributeValue(null, "ref");
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=NetexPlannedDataExtractorTest`
Expected: all 5 PASS. If `malformedXmlThrowsAfterKeepingWhatParsed` fails because `TST:Line:before` is missing, the `Line` handler isn't reached before the parser throws — check that `IS_COALESCING` did not buffer the whole document (it should not; StAX is incremental).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/NetexPlannedDataExtractor.java \
        src/test/resources/netex/fragment-line-file.xml \
        src/test/resources/netex/fragment-shared-data.xml \
        src/test/resources/netex/fragment-malformed.xml \
        src/test/java/org/entur/vehicles/service/planned/NetexPlannedDataExtractorTest.java
git commit -m "Add StAX extractor for the planned-data subset of NeTEx"
```

---

### Task 5: `PlannedDataLoader` — zip → dataset, with the GOA integration test

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/planned/PlannedDataLoadException.java`
- Create: `src/main/java/org/entur/vehicles/service/planned/PlannedDataLoader.java`
- Create: `src/test/resources/netex/rb_goa-aggregated-netex.zip` (copy of the repo-root file)
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataLoaderTest.java`

**Interfaces:**
- Consumes: `NetexPlannedDataExtractor` (Task 4), `PlannedDataset.Builder` (Task 1).
- Produces:
  - `class PlannedDataLoadException extends Exception`
  - `@Component PlannedDataLoader` with `PlannedDataset load(Path zip) throws PlannedDataLoadException`.

- [ ] **Step 1: Copy the fixture**

```bash
mkdir -p src/test/resources/netex
cp rb_goa-aggregated-netex.zip src/test/resources/netex/rb_goa-aggregated-netex.zip
```

- [ ] **Step 2: Write the failing tests**

```java
package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GOA export (three lines, one shared-data file, 1.3 MB) is the smallest real
 * aggregated NeTEx zip and is checked in as a fixture. Its counts were measured with grep
 * over the extracted XML when the fixture was added.
 */
public class PlannedDataLoaderTest {

    private static Path goaZip() throws URISyntaxException {
        return Path.of(PlannedDataLoaderTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI());
    }

    @Test
    public void loadsTheGoaExport() throws Exception {
        PlannedDataset dataset = new PlannedDataLoader().load(goaZip());

        PlannedDataset.Stats stats = dataset.stats();
        assertThat(stats.operators()).isEqualTo(1);
        assertThat(stats.lines()).isEqualTo(3);
        assertThat(stats.journeyPatterns()).isEqualTo(183);
        assertThat(stats.serviceJourneys()).isEqualTo(650);
        assertThat(stats.datedServiceJourneys()).isEqualTo(18599);
        assertThat(stats.serviceLinks()).isEqualTo(315);

        assertThat(dataset.operator("GOA:Operator:GOA").getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(dataset.line("GOA:Line:59").getLineName()).isEqualTo("Jærbanen");
        assertThat(dataset.line("GOA:Line:59").getPublicCode()).isEqualTo("L5");
        assertThat(dataset.journeyPatternOf("GOA:ServiceJourney:B3008-AA_30082-R")).isEqualTo("GOA:JourneyPattern:L59-153");
        assertThat(dataset.datedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20"))
                .isEqualTo(new DatedJourneyRef("GOA:ServiceJourney:B3008-AA_30082-R", "2024-01-20"));

        var points = dataset.pointsOnLink("GOA:JourneyPattern:L59-153");
        assertThat(points).isNotNull();
        assertThat(points.getLength()).isGreaterThan(10);
        assertThat(points.getPoints()).isNotEmpty();
    }

    @Test
    public void aBrokenEntryIsSkippedAndTheRestIsKept(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("mixed.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "_TST_shared_data.xml", resource("/netex/fragment-shared-data.xml"));
            put(out, "TST_TST-Line-broken.xml", resource("/netex/fragment-malformed.xml"));
            put(out, "TST_TST-Line-204.xml", resource("/netex/fragment-line-file.xml"));
            put(out, "README.txt", "not xml");
        }

        PlannedDataset dataset = new PlannedDataLoader().load(zip);

        assertThat(dataset.line("TST:Line:204")).isNotNull();
        assertThat(dataset.line("TST:Line:before"))
                .withFailMessage("elements parsed before the malformed point are kept")
                .isNotNull();
        assertThat(dataset.operator("TST:Operator:1")).isNotNull();
    }

    @Test
    public void zeroLineFilesIsAFailedLoad(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("shared-only.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "_TST_shared_data.xml", resource("/netex/fragment-shared-data.xml"));
        }

        assertThatThrownBy(() -> new PlannedDataLoader().load(zip))
                .isInstanceOf(PlannedDataLoadException.class)
                .hasMessageContaining("line file");
    }

    @Test
    public void unreadableZipIsAFailedLoad(@TempDir Path dir) throws Exception {
        Path notAZip = dir.resolve("garbage.zip");
        Files.writeString(notAZip, "this is not a zip");

        assertThatThrownBy(() -> new PlannedDataLoader().load(notAZip))
                .isInstanceOf(PlannedDataLoadException.class);
    }

    private static String resource(String name) throws IOException {
        try (var in = PlannedDataLoaderTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void put(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDataLoaderTest`
Expected: compilation failure — `PlannedDataLoader` does not exist.

- [ ] **Step 4: Implement the exception and loader**

`src/main/java/org/entur/vehicles/service/planned/PlannedDataLoadException.java`:

```java
package org.entur.vehicles.service.planned;

/** A load that produced no usable dataset. The caller decides whether that is fatal. */
public class PlannedDataLoadException extends Exception {
    public PlannedDataLoadException(String message) {
        super(message);
    }

    public PlannedDataLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/org/entur/vehicles/service/planned/PlannedDataLoader.java`:

```java
package org.entur.vehicles.service.planned;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns an aggregated NeTEx zip into a {@link PlannedDataset}. Each XML entry is streamed
 * independently: a malformed entry is logged and skipped, and whatever it yielded before
 * failing is kept. The load as a whole fails only if the zip is unreadable or contains no
 * line files at all - a shared-data-only zip is a broken export, not a small one.
 */
@Component
public class PlannedDataLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataLoader.class);

    private final NetexPlannedDataExtractor extractor = new NetexPlannedDataExtractor();

    public PlannedDataset load(Path zip) throws PlannedDataLoadException {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
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
                    extractor.extract(in, builder);
                    if (isLineFile(entry.getName())) {
                        lineFiles++;
                    }
                } catch (Exception e) {
                    failedEntries++;
                    LOG.error("Skipping NeTEx entry {} - {}", entry.getName(), e.toString());
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
        return builder.build();
    }

    /**
     * Aggregated exports name shared files {@code _XXX_shared_data.xml} (leading underscore)
     * and everything else {@code XXX_XXX-Line-...xml}.
     */
    static boolean isLineFile(String entryName) {
        String base = entryName.substring(entryName.lastIndexOf('/') + 1);
        return !base.startsWith("_");
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDataLoaderTest`
Expected: all 4 PASS. If the GOA counts differ, re-measure with `unzip -p src/test/resources/netex/rb_goa-aggregated-netex.zip '*.xml' | grep -o "<ServiceJourney \|<DatedServiceJourney \|<ServiceLink \|<JourneyPattern \|<Line \|<Operator " | sort | uniq -c` and reconcile — a mismatch means the extractor is double-counting or missing an element, not that the fixture is wrong.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/planned/PlannedDataLoadException.java \
        src/main/java/org/entur/vehicles/service/planned/PlannedDataLoader.java \
        src/test/resources/netex/rb_goa-aggregated-netex.zip \
        src/test/java/org/entur/vehicles/service/planned/PlannedDataLoaderTest.java
git commit -m "Load a PlannedDataset from an aggregated NeTEx zip, with GOA fixture test"
```

---

### Task 6: `PlannedDataService` — download, startup load, nightly reload, swap guard, metrics

**Files:**
- Modify: `src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java`
- Create: `src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java`
- Modify: `src/main/java/org/entur/vehicles/Application.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Test: `src/test/java/org/entur/vehicles/service/planned/PlannedDataServiceTest.java`

**Interfaces:**
- Consumes: `PlannedDataLoader.load(Path)` (Task 5), `PlannedDataset` (Tasks 1–2).
- Produces:
  - `PrometheusMetricsService.markPlannedDataLoaded(long durationMillis, PlannedDataset.Stats stats)`, `markPlannedDataLoadFailure()`, `markPlannedDataLookupMiss(String type)`.
  - `@Service PlannedDataService` with constructor `(boolean enabled, String url, PlannedDataLoader loader, PrometheusMetricsService metrics)`, `static PlannedDataService disabled()`, `PlannedDataset current()`, `void reload()` (package-visible for tests; nightly semantics: never throws), `Line findLine(String)`, `Operator findOperator(String)`, `boolean hasServiceJourney(String)`, `PointsOnLink findPointsOnLink(String serviceJourneyId)`, `DatedJourneyRef findDatedServiceJourney(String)` — every `find*` returns `null` on a miss and counts it.

- [ ] **Step 1: Write the failing tests**

```java
package org.entur.vehicles.service.planned;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlannedDataServiceTest {

    private static String goaUrl() throws URISyntaxException {
        return PlannedDataServiceTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI().toString();
    }

    private static PrometheusMetricsService metrics() {
        return new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    @Test
    public void disabledServiceServesTheEmptyDatasetAndNeverLoads() {
        PlannedDataService service = PlannedDataService.disabled();

        service.initialLoad();
        service.scheduledReload();

        assertThat(service.current()).isSameAs(PlannedDataset.EMPTY);
        assertThat(service.findLine("GOA:Line:59")).isNull();
        assertThat(service.findOperator("GOA:Operator:GOA")).isNull();
        assertThat(service.hasServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R")).isFalse();
        assertThat(service.findPointsOnLink("GOA:ServiceJourney:B3008-AA_30082-R")).isNull();
        assertThat(service.findDatedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20")).isNull();
    }

    @Test
    public void initialLoadFromAFileUrlPopulatesTheDataset() throws Exception {
        PlannedDataService service = new PlannedDataService(true, goaUrl(), new PlannedDataLoader(), metrics());

        service.initialLoad();

        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
        assertThat(service.findLine("GOA:Line:59").getPublicCode()).isEqualTo("L5");
        assertThat(service.findOperator("GOA:Operator:GOA").getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(service.hasServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R")).isTrue();
        assertThat(service.findPointsOnLink("GOA:ServiceJourney:B3008-AA_30082-R")).isNotNull();
        assertThat(service.findDatedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20").operatingDate())
                .isEqualTo("2024-01-20");
    }

    @Test
    public void initialLoadFailureThrows(@TempDir Path dir) {
        String missing = dir.resolve("missing.zip").toUri().toString();
        PlannedDataService service = new PlannedDataService(true, missing, new PlannedDataLoader(), metrics());

        assertThatThrownBy(service::initialLoad).isInstanceOf(IllegalStateException.class);
        assertThat(service.current()).isSameAs(PlannedDataset.EMPTY);
    }

    @Test
    public void scheduledReloadFailureKeepsTheCurrentDataset(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("data.zip");
        Files.copy(Path.of(PlannedDataServiceTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI()), zip);
        PlannedDataService service = new PlannedDataService(true, zip.toUri().toString(), new PlannedDataLoader(), metrics());
        service.initialLoad();
        PlannedDataset loaded = service.current();

        Files.writeString(zip, "no longer a zip");
        service.scheduledReload(); // must not throw

        assertThat(service.current()).isSameAs(loaded);
    }

    @Test
    public void scheduledReloadSwapsInAFreshDataset(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("data.zip");
        Files.copy(Path.of(PlannedDataServiceTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI()), zip);
        PlannedDataService service = new PlannedDataService(true, zip.toUri().toString(), new PlannedDataLoader(), metrics());
        service.initialLoad();
        PlannedDataset first = service.current();

        service.scheduledReload();

        assertThat(service.current()).isNotSameAs(first);
        assertThat(service.current().serviceJourneyCount()).isEqualTo(650);
    }

    @Test
    public void aDatasetThatShrankByMoreThanHalfIsRejected() {
        PlannedDataset big = withServiceJourneys(10);
        PlannedDataset small = withServiceJourneys(4);
        PlannedDataset okay = withServiceJourneys(5);

        assertThat(PlannedDataService.isSuspiciouslySmall(small, big)).isTrue();
        assertThat(PlannedDataService.isSuspiciouslySmall(okay, big)).isFalse();
        assertThat(PlannedDataService.isSuspiciouslySmall(small, PlannedDataset.EMPTY))
                .withFailMessage("the first load has nothing to compare against")
                .isFalse();
    }

    private static PlannedDataset withServiceJourneys(int n) {
        PlannedDataset.Builder builder = new PlannedDataset.Builder().addJourneyPattern("JP", java.util.List.of());
        for (int i = 0; i < n; i++) {
            builder.addServiceJourney("SJ:" + i, "JP");
        }
        return builder.build();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDataServiceTest`
Expected: compilation failure — `PlannedDataService` and the new metrics methods do not exist.

- [ ] **Step 3: Add the metrics methods**

In `src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java`, add these constants next to the other `*_COUNTER_NAME` constants:

```java
    private static final String PLANNED_DATA_LOAD_DURATION_NAME = METRICS_PREFIX + "planned.data.load.duration";
    private static final String PLANNED_DATA_LAST_SUCCESS_NAME = METRICS_PREFIX + "planned.data.last.success.epoch.seconds";
    private static final String PLANNED_DATA_ENTITIES_NAME = METRICS_PREFIX + "planned.data.entities";
    private static final String PLANNED_DATA_UNRESOLVED_NAME = METRICS_PREFIX + "planned.data.unresolved.refs";
    private static final String PLANNED_DATA_LOAD_FAILURE_COUNTER_NAME = METRICS_PREFIX + "planned.data.load.failure";
    private static final String PLANNED_DATA_LOOKUP_MISS_COUNTER_NAME = METRICS_PREFIX + "planned.data.lookup.miss";
```

Add these fields next to the existing `AtomicInteger`/`AtomicLong` fields (gauges must reference a long-lived number):

```java
    private final AtomicLong plannedDataLoadDurationMillis = new AtomicLong(0);
    private final AtomicLong plannedDataLastSuccessEpochSeconds = new AtomicLong(0);
    private final java.util.Map<String, AtomicLong> plannedDataGauges = new java.util.concurrent.ConcurrentHashMap<>();
```

Add these methods (anywhere among the public `mark*` methods), and add `import org.entur.vehicles.service.planned.PlannedDataset;`:

```java
    public void markPlannedDataLoaded(long durationMillis, PlannedDataset.Stats stats) {
        plannedDataLoadDurationMillis.set(durationMillis);
        prometheusMeterRegistry.gauge(PLANNED_DATA_LOAD_DURATION_NAME, plannedDataLoadDurationMillis);
        plannedDataLastSuccessEpochSeconds.set(System.currentTimeMillis() / 1000);
        prometheusMeterRegistry.gauge(PLANNED_DATA_LAST_SUCCESS_NAME, plannedDataLastSuccessEpochSeconds);

        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "operator", stats.operators());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "line", stats.lines());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "serviceJourney", stats.serviceJourneys());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "datedServiceJourney", stats.datedServiceJourneys());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "journeyPattern", stats.journeyPatterns());
        gauge(PLANNED_DATA_ENTITIES_NAME, "type", "serviceLink", stats.serviceLinks());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "duplicateId", stats.duplicateIds());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "pattern", stats.unresolvedPatternRefs());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "link", stats.unresolvedLinkRefs());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "serviceJourney", stats.unresolvedServiceJourneyRefs());
        gauge(PLANNED_DATA_UNRESOLVED_NAME, "kind", "operatingDay", stats.unresolvedOperatingDayRefs());
    }

    private void gauge(String name, String tagKey, String tagValue, long value) {
        AtomicLong holder = plannedDataGauges.computeIfAbsent(name + "|" + tagKey + "|" + tagValue, k -> {
            AtomicLong a = new AtomicLong();
            prometheusMeterRegistry.gauge(name, List.of(new ImmutableTag(tagKey, tagValue)), a);
            return a;
        });
        holder.set(value);
    }

    public void markPlannedDataLoadFailure() {
        prometheusMeterRegistry.counter(PLANNED_DATA_LOAD_FAILURE_COUNTER_NAME).increment();
    }

    public void markPlannedDataLookupMiss(String type) {
        prometheusMeterRegistry.counter(PLANNED_DATA_LOOKUP_MISS_COUNTER_NAME, List.of(new ImmutableTag("type", type))).increment();
    }
```

Do **not** remove `markJourneyPlannerRequest`/`markJourneyPlannerResponse` yet — Task 8 deletes their callers first.

- [ ] **Step 4: Implement `PlannedDataService`**

```java
package org.entur.vehicles.service.planned;

import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the current {@link PlannedDataset}. Loads it once at startup - blocking the Spring
 * context, and therefore readiness, until it is in place - and swaps in a fresh one from a
 * nightly reload. A failed startup load is fatal; a failed nightly reload keeps the
 * previous dataset.
 * <p>
 * The {@code find*} methods are the lookup surface the enrichment services use. They return
 * null on a miss and count it, so a producer referencing ids the export lacks is visible.
 */
@Service
public class PlannedDataService {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataService.class);

    private static final int DOWNLOAD_TIMEOUT_MILLIS = 60_000;

    private final boolean enabled;
    private final String url;
    private final PlannedDataLoader loader;
    private final PrometheusMetricsService metrics;
    private final AtomicReference<PlannedDataset> current = new AtomicReference<>(PlannedDataset.EMPTY);

    @Autowired
    public PlannedDataService(@Value("${vehicle.planned.data.enabled:false}") boolean enabled,
                              @Value("${vehicle.planned.data.url:https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip}") String url,
                              PlannedDataLoader loader,
                              PrometheusMetricsService metrics) {
        this.enabled = enabled;
        this.url = url;
        this.loader = loader;
        this.metrics = metrics;
    }

    /** A service that never loads and serves {@link PlannedDataset#EMPTY} - for tests. */
    public static PlannedDataService disabled() {
        return new PlannedDataService(false, null, null, null);
    }

    @PostConstruct
    void initialLoad() {
        if (!enabled) {
            LOG.info("Planned data disabled - lookups return bare refs");
            return;
        }
        try {
            load();
        } catch (PlannedDataLoadException e) {
            throw new IllegalStateException("Initial planned data load failed", e);
        }
    }

    @Scheduled(cron = "${vehicle.planned.data.reload.cron:0 0 8 * * *}", zone = "Europe/Oslo")
    void scheduledReload() {
        if (!enabled) {
            return;
        }
        try {
            load();
        } catch (PlannedDataLoadException e) {
            LOG.error("Planned data reload failed - keeping the current dataset", e);
        }
    }

    private void load() throws PlannedDataLoadException {
        long start = System.currentTimeMillis();
        Path zip = null;
        try {
            zip = download(url);
            PlannedDataset fresh = loader.load(zip);
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
            }
            LOG.info("Planned data loaded in {} ms: {}", duration, fresh.stats());
        } catch (PlannedDataLoadException e) {
            if (metrics != null) {
                metrics.markPlannedDataLoadFailure();
            }
            throw e;
        } finally {
            if (zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException e) {
                    LOG.warn("Could not delete temp file {}", zip, e);
                }
            }
        }
    }

    /**
     * A fresh dataset with fewer than half the current one's service journeys is far more
     * likely a truncated export than a real change in Norway's timetable. Never applies to
     * the first load, which has nothing to compare against.
     */
    static boolean isSuspiciouslySmall(PlannedDataset fresh, PlannedDataset previous) {
        if (previous.serviceJourneyCount() == 0) {
            return false;
        }
        return fresh.serviceJourneyCount() * 2 < previous.serviceJourneyCount();
    }

    private static Path download(String url) throws PlannedDataLoadException {
        long start = System.currentTimeMillis();
        try {
            Path tmp = Files.createTempFile("planned-netex", ".zip");
            FileUtils.copyURLToFile(new URL(url), tmp.toFile(), DOWNLOAD_TIMEOUT_MILLIS, DOWNLOAD_TIMEOUT_MILLIS);
            LOG.info("Download of {} took {} ms", url, System.currentTimeMillis() - start);
            return tmp;
        } catch (IOException e) {
            throw new PlannedDataLoadException("Could not download " + url, e);
        }
    }

    public PlannedDataset current() {
        return current.get();
    }

    public Line findLine(String lineRef) {
        Line line = current.get().line(lineRef);
        if (line == null) {
            miss("line");
        }
        return line;
    }

    public Operator findOperator(String operatorRef) {
        Operator operator = current.get().operator(operatorRef);
        if (operator == null) {
            miss("operator");
        }
        return operator;
    }

    public boolean hasServiceJourney(String serviceJourneyId) {
        boolean known = current.get().hasServiceJourney(serviceJourneyId);
        if (!known) {
            miss("serviceJourney");
        }
        return known;
    }

    /** Geometry for a service journey, or null if the journey or its geometry is unknown. Not miss-counted - use {@link #hasServiceJourney} for that. */
    public PointsOnLink findPointsOnLink(String serviceJourneyId) {
        PlannedDataset dataset = current.get();
        return dataset.pointsOnLink(dataset.journeyPatternOf(serviceJourneyId));
    }

    public DatedJourneyRef findDatedServiceJourney(String datedServiceJourneyId) {
        DatedJourneyRef ref = current.get().datedServiceJourney(datedServiceJourneyId);
        if (ref == null) {
            miss("datedServiceJourney");
        }
        return ref;
    }

    private void miss(String type) {
        if (metrics != null) {
            metrics.markPlannedDataLookupMiss(type);
        }
    }
}
```

`FileUtils.copyURLToFile` handles `file:` URLs, which is what the tests use.

- [ ] **Step 5: Enable scheduling and add config**

`src/main/java/org/entur/vehicles/Application.java`:

```java
package org.entur.vehicles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

Append to `src/main/resources/application.properties` (after the `vehicle.nsr.lookup.*` lines):

```properties
# Planned data (lines, operators, service journeys, dated service journeys, route geometry)
# is loaded from the aggregated NeTEx export at startup and reloaded nightly. Startup blocks
# until the first load completes. Disabled by default for local runs; enabled in helm.
vehicle.planned.data.enabled=false
vehicle.planned.data.url=https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip
# Marduk's export was observed landing at ~07:30 UTC; reloading earlier picks up yesterday's file.
vehicle.planned.data.reload.cron=0 0 8 * * *
```

Append to `src/test/resources/application.properties`:

```properties
# Never loaded in tests - PlannedDataService short-circuits when disabled.
vehicle.planned.data.enabled=false
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedDataServiceTest`
Expected: all 6 PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java \
        src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java \
        src/main/java/org/entur/vehicles/Application.java \
        src/main/resources/application.properties \
        src/test/resources/application.properties \
        src/test/java/org/entur/vehicles/service/planned/PlannedDataServiceTest.java
git commit -m "Add PlannedDataService: startup load, nightly reload, swap guard, metrics"
```

---

### Task 7: Rewrite `LineService`, `OperatorService`, `ServiceJourneyService` on top of `PlannedDataService`

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/LineService.java` (full rewrite)
- Modify: `src/main/java/org/entur/vehicles/service/OperatorService.java` (full rewrite)
- Modify: `src/main/java/org/entur/vehicles/service/ServiceJourneyService.java` (full rewrite)
- Modify: `src/main/java/org/entur/vehicles/repository/VehicleRepository.java:146-181`
- Modify: `src/main/java/org/entur/vehicles/repository/TimetableRepository.java:118-153`
- Modify: `src/main/java/org/entur/vehicles/repository/SituationMapper.java:258-267`
- Modify: `src/test/java/org/entur/vehicles/repository/TimetableRepositoryStopPointTest.java:65`
- Modify: `src/test/java/org/entur/vehicles/repository/SituationMapperTest.java:42,237,261`
- Modify: `src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java:50`
- Modify: `src/test/java/org/entur/vehicles/service/SituationSnapshotServiceTest.java:59`
- Test: `src/test/java/org/entur/vehicles/service/PlannedLookupServicesTest.java`

**Interfaces:**
- Consumes: `PlannedDataService.find*` / `hasServiceJourney` (Task 6).
- Produces (public API unchanged in name, but no longer `throws ExecutionException`):
  - `Line LineService.getLine(String lineRef)` — never null.
  - `static Operator OperatorService.getOperator(String operatorRef)` — null on miss (as today).
  - `ServiceJourney ServiceJourneyService.getServiceJourney(String id)` — fresh instance, never null.
  - `DatedServiceJourney ServiceJourneyService.getDatedServiceJourney(String id)` — fresh instance, never null.
  - Constructors: `LineService(PlannedDataService)`, `OperatorService(PlannedDataService)`, `ServiceJourneyService(PlannedDataService)`.

- [ ] **Step 1: Write the failing test**

```java
package org.entur.vehicles.service;

import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three enrichment services are thin lookups over the current PlannedDataset. Against
 * the GOA fixture they must resolve real ids, and against a disabled service they must
 * return the same bare-ref fallbacks a failed JourneyPlanner lookup returned before.
 */
public class PlannedLookupServicesTest {

    private static PlannedDataService loaded() throws Exception {
        String url = PlannedLookupServicesTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI().toString();
        PlannedDataService service = new PlannedDataService(true, url,
                new org.entur.vehicles.service.planned.PlannedDataLoader(), null);
        // initialLoad is package-private in the planned package; go through the public reload path
        service.reloadForTest();
        return service;
    }

    @Test
    public void lineIsResolvedFromTheDataset() throws Exception {
        LineService lineService = new LineService(loaded());

        Line line = lineService.getLine("GOA:Line:59");

        assertThat(line.getLineName()).isEqualTo("Jærbanen");
        assertThat(line.getPublicCode()).isEqualTo("L5");
    }

    @Test
    public void lineMissIsABareRef() {
        LineService lineService = new LineService(PlannedDataService.disabled());

        Line line = lineService.getLine("X:Line:1");

        assertThat(line.getLineRef()).isEqualTo("X:Line:1");
        assertThat(line.getLineName()).isNull();
    }

    @Test
    public void operatorIsResolvedStatically() throws Exception {
        new OperatorService(loaded());

        Operator operator = OperatorService.getOperator("GOA:Operator:GOA");

        assertThat(operator.getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(OperatorService.getOperator("X:Operator:1")).isNull();
    }

    @Test
    public void serviceJourneyCarriesGeometryAndIsAFreshInstancePerCall() throws Exception {
        ServiceJourneyService service = new ServiceJourneyService(loaded());

        ServiceJourney a = service.getServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R");
        ServiceJourney b = service.getServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R");

        assertThat(a.getId()).isEqualTo("GOA:ServiceJourney:B3008-AA_30082-R");
        assertThat(a.getPointsOnLink()).isNotNull();
        assertThat(a).isNotSameAs(b);
        assertThat(a.getPointsOnLink()).isSameAs(b.getPointsOnLink());
        a.setDate("2024-01-20");
        assertThat(b.getDate()).withFailMessage("mutating one caller's instance must not leak to another").isNull();
    }

    @Test
    public void serviceJourneyMissIsABareRef() {
        ServiceJourneyService service = new ServiceJourneyService(PlannedDataService.disabled());

        ServiceJourney sj = service.getServiceJourney("X:ServiceJourney:1");

        assertThat(sj.getId()).isEqualTo("X:ServiceJourney:1");
        assertThat(sj.getPointsOnLink()).isNull();
    }

    @Test
    public void datedServiceJourneyResolvesToItsJourneyAndDate() throws Exception {
        ServiceJourneyService service = new ServiceJourneyService(loaded());

        DatedServiceJourney dsj = service.getDatedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20");

        assertThat(dsj.getId()).isEqualTo("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20");
        assertThat(dsj.getOperatingDay()).isEqualTo("2024-01-20");
        assertThat(dsj.getServiceJourney().getId()).isEqualTo("GOA:ServiceJourney:B3008-AA_30082-R");
        assertThat(dsj.getServiceJourney().getDate()).isEqualTo("2024-01-20");
        assertThat(dsj.getServiceJourney().getPointsOnLink()).isNotNull();
    }

    @Test
    public void datedServiceJourneyMissIsABareRefWithABareServiceJourney() {
        ServiceJourneyService service = new ServiceJourneyService(PlannedDataService.disabled());

        DatedServiceJourney dsj = service.getDatedServiceJourney("X:DatedServiceJourney:1");

        assertThat(dsj.getId()).isEqualTo("X:DatedServiceJourney:1");
        assertThat(dsj.getOperatingDay()).isNull();
        assertThat(dsj.getServiceJourney().getId()).isEqualTo("X:DatedServiceJourney:1");
    }
}
```

This test needs one public hook on `PlannedDataService` because `initialLoad()` is package-private in another package. Add to `PlannedDataService`:

```java
    /** Runs a load with startup semantics (throws on failure). For tests outside this package. */
    public void reloadForTest() {
        initialLoad();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test -Dtest=PlannedLookupServicesTest`
Expected: compilation failure — the new constructors do not exist.

- [ ] **Step 3: Rewrite the three services**

`src/main/java/org/entur/vehicles/service/LineService.java`:

```java
package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LineService {

    private final PlannedDataService plannedData;

    @Autowired
    public LineService(PlannedDataService plannedData) {
        this.plannedData = plannedData;
    }

    /** The line from planned data, or a bare ref if unknown. Never null. */
    public Line getLine(String lineRef) {
        Line line = plannedData.findLine(lineRef);
        return line != null ? line : new Line(lineRef);
    }
}
```

`src/main/java/org/entur/vehicles/service/OperatorService.java`:

```java
package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@link #getOperator} is static because its three callers - VehicleRepository,
 * TimetableRepository, SituationMapper - call it that way and have done so since the
 * operator cache was a static map. The static reference is set when Spring constructs the
 * bean; before that (and in tests that never construct one) every lookup misses, exactly as
 * the empty static cache did.
 */
@Service
public class OperatorService {

    private static volatile PlannedDataService plannedData;

    @Autowired
    public OperatorService(PlannedDataService plannedData) {
        OperatorService.plannedData = plannedData;
    }

    /** The operator from planned data, or null if unknown. */
    public static Operator getOperator(String operatorRef) {
        PlannedDataService service = plannedData;
        return service == null ? null : service.findOperator(operatorRef);
    }
}
```

`src/main/java/org/entur/vehicles/service/ServiceJourneyService.java`:

```java
package org.entur.vehicles.service;

import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.service.planned.DatedJourneyRef;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ServiceJourneyService {

    private final PlannedDataService plannedData;

    @Autowired
    public ServiceJourneyService(PlannedDataService plannedData) {
        this.plannedData = plannedData;
    }

    /**
     * A fresh ServiceJourney per call. Callers set the date on it, so instances must not be
     * shared; the PointsOnLink it carries is immutable and is shared.
     */
    public ServiceJourney getServiceJourney(String serviceJourneyId) {
        ServiceJourney serviceJourney = new ServiceJourney(serviceJourneyId);
        if (plannedData.hasServiceJourney(serviceJourneyId)) {
            serviceJourney.setPointsOnLink(plannedData.findPointsOnLink(serviceJourneyId));
        }
        return serviceJourney;
    }

    /**
     * A fresh DatedServiceJourney per call, with the operating day and a ServiceJourney
     * dated to it - the same shape the JourneyPlanner lookup used to build.
     */
    public DatedServiceJourney getDatedServiceJourney(String datedServiceJourneyId) {
        DatedJourneyRef ref = plannedData.findDatedServiceJourney(datedServiceJourneyId);
        if (ref == null) {
            return new DatedServiceJourney(datedServiceJourneyId, new ServiceJourney(datedServiceJourneyId));
        }
        ServiceJourney serviceJourney = new ServiceJourney(ref.serviceJourneyId(), ref.operatingDate());
        PointsOnLink pointsOnLink = plannedData.findPointsOnLink(ref.serviceJourneyId());
        serviceJourney.setPointsOnLink(pointsOnLink);
        DatedServiceJourney datedServiceJourney = new DatedServiceJourney(datedServiceJourneyId, serviceJourney);
        datedServiceJourney.setOperatingDay(ref.operatingDate());
        return datedServiceJourney;
    }
}
```

- [ ] **Step 4: Remove the `ExecutionException` handling at the call sites**

Removing `throws ExecutionException` makes the existing `catch (ExecutionException e)` blocks a compile error ("exception is never thrown"), so each must go.

`src/main/java/org/entur/vehicles/repository/VehicleRepository.java` — replace lines 146–181 (the three `if (lineRef != null)` / `if (serviceJourneyId != null)` / `if (datedServiceJourneyId != null)` blocks) with:

```java
      if (lineRef != null) {
        v.setLine(lineService.getLine(lineRef));
      } else {
        v.setLine(Line.DEFAULT);
      }

      if (serviceJourneyId != null) {
        ServiceJourney serviceJourney = serviceJourneyService.getServiceJourney(serviceJourneyId);
        serviceJourney.setDate(date);
        v.setServiceJourney(serviceJourney);
      }
      if (datedServiceJourneyId != null) {
        DatedServiceJourney datedServiceJourney = serviceJourneyService.getDatedServiceJourney(datedServiceJourneyId);
        if (v.getServiceJourney() != null) {
          datedServiceJourney.setServiceJourney(v.getServiceJourney());
        }
        v.setDatedServiceJourney(datedServiceJourney);
      }
```

Then remove `import java.util.concurrent.ExecutionException;` from `VehicleRepository.java` if nothing else uses it (check with `grep -n ExecutionException src/main/java/org/entur/vehicles/repository/VehicleRepository.java`).

`src/main/java/org/entur/vehicles/repository/TimetableRepository.java` — replace lines 118–153 (the same three blocks) with the identical code above, then remove the unused `ExecutionException` import the same way.

`src/main/java/org/entur/vehicles/repository/SituationMapper.java` — replace `resolveLine` (lines 258–267) with:

```java
    private Line resolveLine(String lineRef) {
        if (lineRef == null) {
            return null;
        }
        return lineService.getLine(lineRef);
    }
```

and remove the unused `ExecutionException` import.

- [ ] **Step 5: Update the test call sites that construct `LineService`**

Every `new LineService(false)` becomes `new LineService(PlannedDataService.disabled())`, with `import org.entur.vehicles.service.planned.PlannedDataService;` added:

- `src/test/java/org/entur/vehicles/repository/TimetableRepositoryStopPointTest.java:65`
- `src/test/java/org/entur/vehicles/repository/SituationMapperTest.java:42,237,261`
- `src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java:50`
- `src/test/java/org/entur/vehicles/service/SituationSnapshotServiceTest.java:59`

```bash
for f in src/test/java/org/entur/vehicles/repository/TimetableRepositoryStopPointTest.java \
         src/test/java/org/entur/vehicles/repository/SituationMapperTest.java \
         src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java \
         src/test/java/org/entur/vehicles/service/SituationSnapshotServiceTest.java; do
  sed -i '' 's/new LineService(false)/new LineService(PlannedDataService.disabled())/g' "$f"
  grep -q 'import org.entur.vehicles.service.planned.PlannedDataService;' "$f" || \
    sed -i '' 's/^import org.entur.vehicles.service.LineService;/import org.entur.vehicles.service.LineService;\nimport org.entur.vehicles.service.planned.PlannedDataService;/' "$f"
done
grep -rn "new LineService(" src/test/java
```

`SituationSnapshotServiceTest.java` is in package `org.entur.vehicles.service` and may not import `LineService` at all (same package). If the grep shows the import wasn't added there, add `import org.entur.vehicles.service.planned.PlannedDataService;` by hand.

Tests that mock `ServiceJourneyService` with `Mockito.when(...)` inside methods declared `throws ExecutionException` still compile — a method may declare a checked exception it never throws; only `catch` blocks are checked.

- [ ] **Step 6: Run the whole suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test`
Expected: all PASS, including `PlannedLookupServicesTest` (7 tests) and every pre-existing test. The `ApplicationGraphQlSchemaTests` and `NSRServiceSpringWiringTest` boot a real Spring context: they verify that `PlannedDataService`, `PlannedDataLoader` and the three rewritten services wire up.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/LineService.java \
        src/main/java/org/entur/vehicles/service/OperatorService.java \
        src/main/java/org/entur/vehicles/service/ServiceJourneyService.java \
        src/main/java/org/entur/vehicles/service/planned/PlannedDataService.java \
        src/main/java/org/entur/vehicles/repository/VehicleRepository.java \
        src/main/java/org/entur/vehicles/repository/TimetableRepository.java \
        src/main/java/org/entur/vehicles/repository/SituationMapper.java \
        src/test/java/org/entur/vehicles/service/PlannedLookupServicesTest.java \
        src/test/java/org/entur/vehicles/repository/TimetableRepositoryStopPointTest.java \
        src/test/java/org/entur/vehicles/repository/SituationMapperTest.java \
        src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java \
        src/test/java/org/entur/vehicles/service/SituationSnapshotServiceTest.java
git commit -m "Serve line, operator and service journey lookups from planned NeTEx data"
```

---

### Task 8: Delete the JourneyPlanner client and its configuration

**Files:**
- Delete: `src/main/java/org/entur/vehicles/service/JourneyPlannerGraphQLClient.java`
- Delete: `src/main/java/org/entur/vehicles/service/graphql/Data.java`
- Delete: `src/main/java/org/entur/vehicles/service/graphql/Response.java`
- Modify: `src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java`
- Modify: `src/main/resources/application.properties:36-39`
- Modify: `local_config/application.properties:23-24`
- Modify: `src/test/resources/application.properties:1-8`
- Modify: `helm/vehicle-positions-2/values.yaml`
- Modify: `helm/vehicle-positions-2/env/values-kub-ent-dev.yaml`, `values-kub-ent-tst.yaml`, `values-kub-ent-prd.yaml`
- Modify: `helm/vehicle-positions-2/templates/configmap.yaml`
- Modify: `helm/vehicle-positions-2/templates/deployment.yaml:91`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Delete the client and its DTOs**

```bash
git rm src/main/java/org/entur/vehicles/service/JourneyPlannerGraphQLClient.java \
       src/main/java/org/entur/vehicles/service/graphql/Data.java \
       src/main/java/org/entur/vehicles/service/graphql/Response.java
grep -rn "service.graphql\|JourneyPlannerGraphQLClient\|markJourneyPlanner" src/main/java src/test/java
```

Expected grep output: only the two `markJourneyPlanner*` methods and their two constants in `PrometheusMetricsService.java`.

- [ ] **Step 2: Remove the JourneyPlanner metrics**

In `src/main/java/org/entur/vehicles/metrics/PrometheusMetricsService.java` delete the constants `JOURNEY_PLANNER_REQUEST_COUNTER_NAME` and `JOURNEY_PLANNER_RESPONSE_COUNTER_NAME` and the methods `markJourneyPlannerRequest(String)` and `markJourneyPlannerResponse(String)`.

- [ ] **Step 3: Properties**

`src/main/resources/application.properties` — delete these four lines:

```properties
vehicle.journeyplanner.url=https://api.dev.entur.io/journey-planner/v3/graphql
vehicle.journeyplanner.EtClientName=ror.vehicle-positions.graphql
vehicle.line.lookup.enabled=true
vehicle.operator.lookup.enabled=true
```

`local_config/application.properties` — replace lines 23–24 with:

```properties
# Planned data from a local copy of the aggregated NeTEx export; download from
# https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip
# (or use the much smaller rb_goa-aggregated-netex.zip for a quick start)
vehicle.planned.data.enabled=false
vehicle.planned.data.url=file:///path/to/rb_norway-aggregated-netex.zip
```

`src/test/resources/application.properties` — replace the first eight lines (five `*.lookup.enabled` lines, a blank line, two `vehicle.journeyplanner.*` lines) with the single line:

```properties
vehicle.nsr.lookup.enabled=false
```

The rest of the file, including the `vehicle.planned.data.enabled=false` line added in Task 6, stays as is.

- [ ] **Step 4: Helm**

`helm/vehicle-positions-2/values.yaml` — under `configMap:` delete `journeyPlannerUrl`, `EtClientName`, `serviceJourneyLookupEnabled` and the whole `journeyplanner:` block; add:

```yaml
  plannedData:
    enabled: true
    url: https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip
    # Europe/Oslo. Marduk's export lands around 07:30 UTC; confirm before moving this earlier.
    reloadCron: "0 0 8 * * *"
```

`helm/vehicle-positions-2/env/values-kub-ent-dev.yaml`, `values-kub-ent-tst.yaml`, `values-kub-ent-prd.yaml` — delete the `journeyPlannerUrl`, `EtClientName` and `serviceJourneyLookupEnabled` lines from each.

`helm/vehicle-positions-2/templates/configmap.yaml` — delete these lines:

```
      vehicle.journeyplanner.url={{ .Values.configMap.journeyPlannerUrl }}
      vehicle.journeyplanner.EtClientName={{ .Values.configMap.EtClientName }}
      vehicle.serviceJourney.lookup.enabled={{ .Values.configMap.serviceJourneyLookupEnabled }}
      vehicle.line.lookup.enabled=true
      vehicle.operator.lookup.enabled=true
      vehicle.serviceJourney.concurrent.requests={{ .Values.configMap.journeyplanner.servicejourney.threadpoolsize }}
      vehicle.serviceJourney.concurrent.sleeptime={{ .Values.configMap.journeyplanner.servicejourney.sleeptime }}
      vehicle.line.concurrent.requests={{ .Values.configMap.journeyplanner.line.threadpoolsize }}
      vehicle.line.concurrent.sleeptime={{ .Values.configMap.journeyplanner.line.sleeptime }}
```

and add, next to the `vehicle.nsr.lookup.*` lines:

```
      vehicle.planned.data.enabled={{ .Values.configMap.plannedData.enabled }}
      vehicle.planned.data.url={{ .Values.configMap.plannedData.url }}
      vehicle.planned.data.reload.cron={{ .Values.configMap.plannedData.reloadCron }}
```

`helm/vehicle-positions-2/templates/deployment.yaml` — in `startupProbe`, change `failureThreshold: 25` to `failureThreshold: 60` (5 minutes at `periodSeconds: 5`). Task 9 may adjust this from measurement.

Verify the chart still renders:

```bash
helm template helm/vehicle-positions-2 -f helm/vehicle-positions-2/env/values-kub-ent-prd.yaml | grep -n "planned.data\|journeyplanner\|failureThreshold"
```

Expected: three `vehicle.planned.data.*` lines, no `journeyplanner`, `failureThreshold: 60` for the startup probe.

- [ ] **Step 5: Update `CLAUDE.md`**

In `CLAUDE.md`, replace the enrichment part of the **Data Flow Pipeline** block:

```
Repository.add() - Enrichment Layer:
    ├─ LineService: Fetches line metadata (cached 6h)
    ├─ OperatorService: Fetches operator info (updated every 60min)
    ├─ ServiceJourneyService: Fetches journey details async (cached 6h)
    └─ NSRService: Looks up stop coordinates (from NeTEx file)
```

with:

```
Repository.add() - Enrichment Layer:
    ├─ LineService / OperatorService / ServiceJourneyService:
    │    O(1) lookups in PlannedDataService's in-memory PlannedDataset
    │    (extracted from the aggregated NeTEx export at startup, reloaded nightly)
    └─ NSRService: Looks up stop coordinates (from the NSR NeTEx file)
```

Replace item 2 of **External Service Integrations** (`Journey Planner GraphQL API ...`) with:

```
2. **Aggregated NeTEx export** (`vehicle.planned.data.url`)
   - Service: `PlannedDataService` (package `service.planned`)
   - Purpose: Lines, operators, service journeys, dated service journeys, route geometry
   - Loaded at startup (blocks readiness), reloaded nightly (`vehicle.planned.data.reload.cron`)
   - Streamed with StAX into ~300 MB of compact maps; never JAXB
```

Replace item 3 of **Important Behavioral Notes** (`Operator Updates: ...`) with:

```
3. **Planned data reload**: `PlannedDataService` swaps in a fresh `PlannedDataset` nightly; a failed reload keeps the previous one, a dataset with < 50% of the previous service journeys is rejected
```

- [ ] **Step 6: Run the whole suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q test`
Expected: all PASS; no compile references to the deleted classes.

- [ ] **Step 7: Commit**

```bash
git add -A src/main src/test local_config helm CLAUDE.md
git status --short   # verify only intended files are staged; do NOT stage the root *.zip files, terraform/, or src/test/stresstest/
git commit -m "Remove JourneyPlanner client and configuration"
```

---

### Task 9: Measure the full Norway load and set the probe threshold

This task is a manual measurement; nothing here is guessed.

**Files:**
- Modify: `helm/vehicle-positions-2/templates/deployment.yaml` (startup probe threshold, if the measurement warrants)
- Modify: `docs/superpowers/specs/2026-08-25-netex-planned-data-design.md` (record the numbers under Deployment)

- [ ] **Step 1: Build the jar**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25+) mvn -q -DskipTests package
ls target/vehicle-positions-*-SNAPSHOT.jar
```

- [ ] **Step 2: Run against the local Norway export with the production heap**

The Pub/Sub subscribers are disabled so no credentials are needed; NSR lookup is left off so the measured time is the planned-data load alone.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25+) $JAVA_HOME/bin/java -Xmx5G -XX:+UseG1GC \
  -Dvehicle.planned.data.enabled=true \
  -Dvehicle.planned.data.url=file://$PWD/rb_norway-aggregated-netex.zip \
  -Dentur.vehicle-positions.vm.enabled=false \
  -Dentur.vehicle-positions.et.enabled=false \
  -Dentur.vehicle-positions.sx.enabled=false \
  -Dentur.vehicle-positions.gcp.topic.project.name=x \
  -Dentur.vehicle-positions.gcp.subscription.project.name=x \
  -Dentur.vehicle-positions.gcp.topic.name.vm=x -Dentur.vehicle-positions.gcp.topic.name.et=x -Dentur.vehicle-positions.gcp.topic.name.sx=x \
  -Dentur.vehicle-positions.gcp.subscription.name.vm=x -Dentur.vehicle-positions.gcp.subscription.name.et=x -Dentur.vehicle-positions.gcp.subscription.name.sx=x \
  -Dvehicle.sx.snapshot.url=http://localhost:0/sx \
  -jar target/vehicle-positions-*-SNAPSHOT.jar 2>&1 | tee /tmp/planned-load.log | grep -E "Planned data loaded|Download of|Started Application"
```

Record from the log: `Planned data loaded in N ms: Stats[...]` — the load time and the stats line.

- [ ] **Step 3: Measure heap**

While the app is still running, in another shell:

```bash
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap | python3 -c 'import json,sys; print(round(json.load(sys.stdin)["measurements"][0]["value"]/1e6), "MB heap used")'
```

Then trigger a GC and re-read to get the retained size: `jcmd $(pgrep -f vehicle-positions) GC.run` and repeat the curl. Record the retained heap.

- [ ] **Step 4: Check production headroom**

Use the `entur-kompass` `read_metrics` tool (or the Grafana dashboard for `ent-vpos-prd`) to read `jvm_memory_used_bytes{area="heap"}` for the vehicle-positions pods over the last 7 days. Record the peak. The retained size from Step 3, times two (for the swap), plus that peak must sit under the 5 GB `-Xmx`. If it does not, raise `resources.xmx`/`memRequest`/`memLimit` in `helm/vehicle-positions-2/values.yaml` proportionally and say so in the PR.

- [ ] **Step 5: Set the probe threshold**

Add the NSR load time (from a prod log line `NSRService cache warm-up took: N ms`) to the planned load time and the download time, double it for a slow node, and divide by `periodSeconds: 5`. Set `startupProbe.failureThreshold` in `helm/vehicle-positions-2/templates/deployment.yaml` to that number, or leave it at 60 if the computed value is lower.

- [ ] **Step 6: Record and commit**

Append to the **Deployment** section of `docs/superpowers/specs/2026-08-25-netex-planned-data-design.md`:

```markdown
### Measured (2026-MM-DD, full Norway export, local run)

- Load time: N s (download M s + parse/build P s)
- Retained heap after load and GC: X MB
- Prod heap peak (7 days, before this change): Y MB
- Startup probe `failureThreshold` set to: T
```

with real numbers.

```bash
git add helm/vehicle-positions-2/templates/deployment.yaml docs/superpowers/specs/2026-08-25-netex-planned-data-design.md
git commit -m "Set startup probe from measured planned-data load time"
```

---

### Task 10: Manual comparison against JourneyPlanner before it goes away

One journey, compared end to end, while the dev JourneyPlanner still serves `pointsOnLink`. This is the only check against the real source; everything else is unit-tested.

- [ ] **Step 1: Pick a live journey**

With the app from Task 9 still running (or restarted with `entur.vehicle-positions.vm.enabled=true` and dev credentials), query it:

```bash
curl -s http://localhost:8080/graphql -H 'Content-Type: application/json' -H 'Et-Client-Name: local-test' \
  -d '{"query":"{ vehicles(codespaceId:\"RUT\") { serviceJourney { id pointsOnLink { length points } } } }"}' \
  | python3 -c 'import json,sys; v=[x for x in json.load(sys.stdin)["data"]["vehicles"] if x["serviceJourney"] and x["serviceJourney"]["pointsOnLink"]][0]; print(json.dumps(v))'
```

Save the `id`, `length` and `points`.

- [ ] **Step 2: Fetch the same journey from JourneyPlanner**

```bash
curl -s https://api.dev.entur.io/journey-planner/v3/graphql -H 'Content-Type: application/json' -H 'ET-Client-Name: ror.vehicle-positions.local-compare' \
  -d '{"query":"{ serviceJourney(id:\"<ID FROM STEP 1>\") { pointsOnLink { length points } } }"}'
```

- [ ] **Step 3: Compare**

Expected: `length` within a few points of each other and `points` identical or differing only in the last few characters (OTP may simplify or re-project). A `length` that differs by a large factor, or a polyline that decodes to a different place, means the stitching order or the lat/lon axis order is wrong — check `PosListParser` output order against `gis:posList` in the source file for that pattern before anything else.

Paste the two results into the PR description. No commit.

---

## Self-review

**Spec coverage:**
- Components (`PlannedDataset`, extractor, `PlannedDataService`, changed services, deletions) → Tasks 1–8 ✔
- Configuration keys and defaults → Tasks 6, 8 ✔
- Geometry (stitch, gap, encode, `length`, lazy cache, null for none) → Task 2 ✔
- Error handling table: startup throw (T6), nightly keep (T6), entry skip (T5), zero line files (T5), < 50% guard (T6), dangling refs counted (T1), duplicates last-wins (T1), disabled → bare refs (T6, T7) ✔
- Observability metrics → Task 6 ✔
- Deployment: probe threshold (T8 initial, T9 measured), memory check (T9), README/CLAUDE.md (T8) ✔
- Testing: extractor fragments (T4), geometry incl. OTP comparison (T2 unit, T10 manual), GOA integration (T5), service tests (T7), reload tests (T6), manual perf run (T9) ✔
- Non-goals respected: `Query.lines/operators/serviceJourneys` untouched; `NSRService` untouched; no straight-line fallback ✔

**Type consistency check:** `PlannedDataset.Builder.addServiceLink(String, int[])` used identically in T1/T2/T4; `Stats` accessor names (`unresolvedLinkRefs()` etc.) match between T1 definition and T4/T5/T6 use; `PlannedDataService(boolean, String, PlannedDataLoader, PrometheusMetricsService)` matches between T6 definition and T7 tests; `findPointsOnLink(String serviceJourneyId)` takes an SJ id (not a pattern id) everywhere; `DatedJourneyRef(serviceJourneyId, operatingDate)` accessor names match in T1/T5/T7.
