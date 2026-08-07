# Resolving StopPlace-tagged situations to their quays — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A situation tagged on a StopPlace reaches the calls at that stop place's quays — including when it is tagged on a multimodal parent above that stop place.

**Architecture:** `NSRService` retains a child→ancestors map it already reads from NeTEx and currently discards, flattened once at startup. Three consumers that today match a stop on its literal id — the ET join, the standalone `situations` filter, and the republisher — each climb that map. Nothing resolved is ever stored back onto `Affects`.

**Tech Stack:** Java 21, Spring Boot, `org.entur:netex-parser-java:4.0.0`, JUnit 5 + Mockito + AssertJ.

**Design spec:** `docs/superpowers/specs/2026-08-07-sx-stopplace-quay-resolution-design.md`

## Global Constraints

- Package root is `org.entur.vehicles`. Branch is `siri_sx_stopplace_quays`, already created off `siri_sx_api`. Do not create another branch.
- The SX feature, the ET join, and situation-triggered republishing all already exist and are committed. Do not reimplement any of them.
- **Resolution climbs, never descends.** Any ref expands to itself plus its ancestors. A stop place never resolves to its quays. Asserting this non-goal is a required test, not optional.
- **Nothing resolved is written back onto `Affects`.** `affects { stopPoints }` and `affects { stopPlaces }` must keep returning exactly what the producer named.
- No new GraphQL field, no schema change. The `stopRef: String` argument stays a single string.
- `SituationMatcher` must remain free of Spring and GraphQL dependencies — that is what lets the match rule be unit-tested directly.
- A null `stopRef` argument must keep meaning **"no filter"**, never "match nothing". Getting this wrong silently empties every unfiltered `situations` query.
- With `vehicle.nsr.lookup.enabled=false` (the default locally and in tests) behaviour must be identical to today.
- `TimetableRepository`, `SituationMapper`, `AutoPurgingTimetableMap`, the SX ingest paths and everything VM-related must NOT be modified.
- No new dependency in `pom.xml`. No test may perform network I/O — in particular, no test may download or parse a NeTEx file.
- Build and test with `mvn`. Full suite: `mvn clean test` — **170 tests currently pass.**
- No Claude/AI attribution in commit messages — match the existing terse style (`git log --oneline`).

## Two facts that shape the work

**The quay→stop-place map already exists.** `NetexEntitiesIndex.getStopPlaceIdByQuayIdIndex()` returns a ready-made `Map<String, String>`. Do not hand-roll it from the quay loop.

**The parent link is on the stop place.** `Site_VersionStructure.getParentSiteRef()` (inherited by `StopPlace`) returns a `SiteRefStructure`, whose id comes from `VersionOfObjectRefStructure.getRef()`. Both verified against `netex-parser-java:4.0.0`.

## Why there are two lookup methods

`ancestorsOf(ref)` returns the **stored** set — no allocation. `expandWithAncestors(ref)` allocates `{ref} ∪ ancestors`.

That split is deliberate and load-bearing. `SituationTriggeredRepublisher.findAffected` walks every call of every stored journey on every situation change; allocating a fresh set per call there would mean hundreds of thousands of throwaway sets per scan. It uses `ancestorsOf`. The standalone filter runs once per query and uses the convenience form.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/org/entur/vehicles/service/NSRService.java` (modify) | Build and expose the child→ancestors map |
| `src/main/java/org/entur/vehicles/data/SituationMatcher.java` (modify) | Climb ancestors when matching a call |
| `src/main/java/org/entur/vehicles/graphql/SituationJoinController.java` (modify) | Supply the resolver to the matcher |
| `src/main/java/org/entur/vehicles/data/SituationFilter.java` (modify) | `stopRef` becomes `stopRefs`, disjoint check |
| `src/main/java/org/entur/vehicles/graphql/Query.java` (modify) | Resolve before constructing the filter |
| `src/main/java/org/entur/vehicles/graphql/Subscription.java` (modify) | Resolve before constructing the filter |
| `src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java` (modify) | Climb ancestors so subscribers are told |
| `src/main/resources/Usage.md` (modify) | Document the behaviour |
| `src/test/java/org/entur/vehicles/service/NSRServiceAncestorTest.java` (new) | Flattening, cycles, depth cap, lookup |

## Task Overview

| # | Deliverable |
|---|---|
| 1 | `NSRService` ancestor map — flattening, cycle/depth guards, the two lookups |
| 2 | The ET join climbs ancestors |
| 3 | The standalone `situations` filter climbs ancestors |
| 4 | The republisher climbs ancestors, plus docs and the end-to-end proof |

---

### Task 1: The ancestor map

Two pure static functions plus thin wiring. No network, no Spring lifecycle under test.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/NSRService.java`
- Test: `src/test/java/org/entur/vehicles/service/NSRServiceAncestorTest.java`

**Interfaces:**
- Consumes: `NetexEntitiesIndex.getStopPlaceIdByQuayIdIndex()`, `StopPlace.getParentSiteRef()`.
- Produces:
  - `static Map<String, Set<String>> flattenAncestors(Map<String, String> childToParent)` — package-private, pure, cycle- and depth-guarded.
  - `Set<String> ancestorsOf(String stopRef)` — the stored set, or `Set.of()`. Never allocates.
  - `Set<String> expandWithAncestors(String stopRef)` — `{stopRef} ∪ ancestorsOf(stopRef)`. Returns `Set.of()` for a null ref.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/service/NSRServiceAncestorTest.java`:

```java
package org.entur.vehicles.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flattening is a pure static function so it can be tested without a NeTEx file -
 * NSRService's real warm-up downloads and parses a multi-megabyte zip, which no test may do.
 */
public class NSRServiceAncestorTest {

    @Test
    public void testResolvesAQuayToItsStopPlace() {
        Map<String, String> childToParent = Map.of("NSR:Quay:749", "NSR:StopPlace:451");

        assertThat(NSRService.flattenAncestors(childToParent))
                .containsExactly(Map.entry("NSR:Quay:749", Set.of("NSR:StopPlace:451")));
    }

    @Test
    public void testClimbsThroughAMultimodalParent() {
        Map<String, String> childToParent = new LinkedHashMap<>();
        childToParent.put("NSR:Quay:749", "NSR:StopPlace:451");
        childToParent.put("NSR:StopPlace:451", "NSR:StopPlace:999");

        Map<String, Set<String>> flattened = NSRService.flattenAncestors(childToParent);

        assertThat(flattened.get("NSR:Quay:749"))
                .withFailMessage("a situation on the multimodal parent must still reach the quay")
                .containsExactlyInAnyOrder("NSR:StopPlace:451", "NSR:StopPlace:999");
        assertThat(flattened.get("NSR:StopPlace:451")).containsExactly("NSR:StopPlace:999");
    }

    @Test
    public void testACircularChainDoesNotHang() {
        Map<String, String> childToParent = new LinkedHashMap<>();
        childToParent.put("NSR:StopPlace:1", "NSR:StopPlace:2");
        childToParent.put("NSR:StopPlace:2", "NSR:StopPlace:1");

        Map<String, Set<String>> flattened = NSRService.flattenAncestors(childToParent);

        assertThat(flattened.get("NSR:StopPlace:1"))
                .withFailMessage("the climb must stop when it revisits a ref, keeping what it found")
                .containsExactly("NSR:StopPlace:2");
        assertThat(flattened.get("NSR:StopPlace:2")).containsExactly("NSR:StopPlace:1");
    }

    @Test
    public void testASelfReferencingParentIsNotItsOwnAncestor() {
        Map<String, String> childToParent = Map.of("NSR:StopPlace:1", "NSR:StopPlace:1");

        assertThat(NSRService.flattenAncestors(childToParent))
                .withFailMessage("a self-loop yields no ancestors, so the ref must be absent entirely")
                .isEmpty();
    }

    @Test
    public void testAChainDeeperThanTheCapIsTruncatedNotDropped() {
        Map<String, String> childToParent = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            childToParent.put("NSR:StopPlace:" + i, "NSR:StopPlace:" + (i + 1));
        }

        assertThat(NSRService.flattenAncestors(childToParent).get("NSR:StopPlace:0"))
                .withFailMessage("the depth cap must truncate the climb, not discard the ref")
                .hasSize(10);
    }

    @Test
    public void testARefWithNoParentIsAbsent() {
        assertThat(NSRService.flattenAncestors(Map.of())).isEmpty();
    }

    @Test
    public void testAncestorsOfIsEmptyWhenLookupIsDisabled() {
        NSRService service = new NSRService(false, "");

        assertThat(service.ancestorsOf("NSR:Quay:749")).isEmpty();
        assertThat(service.ancestorsOf(null)).isEmpty();
    }

    @Test
    public void testExpandWithAncestorsFallsBackToTheRefItself() {
        NSRService service = new NSRService(false, "");

        assertThat(service.expandWithAncestors("NSR:Quay:749"))
                .withFailMessage("with no ancestor data the caller must still get a usable ref back, "
                        + "so behaviour is unchanged when NSR lookup is disabled")
                .containsExactly("NSR:Quay:749");
    }

    @Test
    public void testExpandWithAncestorsOfNullIsEmpty() {
        NSRService service = new NSRService(false, "");

        assertThat(service.expandWithAncestors(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o test -Dtest=NSRServiceAncestorTest`
Expected: FAIL — compilation error; `flattenAncestors`, `ancestorsOf` and `expandWithAncestors` do not exist.

- [ ] **Step 3: Add the ancestor map to NSRService**

In `src/main/java/org/entur/vehicles/service/NSRService.java`, add these imports:

```java
import org.rutebanken.netex.model.SiteRefStructure;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
```

Add the field and constant beside the existing `stopPointCache` field:

```java
    /**
     * How far the climb from a quay to its ancestors may go. NeTEx data is external and outside
     * this service's control, so a malformed or absurdly deep ParentSiteRef chain must not be
     * able to stall startup.
     */
    private static final int MAX_ANCESTOR_DEPTH = 10;

    /**
     * Every ancestor above a ref: for a quay, its stop place and any multimodal parent above
     * that. Empty when NSR lookup is disabled, which makes every consumer fall back to literal
     * stop matching - exactly today's behaviour.
     */
    private final Map<String, Set<String>> ancestorsByRef = new ConcurrentHashMap<>();
```

Add both lookups:

```java
    /**
     * Every ancestor above this ref, nearest first. Returns the stored set rather than a copy,
     * so this allocates nothing - {@code SituationTriggeredRepublisher} calls it for every call
     * of every stored journey on every situation change.
     */
    public Set<String> ancestorsOf(String stopRef) {
        if (stopRef == null) {
            return Set.of();
        }
        return ancestorsByRef.getOrDefault(stopRef, Set.of());
    }

    /**
     * This ref plus every ancestor above it. Allocates, so prefer {@link #ancestorsOf} on a hot
     * path. A null ref yields an empty set; an unknown ref yields just itself, so a caller never
     * has to special-case missing NeTEx data.
     */
    public Set<String> expandWithAncestors(String stopRef) {
        if (stopRef == null) {
            return Set.of();
        }
        Set<String> ancestors = ancestorsOf(stopRef);
        if (ancestors.isEmpty()) {
            return Set.of(stopRef);
        }
        Set<String> expanded = new LinkedHashSet<>();
        expanded.add(stopRef);
        expanded.addAll(ancestors);
        return Collections.unmodifiableSet(expanded);
    }

    /**
     * Collapses a child-to-parent map into a child-to-all-ancestors map, once, at startup.
     * <p>
     * Flattening here rather than walking the chain per lookup keeps lookup O(1), which matters
     * because {@code SituationTriggeredRepublisher}'s scan sits on a hot path.
     * <p>
     * The climb stops on revisiting a ref or on reaching {@link #MAX_ANCESTOR_DEPTH}, keeping
     * whatever it found so far. Both guards exist because this data is external: a circular
     * ParentSiteRef must degrade to partial resolution, never to a hung startup.
     */
    static Map<String, Set<String>> flattenAncestors(Map<String, String> childToParent) {
        Map<String, Set<String>> flattened = new HashMap<>();
        for (String child : childToParent.keySet()) {
            Set<String> ancestors = new LinkedHashSet<>();
            Set<String> visited = new HashSet<>();
            visited.add(child);

            String current = child;
            while (ancestors.size() < MAX_ANCESTOR_DEPTH) {
                String parent = childToParent.get(current);
                if (parent == null) {
                    break;
                }
                if (!visited.add(parent)) {
                    LOG.warn("Circular ParentSiteRef chain: climbing from {} revisited {} - "
                            + "stopping there with the ancestors found so far.", child, parent);
                    break;
                }
                ancestors.add(parent);
                current = parent;
            }

            if (ancestors.size() == MAX_ANCESTOR_DEPTH && childToParent.get(current) != null) {
                LOG.warn("ParentSiteRef chain from {} is deeper than the cap of {} - "
                        + "resolution is truncated there.", child, MAX_ANCESTOR_DEPTH);
            }

            if (!ancestors.isEmpty()) {
                flattened.put(child, Set.copyOf(ancestors));
            }
        }
        return flattened;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -o test -Dtest=NSRServiceAncestorTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Populate the map during warm-up**

Still in `NSRService.warmUpCache()`. The existing method parses the NeTEx file into `index` and then iterates stop places. Add a `childToParent` map before the existing `forEach`, record the parent link inside it, and flatten afterwards.

Change this:

```java
                NetexEntitiesIndex index = netexParser.parse(readUrl(url));
                index.getStopPlaceIndex().getLatestVersions().forEach( stopPlace -> {
                    String stopPlaceId = stopPlace.getId();
```

to this:

```java
                NetexEntitiesIndex index = netexParser.parse(readUrl(url));

                // The parser already publishes quay -> stop place; this map is not retained
                // after parsing, so its contents are copied. Stop place -> multimodal parent is
                // added below, from the loop that already visits every stop place.
                Map<String, String> childToParent = new HashMap<>(index.getStopPlaceIdByQuayIdIndex());

                index.getStopPlaceIndex().getLatestVersions().forEach( stopPlace -> {
                    String stopPlaceId = stopPlace.getId();
                    SiteRefStructure parentSiteRef = stopPlace.getParentSiteRef();
                    if (parentSiteRef != null && parentSiteRef.getRef() != null) {
                        childToParent.put(stopPlaceId, parentSiteRef.getRef());
                    }
```

Then, immediately after the closing `});` of that `forEach` and before the `catch`, add:

```java
                ancestorsByRef.putAll(flattenAncestors(childToParent));
                LOG.info("NSRService resolved ancestors for {} stop refs.", ancestorsByRef.size());
```

- [ ] **Step 6: Run the full suite**

Run: `mvn -o clean test`
Expected: PASS, 179 tests (170 + 9). Output pristine.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/NSRService.java \
        src/test/java/org/entur/vehicles/service/NSRServiceAncestorTest.java
git commit -m "Retaining the stop place hierarchy from NeTEx"
```

---

### Task 2: The ET join climbs ancestors

Makes the reported bug go away: a situation on `NSR:StopPlace:451` attaches to the call at `NSR:Quay:749`.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/data/SituationMatcher.java`
- Modify: `src/main/java/org/entur/vehicles/graphql/SituationJoinController.java`
- Test: `src/test/java/org/entur/vehicles/data/SituationMatcherTest.java`

**Interfaces:**
- Consumes: `NSRService.ancestorsOf(String) -> Set<String>` from Task 1.
- Produces: `SituationMatcher(Collection<SituationUpdate>, Function<String, Set<String>> ancestorResolver)` — the second argument yields a ref's ancestors, NOT including the ref itself. The existing one-argument constructor stays, defaulting to `ref -> Set.of()`.

**Do not inject `NSRService` into `SituationMatcher`.** It is deliberately free of Spring so the match rule can be unit-tested directly; a `Function` preserves that.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/data/SituationMatcherTest.java`. Add the import `java.util.Map` and `java.util.function.Function` if not already present.

```java
    /** The reported production case: BNR:SituationNumber:1234-1234 on NSR:StopPlace:451. */
    @Test
    public void testStopPlaceSituationAttachesToTheCallAtItsQuay() {
        SituationUpdate atStopPlace = situation("BNR:SituationNumber:1234-1234");
        atStopPlace.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:451"));

        SituationMatcher matcher = new SituationMatcher(
                List.of(atStopPlace),
                ref -> "NSR:Quay:749".equals(ref) ? Set.of("NSR:StopPlace:451") : Set.of());

        Call call = call("NSR:Quay:749", noon, noon.plusMinutes(1));

        assertThat(matcher.match(call))
                .withFailMessage("a situation tagged on a stop place must reach the calls at its quays")
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("BNR:SituationNumber:1234-1234");
    }

    @Test
    public void testSituationOnAMultimodalParentReachesTheQuay() {
        SituationUpdate atParent = situation("TST:SituationNumber:multimodal");
        atParent.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:999"));

        SituationMatcher matcher = new SituationMatcher(
                List.of(atParent),
                ref -> "NSR:Quay:749".equals(ref)
                        ? Set.of("NSR:StopPlace:451", "NSR:StopPlace:999")
                        : Set.of());

        assertThat(matcher.match(call("NSR:Quay:749", noon, noon.plusMinutes(1))))
                .withFailMessage("resolution must climb past the immediate stop place")
                .extracting(SituationUpdate::getSituationNumber)
                .containsExactly("TST:SituationNumber:multimodal");
    }

    @Test
    public void testSituationNamingBothQuayAndStopPlaceAppearsOnce() {
        SituationUpdate both = situation("TST:SituationNumber:both");
        both.getAffects().addStopPoint(new StopPoint("NSR:Quay:749"));
        both.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:451"));

        SituationMatcher matcher = new SituationMatcher(
                List.of(both),
                ref -> "NSR:Quay:749".equals(ref) ? Set.of("NSR:StopPlace:451") : Set.of());

        assertThat(matcher.match(call("NSR:Quay:749", noon, noon.plusMinutes(1))))
                .withFailMessage("matching by two routes must not list the situation twice")
                .hasSize(1);
    }

    /**
     * Ancestor resolution must not weaken the per-call temporal rule, which is the reason
     * stop matching is safe to do at all.
     */
    @Test
    public void testAnAncestorMatchIsStillTestedAgainstTheCallsWindow() {
        SituationUpdate untilNoon = situation("TST:SituationNumber:lapsed",
                new ValidityPeriod(noon.minusHours(3), noon));
        untilNoon.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:451"));

        SituationMatcher matcher = new SituationMatcher(
                List.of(untilNoon),
                ref -> "NSR:Quay:749".equals(ref) ? Set.of("NSR:StopPlace:451") : Set.of());

        assertThat(matcher.match(call("NSR:Quay:749", noon.plusMinutes(30), noon.plusMinutes(31))))
                .withFailMessage("a stop place message ending at 12:00 must not attach to a call at 12:30")
                .isEmpty();
    }

    @Test
    public void testWithoutAResolverMatchingStaysLiteral() {
        SituationUpdate atStopPlace = situation("TST:SituationNumber:stopplace");
        atStopPlace.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:451"));

        SituationMatcher matcher = new SituationMatcher(List.of(atStopPlace));

        assertThat(matcher.match(call("NSR:Quay:749", noon, noon.plusMinutes(1))))
                .withFailMessage("with NSR lookup disabled there is no hierarchy, so matching must "
                        + "fall back to literal stop ids exactly as it does today")
                .isEmpty();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o test -Dtest=SituationMatcherTest`
Expected: FAIL — compilation error; the two-argument `SituationMatcher` constructor does not exist.

- [ ] **Step 3: Add the resolver to SituationMatcher**

In `src/main/java/org/entur/vehicles/data/SituationMatcher.java`, add the import `java.util.function.Function`, then add the field and replace the constructor:

```java
    private final Function<String, Set<String>> ancestorResolver;

    /**
     * Matches on literal stop ids only. Used where no stop hierarchy is available - which is
     * every deployment with {@code vehicle.nsr.lookup.enabled=false}, and every unit test that
     * is not specifically about hierarchy.
     */
    public SituationMatcher(Collection<SituationUpdate> situations) {
        this(situations, ref -> Set.of());
    }

    /**
     * @param ancestorResolver a ref's ancestors, NOT including the ref itself - typically
     *                         {@code NSRService::ancestorsOf}. Passed as a function rather than
     *                         the service so this class stays free of Spring, which is what lets
     *                         the match rule be unit-tested directly.
     */
    public SituationMatcher(Collection<SituationUpdate> situations,
                            Function<String, Set<String>> ancestorResolver) {
        this.ancestorResolver = ancestorResolver;
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
```

- [ ] **Step 4: Climb ancestors in match(Call)**

Replace the body of `match(Call)`. The existing version looks up `byStopRef` once; it now also looks up each ancestor. Keep the doc comment already there and add the ancestor paragraph:

```java
    /**
     * Situations affecting this call's stop while the vehicle is there. A situation on a
     * quay is only relevant if it is in force when the vehicle actually calls: one ending
     * at 12:00 does not apply to a call at 12:30, even though it may be valid right now.
     * <p>
     * A situation naming several of the journey's stops is reported against every one of
     * them, so a client can mark each affected stop.
     * <p>
     * A stop is also matched by any ancestor above it - its stop place, and any multimodal
     * parent above that - because timetable data references quays while a situation may be
     * tagged on the stop place that owns them. The temporal rule is unchanged: an
     * ancestor-matched situation is still tested against this call's own window.
     */
    public List<SituationUpdate> match(Call call) {
        if (call.getStopPoint() == null || call.getStopPoint().getId() == null) {
            return List.of();
        }
        String stopId = call.getStopPoint().getId();
        Map<Identity, SituationUpdate> matched = new LinkedHashMap<>();
        collect(byStopRef.get(stopId), call.getWindowStart(), call.getWindowEnd(), matched);
        for (String ancestor : ancestorResolver.apply(stopId)) {
            collect(byStopRef.get(ancestor), call.getWindowStart(), call.getWindowEnd(), matched);
        }
        return new ArrayList<>(matched.values());
    }
```

- [ ] **Step 5: Wire the resolver in SituationJoinController**

In `src/main/java/org/entur/vehicles/graphql/SituationJoinController.java`, the constructor is currently:

```java
    public SituationJoinController(@Autowired SituationRepository situationRepository) {
        this.situationRepository = situationRepository;
    }
```

Change it to:

```java
    public SituationJoinController(@Autowired SituationRepository situationRepository,
                                   @Autowired NSRService nsrService) {
        this.situationRepository = situationRepository;
        this.nsrService = nsrService;
    }
```

adding the matching `private final NSRService nsrService;` field, and change `matcher()`:

```java
    private SituationMatcher matcher() {
        return new SituationMatcher(situationRepository.getSituations(null), nsrService::ancestorsOf);
    }
```

Add the import `org.entur.vehicles.service.NSRService`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -o test -Dtest=SituationMatcherTest`
Expected: PASS, 19 tests (14 existing + 5 new).

- [ ] **Step 7: Run the full suite**

Run: `mvn -o clean test`
Expected: PASS, 184 tests (179 + 5). Output pristine.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/SituationMatcher.java \
        src/main/java/org/entur/vehicles/graphql/SituationJoinController.java \
        src/test/java/org/entur/vehicles/data/SituationMatcherTest.java
git commit -m "Matching calls against the stop place above the quay"
```

---

### Task 3: The standalone filter climbs ancestors

`situations(stopRef: "NSR:Quay:749")` returns situations tagged on its ancestors. A stop place query still never descends to quays.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/data/SituationFilter.java`
- Modify: `src/main/java/org/entur/vehicles/graphql/Query.java`
- Modify: `src/main/java/org/entur/vehicles/graphql/Subscription.java`
- Test: `src/test/java/org/entur/vehicles/data/SituationFilterTest.java`

**Interfaces:**
- Consumes: `NSRService.expandWithAncestors(String) -> Set<String>` from Task 1.
- Produces: `SituationFilter`'s seventh constructor parameter changes from `String stopRef` to `Set<String> stopRefs`. Nothing later depends on it.

**The null case is the trap here.** `stopRef` is optional, and a null one means "do not filter by stop". If `Query` calls `expandWithAncestors(null)` it gets an empty set, and an empty set must NOT be treated as "match nothing" — that would silently empty every `situations` query with no stop filter. Resolve conditionally and keep passing null through.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/data/SituationFilterTest.java`. Match the existing file's fixture style; add `java.util.Set` to the imports.

```java
    @Test
    void testMatchesASituationTaggedOnAnAncestorOfTheQueriedRef() {
        SituationUpdate atStopPlace = new SituationUpdate();
        atStopPlace.setSituationNumber("BNR:SituationNumber:1234-1234");
        atStopPlace.setAffects(new Affects());
        atStopPlace.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:451"));

        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:Quay:749", "NSR:StopPlace:451"),
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(atStopPlace))
                .withFailMessage("asking for a quay must also find situations on the stop place above it")
                .isTrue();
    }

    @Test
    void testDoesNotDescendFromAStopPlaceToItsQuays() {
        SituationUpdate atQuay = new SituationUpdate();
        atQuay.setSituationNumber("TST:SituationNumber:quay-only");
        atQuay.setAffects(new Affects());
        atQuay.getAffects().addStopPoint(new StopPoint("NSR:Quay:749"));

        // Expanding a stop place yields the stop place and anything above it - never its quays.
        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:451"),
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(atQuay))
                .withFailMessage("resolution climbs, never descends - asserting the non-goal so a "
                        + "later change cannot widen the contract silently")
                .isFalse();
    }

    @Test
    void testAStopPlaceQueryStillClimbsToItsMultimodalParent() {
        SituationUpdate atParent = new SituationUpdate();
        atParent.setSituationNumber("TST:SituationNumber:multimodal");
        atParent.setAffects(new Affects());
        atParent.getAffects().addStopPoint(new StopPoint("NSR:StopPlace:999"));

        // Expanding NSR:StopPlace:451 yields itself plus the multimodal parent above it.
        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                Set.of("NSR:StopPlace:451", "NSR:StopPlace:999"),
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(atParent))
                .withFailMessage("climbing is uniform - a stop place resolves to its own ancestors "
                        + "too, not just quays. A rule with an exception is what a later change "
                        + "gets quietly wrong.")
                .isTrue();
    }

    @Test
    void testANullStopRefSetStillMeansNoStopFilter() {
        SituationUpdate anywhere = new SituationUpdate();
        anywhere.setSituationNumber("TST:SituationNumber:anywhere");
        anywhere.setAffects(new Affects());
        anywhere.getAffects().addLine(new Line("TST:Line:1"));

        SituationFilter filter = new SituationFilter(
                null, MetricType.QUERY, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null, true, null, null);

        assertThat(filter.isMatch(anywhere))
                .withFailMessage("a query with no stopRef must not be filtered by stop at all - "
                        + "treating an absent filter as 'match nothing' would empty every "
                        + "unfiltered situations query")
                .isTrue();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o test -Dtest=SituationFilterTest`
Expected: FAIL — compilation error; the seventh constructor parameter is still `String`.

- [ ] **Step 3: Change SituationFilter to a ref set**

In `src/main/java/org/entur/vehicles/data/SituationFilter.java`:

Change the field declaration from `private final String stopRef;` to:

```java
    /**
     * The queried stop ref together with every ancestor above it, already resolved by the
     * caller. Null means no stop filter at all - an empty set would mean "match nothing",
     * which is not the same thing.
     */
    private final Set<String> stopRefs;
```

Change the constructor parameter `String stopRef,` to `Set<String> stopRefs,` and the assignment `this.stopRef = stopRef;` to `this.stopRefs = stopRefs;`.

Change the check at line 127 from:

```java
        if (stopRef != null && (affects == null || !affects.getStopRefs().contains(stopRef))) {
            return false;
        }
```

to:

```java
        if (stopRefs != null && (affects == null || Collections.disjoint(affects.getStopRefs(), stopRefs))) {
            return false;
        }
```

Add the import `java.util.Collections`. Change the `toString()` entry from `.add("stopRef='" + stopRef + "'")` to `.add("stopRefs=" + stopRefs)`.

- [ ] **Step 4: Resolve before constructing the filter**

In both `src/main/java/org/entur/vehicles/graphql/Query.java` and `src/main/java/org/entur/vehicles/graphql/Subscription.java`, add `NSRService` as a constructor dependency following each file's existing injection style, add the import `org.entur.vehicles.service.NSRService`, and replace the `stopRef,` argument in the `new SituationFilter(...)` call with:

```java
                stopRef == null ? null : nsrService.expandWithAncestors(stopRef),
```

The conditional is required. `expandWithAncestors(null)` returns an empty set, and an empty set means "match nothing" — passing that through would empty every `situations` query that does not filter by stop.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -o test -Dtest=SituationFilterTest`
Expected: PASS, 17 tests (13 existing + 4 new).

- [ ] **Step 6: Run the full suite**

Run: `mvn -o clean test`
Expected: PASS, 188 tests (184 + 4). Output pristine.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/entur/vehicles/data/SituationFilter.java \
        src/main/java/org/entur/vehicles/graphql/Query.java \
        src/main/java/org/entur/vehicles/graphql/Subscription.java \
        src/test/java/org/entur/vehicles/data/SituationFilterTest.java
git commit -m "Filtering situations by stop ancestors"
```

---

### Task 4: The republisher, the end-to-end proof and the docs

Without this task the join works on a query but never reaches a subscriber, and the two features disagree.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java`
- Modify: `src/main/resources/Usage.md`
- Test: `src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java`
- Test: `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java`

**Interfaces:**
- Consumes: `NSRService.ancestorsOf(String) -> Set<String>` from Task 1.
- Produces: nothing further depends on this.

**Use `ancestorsOf`, not `expandWithAncestors`.** `findAffected` walks every call of every stored journey on every situation change. `expandWithAncestors` allocates a set per call; `ancestorsOf` returns the stored one. This is the hottest path in the feature.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java`:

```java
    @Test
    public void testFindsAJourneyByAnAncestorOfItsCalledAtStop() {
        EstimatedTimetableUpdate journey = storeJourney(
                "VYG:Line:1", "VYG:ServiceJourney:80808_548292-R",
                "VYG:DatedServiceJourney:80808_548292-R", "NSR:Quay:749");

        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(
                metricsService, timetableMap, new EstimatedTimetableUpdateRxPublisher(),
                100, Duration.ofMillis(1), DEFAULT_THRESHOLD,
                ref -> "NSR:Quay:749".equals(ref) ? Set.of("NSR:StopPlace:451") : Set.of());

        assertThat(republisher.findAffected(Set.of("NSR:StopPlace:451")))
                .withFailMessage("a situation on a stop place must republish the journeys calling "
                        + "at its quays, or the join works on a query but never reaches a subscriber")
                .containsExactly(journey);
    }

    @Test
    public void testDoesNotFindAJourneyByAnUnrelatedAncestor() {
        storeJourney("VYG:Line:1", "VYG:ServiceJourney:1", "VYG:DatedServiceJourney:1", "NSR:Quay:749");

        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(
                metricsService, timetableMap, new EstimatedTimetableUpdateRxPublisher(),
                100, Duration.ofMillis(1), DEFAULT_THRESHOLD,
                ref -> "NSR:Quay:749".equals(ref) ? Set.of("NSR:StopPlace:451") : Set.of());

        assertThat(republisher.findAffected(Set.of("NSR:StopPlace:999"))).isEmpty();
    }
```

The real constructor today is `(PrometheusMetricsService, AutoPurgingTimetableMap, EstimatedTimetableUpdateRxPublisher, int chunkSize, Duration chunkDelay, int largeFanoutThreshold)`; the resolver goes on the end. Update the existing `setUp()` and every other `new SituationTriggeredRepublisher(...)` in this file to pass `ref -> Set.of()` as the new final argument, so the existing tests keep exercising literal matching. `metricsService` and `DEFAULT_THRESHOLD` are already fields in this test class.

Then append to `src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java` — this is the end-to-end proof of the reported bug, through the real schema:

```java
    private static final String QUAY_ANCESTOR_LINE = "TST:Line:ancestor-probe";
    private static final String QUAY_ANCESTOR_DSJ = "TST:DatedServiceJourney:ancestor-probe";
    private static final String QUAY_ANCESTOR_QUAY = "NSR:Quay:ancestor-probe";
    private static final String QUAY_ANCESTOR_STOP_PLACE = "NSR:StopPlace:ancestor-probe";
    private static final String QUAY_ANCESTOR_SITUATION = "TST:SituationNumber:ancestor-probe";
```

```java
    /**
     * NSR lookup is disabled in the test context, so the real hierarchy is empty and this test
     * has to supply one. It replaces NSRService with a stub whose only knowledge is that the
     * probe quay sits under the probe stop place - which is exactly the relationship under test.
     */
    @MockitoBean
    private NSRService nsrService;

    @Test
    void aSituationOnAStopPlaceReachesTheCallAtItsQuay() {
        when(nsrService.ancestorsOf(QUAY_ANCESTOR_QUAY)).thenReturn(Set.of(QUAY_ANCESTOR_STOP_PLACE));
        when(nsrService.ancestorsOf(argThat(ref -> !QUAY_ANCESTOR_QUAY.equals(ref)))).thenReturn(Set.of());
        when(nsrService.getStop(anyString())).thenAnswer(i -> new StopPoint(i.getArgument(0)));

        situationRepository.add(situationAffectingStop(QUAY_ANCESTOR_SITUATION, QUAY_ANCESTOR_STOP_PLACE));
        timetableRepository.add(journeyCallingAt(QUAY_ANCESTOR_LINE, QUAY_ANCESTOR_DSJ, QUAY_ANCESTOR_QUAY));

        String document = """
                query {
                  timetables(lineRef: "%s") {
                    calls {
                      stopPoint { id }
                      situations { situationNumber }
                    }
                  }
                }
                """.formatted(QUAY_ANCESTOR_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-ancestor", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<String> callSituations =
                situationNumbersOf(response.field("timetables[0].calls[0].situations").getValue());
        assertThat(callSituations)
                .withFailMessage("a situation tagged on NSR:StopPlace must appear on the call at its quay")
                .containsExactly(QUAY_ANCESTOR_SITUATION);
    }
```

Add the imports `org.springframework.test.context.bean.override.mockito.MockitoBean`, `org.entur.vehicles.service.NSRService`, `org.entur.vehicles.data.model.StopPoint`, `java.util.Set`, and statically `org.mockito.Mockito.when`, `org.mockito.ArgumentMatchers.anyString`, `org.mockito.ArgumentMatchers.argThat`.

If `@MockitoBean` is unavailable on this Spring Boot version, use `@MockBean`. If neither exists without adding a dependency, STOP and report it — do not replace this with a test that only asserts the query succeeded, which would assert nothing about the hierarchy.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o test -Dtest=SituationTriggeredRepublisherTest+ApplicationGraphQlSchemaTests`
Expected: FAIL — the republisher constructor does not take a resolver, and the schema test finds no situation on the call.

- [ ] **Step 3: Climb ancestors in the republisher**

In `src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java`, add the import `org.entur.vehicles.service.NSRService` and a field:

```java
    private final Function<String, Set<String>> ancestorResolver;
```

Add `java.util.function.Function` to the imports. Add `NSRService` to the `@Autowired` constructor and assign `this.ancestorResolver = nsrService::ancestorsOf;`. Keep every existing parameter and its `@Value` annotation exactly as it is; add the new one last.

For the tests, add a package-private constructor overload taking the resolver directly as its final argument, so the hierarchy can be supplied without a Spring context.

Then change `isAffected` from a static method to an instance method — it now needs the resolver — and replace its call loop:

```java
    private boolean isAffected(EstimatedTimetableUpdate timetable, Set<String> refs) {
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
                if (call.getStopPoint() != null && touchesStop(refs, call.getStopPoint().getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the situation's refs name this stop or any ancestor above it - the stop place
     * that owns the quay, or a multimodal parent above that.
     * <p>
     * Uses {@code ancestorsOf} rather than {@code expandWithAncestors} deliberately: this runs
     * for every call of every stored journey on every situation change, and the former returns
     * the stored set while the latter allocates a new one per call.
     */
    private boolean touchesStop(Set<String> refs, String stopId) {
        if (stopId == null) {
            return false;
        }
        if (refs.contains(stopId)) {
            return true;
        }
        for (String ancestor : ancestorResolver.apply(stopId)) {
            if (refs.contains(ancestor)) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o test -Dtest=SituationTriggeredRepublisherTest+ApplicationGraphQlSchemaTests`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `mvn -o clean test`
Expected: PASS, 191 tests (188 + 3). Output pristine.

- [ ] **Step 6: Prove the end-to-end test is not passing for the wrong reason**

Temporarily change the stub in `aSituationOnAStopPlaceReachesTheCallAtItsQuay` so `ancestorsOf` always returns `Set.of()`, run `mvn -o test -Dtest=ApplicationGraphQlSchemaTests`, and confirm that test now fails. Restore the stub and confirm it passes again.

That test asserts on a list, and a list assertion can pass for reasons unrelated to the hierarchy. This step is what proves the ancestor resolution is what makes it pass. Record the observed failure output in your report.

- [ ] **Step 7: Document the behaviour**

In `src/main/resources/Usage.md`, find the paragraph ending "A situation naming several of the journey's stops is reported against every one of them, so a client can mark each affected stop. Closed situations are never attached." and add after it:

```markdown
A situation tagged on a StopPlace also reaches the calls at that stop place's quays. In NeTEx a
StopPlace is the parent object and the timetable references its Quays, so a message about a station
as a whole is matched against every platform beneath it — including when it is tagged on a
multimodal parent above the stop place.

The same applies when filtering the `situations` feed: `stopRef: "NSR:Quay:749"` returns situations
tagged on that quay, on the stop place above it, and on any multimodal parent above that.

Resolution only ever climbs. `stopRef: "NSR:StopPlace:451"` returns situations naming that stop
place or a parent above it, but **not** ones naming its individual quays — so use a quay ref when
you want everything affecting a specific platform.
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/entur/vehicles/repository/SituationTriggeredRepublisher.java \
        src/main/resources/Usage.md \
        src/test/java/org/entur/vehicles/repository/SituationTriggeredRepublisherTest.java \
        src/test/java/org/entur/vehicles/graphql/ApplicationGraphQlSchemaTests.java
git commit -m "Republishing timetables affected through the stop place hierarchy"
```

---

## Overall verification

```bash
mvn -o clean test          # 191 tests, 0 failures
git log --oneline -4       # four commits, terse subjects, no AI attribution
git status --short         # only the user's pre-existing untracked files
```

Confirm by reading the diff that `TimetableRepository`, `SituationMapper`, `AutoPurgingTimetableMap`, the SX ingest paths and all VM code are untouched, that `pom.xml` has no new dependency, and that nothing writes resolved refs back onto `Affects`.

The test counts above are derived from the test code in this plan. If your count differs, check you have not dropped or duplicated a test before assuming the plan is wrong — but say so in your report either way.
