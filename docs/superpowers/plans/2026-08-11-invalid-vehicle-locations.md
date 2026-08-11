# Invalid Vehicle Locations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vehicles reported at known-invalid coordinates (`0,0`, `-1,-1`, `1,1`) are excluded from the `vehicles` query and subscription unless the client passes `includeInvalidLocations: true`.

**Architecture:** A new `InvalidLocationRegistry` Spring bean parses a configured list of invalid lat/lon pairs and answers `isInvalid(Location)`. `QueryFilter` gains an optional location-validity check that all vehicle read paths already funnel through (`Query.getVehicles` → `VehicleRepository.getVehicles(filter)`, and `Subscription.vehicles` → `VehicleUpdateRxPublisher`, whose `.filter(...)` stage sits after `startWith(initialdata)` and so covers both the initial snapshot and live updates). Storage, ingestion and enrichment are untouched.

**Tech Stack:** Java (Spring Boot 3 / Spring GraphQL), Maven, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-11-invalid-vehicle-locations-design.md`

## Global Constraints

- Package root is `org.entur.vehicles`. Source in `src/main/java`, tests in `src/test/java` mirroring the package.
- Build/test command is Maven: `mvn test -Dtest=<ClassName>` for a single class, `mvn test` for the suite.
- Default invalid coordinates, in `latitude/longitude` order: `0.0/0.0,-1.0/-1.0,1.0/1.0`.
- Configuration property name: `vehicle.invalid.locations`.
- GraphQL argument name and default: `includeInvalidLocations: Boolean = false`, on `Query.vehicles` and `Subscription.vehicles` only — never on `timetables` or `situations`.
- **Trap:** `org.entur.vehicles.data.model.Location`'s constructor is `Location(double longitude, double latitude)` — **longitude first**. Its getters are `getLatitude()` / `getLongitude()` and return boxed `Double`.
- Existing behaviour must not change when the new wiring is absent: a `QueryFilter` on which the new setter was never called (timetable filters, situation filters, existing tests) filters exactly as it does today.
- Indentation in this codebase is 2 spaces in `data/`, `repository/` and `service/`; 4 spaces in `graphql/`. Match the file you are editing.

---

### Task 1: `InvalidLocationRegistry`

The registry owns the definition of "invalid location": it parses the configured pairs once at construction and answers `isInvalid(Location)`. Nothing else in the codebase knows which coordinates are invalid.

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/InvalidLocationRegistry.java`
- Modify: `src/main/resources/application.properties` (append a new property)
- Test: `src/test/java/org/entur/vehicles/service/InvalidLocationRegistryTest.java`

**Interfaces:**
- Consumes: `org.entur.vehicles.data.model.Location` (existing).
- Produces:
  - `public InvalidLocationRegistry(String invalidLocations)` — single constructor, annotated with `@Value("${vehicle.invalid.locations:0.0/0.0,-1.0/-1.0,1.0/1.0}")` on the parameter, so tests can construct it directly with a plain string.
  - `public boolean isInvalid(Location location)` — `false` for `null` location or `null` coordinate components.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/service/InvalidLocationRegistryTest.java`:

```java
package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidLocationRegistryTest {

  private static final String DEFAULT_CONFIG = "0.0/0.0,-1.0/-1.0,1.0/1.0";

  // NOTE: Location takes (longitude, latitude) - longitude first.
  private static Location location(double latitude, double longitude) {
    return new Location(longitude, latitude);
  }

  @Test
  void theDefaultConfiguredCoordinatesAreInvalid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(location(0.0, 0.0))).isTrue();
    assertThat(registry.isInvalid(location(-1.0, -1.0))).isTrue();
    assertThat(registry.isInvalid(location(1.0, 1.0))).isTrue();
  }

  @Test
  void aRealCoordinateIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    // Oslo
    assertThat(registry.isInvalid(location(59.911491, 10.757933))).isFalse();
  }

  @Test
  void aCoordinateMatchingOnlyOneComponentIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(location(0.0, 10.757933))).isFalse();
    assertThat(registry.isInvalid(location(59.911491, 0.0))).isFalse();
  }

  @Test
  void negativeZeroMatchesAConfiguredZero() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(location(-0.0, -0.0))).isTrue();
  }

  @Test
  void aNullLocationIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(null)).isFalse();
  }

  @Test
  void aLocationWithNullComponentsIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    Location location = location(0.0, 0.0);
    location.setLatitude(null);

    assertThat(registry.isInvalid(location)).isFalse();
  }

  @Test
  void aCustomConfigurationReplacesTheDefaults() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("12.5/13.5");

    assertThat(registry.isInvalid(location(12.5, 13.5))).isTrue();
    assertThat(registry.isInvalid(location(0.0, 0.0))).isFalse();
  }

  @Test
  void whitespaceAroundEntriesIsIgnored() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("  0.0 / 0.0 ,  1.0/1.0  ");

    assertThat(registry.isInvalid(location(0.0, 0.0))).isTrue();
    assertThat(registry.isInvalid(location(1.0, 1.0))).isTrue();
  }

  @Test
  void aMalformedEntryIsSkippedAndTheRestStillParsed() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,not-a-coordinate,7.0/8.0/9.0,1.0/1.0");

    assertThat(registry.isInvalid(location(0.0, 0.0))).isTrue();
    assertThat(registry.isInvalid(location(1.0, 1.0))).isTrue();
  }

  @Test
  void anEmptyConfigurationDisablesTheRegistry() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("");

    assertThat(registry.isInvalid(location(0.0, 0.0))).isFalse();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=InvalidLocationRegistryTest`
Expected: FAIL — compilation error, `cannot find symbol: class InvalidLocationRegistry`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/entur/vehicles/service/InvalidLocationRegistry.java`:

```java
package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Coordinates that upstream data producers are known to report for vehicles whose real position
 * is unknown - e.g. {@code 0,0}. Such vehicles are still stored and still queryable, but are
 * excluded from responses unless the client explicitly asks for them.
 * <p>
 * The list is configuration rather than code so that a new variant of the same upstream bug can
 * be handled by a config change and redeploy. Matching is exact: only the configured pairs are
 * treated as invalid.
 */
@Component
public class InvalidLocationRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(InvalidLocationRegistry.class);

  private final Set<Coordinate> invalidCoordinates;

  public InvalidLocationRegistry(
      @Value("${vehicle.invalid.locations:0.0/0.0,-1.0/-1.0,1.0/1.0}") String invalidLocations
  ) {
    this.invalidCoordinates = parse(invalidLocations);
    LOG.info("Treating {} coordinate(s) as invalid vehicle locations: {}",
        invalidCoordinates.size(), invalidCoordinates);
  }

  public boolean isInvalid(Location location) {
    if (location == null || location.getLatitude() == null || location.getLongitude() == null) {
      return false;
    }
    return invalidCoordinates.contains(
        new Coordinate(normalize(location.getLatitude()), normalize(location.getLongitude()))
    );
  }

  private static Set<Coordinate> parse(String invalidLocations) {
    Set<Coordinate> coordinates = new HashSet<>();
    if (invalidLocations == null || invalidLocations.isBlank()) {
      return coordinates;
    }
    for (String entry : invalidLocations.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] parts = trimmed.split("/");
      if (parts.length != 2) {
        LOG.warn("Ignoring malformed entry '{}' in vehicle.invalid.locations - expected 'latitude/longitude'", trimmed);
        continue;
      }
      try {
        coordinates.add(new Coordinate(
            normalize(Double.parseDouble(parts[0].trim())),
            normalize(Double.parseDouble(parts[1].trim()))
        ));
      } catch (NumberFormatException e) {
        LOG.warn("Ignoring entry '{}' in vehicle.invalid.locations - not a numeric coordinate pair", trimmed);
      }
    }
    return coordinates;
  }

  /**
   * {@code -0.0} and {@code 0.0} are distinct values to {@code Double.equals}, so a feed
   * reporting {@code -0.0} would not match a configured {@code 0.0} without this.
   */
  private static double normalize(double value) {
    return value == 0.0 ? 0.0 : value;
  }

  private record Coordinate(double latitude, double longitude) {
  }
}
```

- [ ] **Step 4: Add the property**

Append to `src/main/resources/application.properties`, at the end of the file:

```properties
# Coordinates reported by upstream producers for vehicles whose real position is unknown.
# Comma-separated latitude/longitude pairs, matched exactly. Vehicles at these coordinates are
# still stored and still queryable, but are only returned when a client passes
# includeInvalidLocations: true.
vehicle.invalid.locations=0.0/0.0,-1.0/-1.0,1.0/1.0
```

The same values are the `@Value` default in the bean, so tests that boot a Spring context (which use `src/test/resources/application.properties`, where the property is not set) get the documented behaviour without further configuration.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=InvalidLocationRegistryTest`
Expected: PASS — 10 tests.

- [ ] **Step 6: Run the existing Spring-context test to confirm the new bean wires**

Run: `mvn test -Dtest=ApplicationGraphQlSchemaTests`
Expected: PASS. This test boots a real Spring context, so it constructs the new `@Component` and would fail if the `@Value` placeholder could not be resolved.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/InvalidLocationRegistry.java \
        src/test/java/org/entur/vehicles/service/InvalidLocationRegistryTest.java \
        src/main/resources/application.properties
git commit -m "Add InvalidLocationRegistry for known-invalid vehicle coordinates"
```

---

### Task 2: Location-validity check in `QueryFilter`

`QueryFilter.isMatch(VehicleUpdate)` is the single choke point for every vehicle response path, so the check goes there. The two existing constructors already take 14 and 16 arguments and are called from eight test sites, so the new state is supplied by a fluent setter instead of growing them.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/data/QueryFilter.java`
- Test: `src/test/java/org/entur/vehicles/data/QueryFilterTest.java` (append tests, leave existing ones untouched)

**Interfaces:**
- Consumes: `InvalidLocationRegistry.isInvalid(Location)` from Task 1.
- Produces: `public QueryFilter withLocationValidity(InvalidLocationRegistry invalidLocations, boolean includeInvalidLocations)` — returns `this` so it can be chained onto construction. When never called, `isMatch` behaves exactly as before.

- [ ] **Step 1: Write the failing tests**

Append these tests to `src/test/java/org/entur/vehicles/data/QueryFilterTest.java`, inside the existing `QueryFilterTest` class (before the final closing brace). Also add the imports `org.entur.vehicles.data.model.Location` and `org.entur.vehicles.service.InvalidLocationRegistry` to the file's import block:

```java
  private static QueryFilter emptyFilter() {
    return new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            null, null,
            null, null, null, null
    );
  }

  private static VehicleUpdate vehicleAt(double latitude, double longitude) {
    VehicleUpdate update = new VehicleUpdate();
    // NOTE: Location takes (longitude, latitude) - longitude first.
    update.setLocation(new Location(longitude, latitude));
    return update;
  }

  @Test
  void testInvalidLocationExcludedByDefault() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0");
    QueryFilter filter = emptyFilter().withLocationValidity(registry, false);

    assertFalse(filter.isMatch(vehicleAt(0.0, 0.0)));
    assertFalse(filter.isMatch(vehicleAt(-1.0, -1.0)));
    assertFalse(filter.isMatch(vehicleAt(1.0, 1.0)));
  }

  @Test
  void testValidLocationMatchesRegardlessOfFlag() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0");

    assertTrue(emptyFilter().withLocationValidity(registry, false).isMatch(vehicleAt(59.911491, 10.757933)));
    assertTrue(emptyFilter().withLocationValidity(registry, true).isMatch(vehicleAt(59.911491, 10.757933)));
  }

  @Test
  void testInvalidLocationIncludedWhenRequested() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0");
    QueryFilter filter = emptyFilter().withLocationValidity(registry, true);

    assertTrue(filter.isMatch(vehicleAt(0.0, 0.0)));
  }

  @Test
  void testFilterWithoutLocationValidityIsUnchanged() {
    // Timetable and situation filters - and every pre-existing test - never call
    // withLocationValidity, and must keep matching invalid locations.
    assertTrue(emptyFilter().isMatch(vehicleAt(0.0, 0.0)));
  }

  @Test
  void testNullRegistryDisablesTheCheck() {
    assertTrue(emptyFilter().withLocationValidity(null, false).isMatch(vehicleAt(0.0, 0.0)));
  }

  @Test
  void testInvalidLocationExcludedEvenWhenOtherCriteriaMatch() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0");
    QueryFilter filter = new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            "TST:Line:123",
            null,
            null, null, null, null
    ).withLocationValidity(registry, false);

    VehicleUpdate update = vehicleAt(0.0, 0.0);
    update.setLine(new Line("TST:Line:123", "A - B"));

    assertFalse(filter.isMatch(update));
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=QueryFilterTest`
Expected: FAIL — compilation error, `cannot find symbol: method withLocationValidity(...)`.

- [ ] **Step 3: Implement the check**

In `src/main/java/org/entur/vehicles/data/QueryFilter.java`:

Add the import alongside the existing `org.entur.vehicles.service.OperatorService` import:

```java
import org.entur.vehicles.service.InvalidLocationRegistry;
```

Add two fields after the existing `private MetricType metricType = UNDEFINED;` declaration:

```java
  private InvalidLocationRegistry invalidLocations;
  private boolean includeInvalidLocations;
```

Add the setter directly after the constructors, above `getBufferSize()`:

```java
  /**
   * Enables filtering of vehicles reported at known-invalid coordinates. Only the vehicle query
   * and subscription call this; filters that never do - timetables and situations - keep matching
   * every location.
   */
  public QueryFilter withLocationValidity(InvalidLocationRegistry invalidLocations, boolean includeInvalidLocations) {
    this.invalidLocations = invalidLocations;
    this.includeInvalidLocations = includeInvalidLocations;
    return this;
  }
```

In `isMatch(VehicleUpdate vehicleUpdate)`, insert this block immediately after the `boundingBox` block and before the `serviceJourneys` block:

```java
    if (isCompleteMatch && !includeInvalidLocations && invalidLocations != null) {
      isCompleteMatch = !invalidLocations.isInvalid(vehicleUpdate.getLocation());
    }
```

Add one line to `toString()`, after the `.add("boundingBox=" + boundingBox)` line, so the debug logging in `Query` and `Subscription` shows the flag:

```java
        .add("includeInvalidLocations=" + includeInvalidLocations)
```

Do not touch `isMatch(EstimatedTimetableUpdate)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=QueryFilterTest`
Expected: PASS — the five pre-existing tests plus the six new ones.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/QueryFilter.java \
        src/test/java/org/entur/vehicles/data/QueryFilterTest.java
git commit -m "Filter vehicles at invalid locations in QueryFilter"
```

---

### Task 3: Expose `includeInvalidLocations` in the GraphQL API

Wires the schema argument through both resolvers. `Query` and `Subscription` each gain an `InvalidLocationRegistry` constructor dependency; four existing test call sites construct `Query` directly and must be updated in the same commit or the build will not compile.

**Files:**
- Modify: `src/main/resources/graphql/vehicle-updates.graphqls` (`Query.vehicles` around line 16-32, `Subscription.vehicles` around line 77-98)
- Modify: `src/main/java/org/entur/vehicles/graphql/Query.java`
- Modify: `src/main/java/org/entur/vehicles/graphql/Subscription.java`
- Modify: `src/test/java/org/entur/vehicles/graphql/SituationGraphQLTests.java:90,270` and `src/test/java/org/entur/vehicles/graphql/TimetableGraphQLTests.java:54` (constructor call sites only)
- Test: `src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java`

**Interfaces:**
- Consumes: `InvalidLocationRegistry` (Task 1), `QueryFilter.withLocationValidity(...)` (Task 2).
- Produces:
  - `Query(VehicleRepository, TimetableRepository, SituationRepository, NSRService, PrometheusMetricsService, InvalidLocationRegistry)` — registry appended as the last parameter.
  - `Subscription(VehicleUpdateRxPublisher, EstimatedTimetableUpdateRxPublisher, SituationUpdateRxPublisher, NSRService, PrometheusMetricsService, InvalidLocationRegistry)` — registry appended as the last parameter.
  - `Query.getVehicles(...)` gains a final `@Argument Boolean includeInvalidLocations` parameter, after `maxDataAge`.
  - `Subscription.vehicles(...)` gains `@Argument Boolean includeInvalidLocations` after `maxDataAge` and **before** `bufferSize`/`bufferTime`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java`. Add the imports `java.util.Collection`, `org.entur.vehicles.data.VehicleUpdate` and `org.entur.vehicles.service.InvalidLocationRegistry`, then append these members inside the existing class:

```java
    private VehicleActivityRecord createVehicleActivity(String codespace, String vehicleRef, String lineRef,
                                                        double latitude, double longitude) {
        VehicleActivityRecord record = new VehicleActivityRecord();
        record.setRecordedAtTime(ZonedDateTime.now().toString());
        record.setValidUntilTime(ZonedDateTime.now().plusMinutes(10).toString());

        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setLineRef(lineRef);
        journey.setVehicleRef(vehicleRef);
        journey.setMonitored(true);
        journey.setDataSource(codespace);

        LocationRecord location = new LocationRecord();
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        journey.setVehicleLocation(location);

        record.setMonitoredVehicleJourney(journey);
        return record;
    }

    private Collection<VehicleUpdate> queryVehicles(String codespaceId, Boolean includeInvalidLocations) {
        return queryService.getVehicles(
                null,   // serviceJourneyId
                null,   // date
                null,   // serviceJourneyIdAndDates
                null,   // datedServiceJourneyId
                null,   // datedServiceJourneyIds
                null,   // operatorRef
                codespaceId,
                null,   // mode
                null,   // vehicleId
                null,   // vehicleIds
                null,   // lineRef
                null,   // lineName
                null,   // monitored
                null,   // boundingBox
                null,   // maxDataAge
                includeInvalidLocations
        );
    }

    @Test
    public void testInvalidLocationsAreExcludedUnlessRequested() {
        repository.addAll(List.of(
                createVehicleActivity("INV", "INV:Vehicle:valid", "INV:Line:1", 59.911491, 10.757933),
                createVehicleActivity("INV", "INV:Vehicle:invalid", "INV:Line:1", 0.0, 0.0)
        ));

        // Argument omitted by the client - GraphQL applies the schema default of false
        Collection<VehicleUpdate> defaultResult = queryVehicles("INV", null);
        assertEquals(1, defaultResult.size());
        assertEquals("INV:Vehicle:valid", defaultResult.iterator().next().getVehicleId());

        Collection<VehicleUpdate> explicitlyExcluded = queryVehicles("INV", false);
        assertEquals(1, explicitlyExcluded.size());

        Collection<VehicleUpdate> included = queryVehicles("INV", true);
        assertEquals(2, included.size());
        assertTrue(included.stream().anyMatch(v -> "INV:Vehicle:invalid".equals(v.getVehicleId())));
    }
```

Update the `Query` construction in `initData()` (currently line 54) to pass a registry:

```java
        queryService = new Query(repository, null, null, new NSRService(false, null), metricsService,
                new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0"));
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=VehicleGraphQLTests`
Expected: FAIL — compilation error, `constructor Query ... cannot be applied to given types` and `method getVehicles ... cannot be applied to given types`.

- [ ] **Step 3: Update the schema**

In `src/main/resources/graphql/vehicle-updates.graphqls`, in the **`Query.vehicles`** field, replace the final argument line:

```graphql
        maxDataAge: Duration,) : [VehicleUpdate]
```

with:

```graphql
        maxDataAge: Duration,
        # Include vehicles whose reported coordinates are known to be invalid (e.g. 0,0).
        # Such vehicles are excluded by default.
        includeInvalidLocations: Boolean = false) : [VehicleUpdate]
```

In the **`Subscription.vehicles`** field, insert the same argument after the `maxDataAge: Duration,` line and before the `bufferSize` comment:

```graphql
                   # Include vehicles whose reported coordinates are known to be invalid (e.g. 0,0).
                   # Such vehicles are excluded by default.
                   includeInvalidLocations: Boolean = false
```

Leave `timetables` and `situations` untouched in both `Query` and `Subscription`.

- [ ] **Step 4: Wire `Query`**

In `src/main/java/org/entur/vehicles/graphql/Query.java`:

Add the import next to the existing `org.entur.vehicles.service.NSRService` import:

```java
import org.entur.vehicles.service.InvalidLocationRegistry;
```

Add the field after `private final NSRService nsrService;`:

```java
    private final InvalidLocationRegistry invalidLocationRegistry;
```

Extend the constructor:

```java
    public Query(VehicleRepository vehicleRepository,
                 TimetableRepository timetableRepository,
                 SituationRepository situationRepository,
                 NSRService nsrService,
                 PrometheusMetricsService metricsService,
                 InvalidLocationRegistry invalidLocationRegistry) {
        this.vehicleRepository = vehicleRepository;
        this.timetableRepository = timetableRepository;
        this.situationRepository = situationRepository;
        this.nsrService = nsrService;
        this.metricsService = metricsService;
        this.invalidLocationRegistry = invalidLocationRegistry;
    }
```

Add the argument to `getVehicles`, after `@Argument Duration maxDataAge`:

```java
                                          @Argument Duration maxDataAge,
                                          @Argument Boolean includeInvalidLocations) {
```

And chain the setter onto the filter construction — the existing statement ends with `);` on the line after `maxDataAge`:

```java
        final QueryFilter filter = new QueryFilter(
                metricsService,
                MetricType.QUERY,
                serviceJourneyIdAndDates,
                datedServiceJourneyIds,
                operatorRef,
                codespaceId,
                mode,
                vehicleIds,
                lineRef,
                lineName,
                monitored,
                false, // cancellation is not used in vehicle queries
                boundingBox,
                maxDataAge
        ).withLocationValidity(invalidLocationRegistry, Boolean.TRUE.equals(includeInvalidLocations));
```

`Boolean.TRUE.equals(...)` treats a client-supplied explicit `null` as `false`, matching the schema default.

- [ ] **Step 5: Wire `Subscription`**

In `src/main/java/org/entur/vehicles/graphql/Subscription.java`, make the same three changes:

Import:

```java
import org.entur.vehicles.service.InvalidLocationRegistry;
```

Field after `private final NSRService nsrService;`:

```java
    private final InvalidLocationRegistry invalidLocationRegistry;
```

Constructor:

```java
    Subscription(VehicleUpdateRxPublisher vehicleUpdater,
                 EstimatedTimetableUpdateRxPublisher timetableUpdater,
                 SituationUpdateRxPublisher situationUpdater,
                 NSRService nsrService,
                 PrometheusMetricsService metricsService,
                 InvalidLocationRegistry invalidLocationRegistry) {
        this.vehicleUpdater = vehicleUpdater;
        this.timetableUpdater = timetableUpdater;
        this.situationUpdater = situationUpdater;
        this.nsrService = nsrService;
        this.metricsService = metricsService;
        this.invalidLocationRegistry = invalidLocationRegistry;
    }
```

Argument on `vehicles(...)`, after `@Argument Duration maxDataAge,` and before `@Argument Integer bufferSize`:

```java
                                            @Argument Boolean includeInvalidLocations,
```

Filter construction in `vehicles(...)` — the one built with `MetricType.SUBSCRIPTION`, `bufferSize` and `bufferTime`:

```java
        final QueryFilter filter = new QueryFilter(
                metricsService,
                MetricType.SUBSCRIPTION,
                serviceJourneyIdAndDates,
                datedServiceJourneyIds,
                operatorRef,
                codespaceId,
                mode,
                vehicleIds,
                lineRef,
                lineName,
                monitored,
                null, // cancellation is not used in vehicle updates
                boundingBox,
                maxDataAge,
                bufferSize,
                bufferTime
        ).withLocationValidity(invalidLocationRegistry, Boolean.TRUE.equals(includeInvalidLocations));
```

Leave the `timetables` and `situations` subscription methods unchanged.

- [ ] **Step 6: Update the other `Query` construction sites**

These three call sites do not query vehicles, so they pass `null` for the registry — with a `null` registry the location check is skipped, exactly as before this change:

`src/test/java/org/entur/vehicles/graphql/SituationGraphQLTests.java:90`:

```java
        queryService = new Query(null, null, repository, new NSRService(false, null), metricsService, null);
```

`src/test/java/org/entur/vehicles/graphql/SituationGraphQLTests.java:270`:

```java
        Query ancestorQueryService = new Query(null, null, repository, ancestorAwareNsrService, metricsService, null);
```

`src/test/java/org/entur/vehicles/graphql/TimetableGraphQLTests.java:54`:

```java
        queryService = new Query(null, repository, null, new NSRService(false, null), metricsService, null);
```

- [ ] **Step 7: Run the vehicle tests to verify they pass**

Run: `mvn test -Dtest=VehicleGraphQLTests`
Expected: PASS — `testQueries` and `testInvalidLocationsAreExcludedUnlessRequested`.

- [ ] **Step 8: Run the full suite**

Run: `mvn test`
Expected: PASS, all tests. `ApplicationGraphQlSchemaTests` boots a real Spring context and executes GraphQL documents, so it verifies the schema still parses and that both resolvers still map to their fields with the new argument in place.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/graphql/vehicle-updates.graphqls \
        src/main/java/org/entur/vehicles/graphql/Query.java \
        src/main/java/org/entur/vehicles/graphql/Subscription.java \
        src/test/java/org/entur/vehicles/graphql/VehicleGraphQLTests.java \
        src/test/java/org/entur/vehicles/graphql/SituationGraphQLTests.java \
        src/test/java/org/entur/vehicles/graphql/TimetableGraphQLTests.java
git commit -m "Add includeInvalidLocations argument to vehicles query and subscription"
```

---

## Verification

After Task 3, confirm against the spec's behaviour table:

- `mvn test` passes in full.
- `git diff main --stat` shows changes only in: the new registry and its test, `QueryFilter` and its test, the schema, the two resolvers, and the four test call sites, plus `application.properties`.
- No change to `VehicleRepository`, `AutoPurgingVehicleMap`, `VehicleUpdateRxPublisher`, `TimetableRepository` or `SituationRepository` — invalid-location vehicles are still ingested, stored, enriched and purged exactly as before.
