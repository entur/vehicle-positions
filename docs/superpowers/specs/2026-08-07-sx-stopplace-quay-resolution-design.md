# Resolving StopPlace-tagged situations to their quays

Date: 2026-08-07
Status: Approved design, ready for implementation planning

Builds on:
- `docs/superpowers/specs/2026-08-05-sx-situation-exchange-design.md`
- `docs/superpowers/specs/2026-08-06-sx-in-et-api-design.md`
- `docs/superpowers/specs/2026-08-06-sx-timetable-republish-design.md`

## Goal

A situation tagged on a StopPlace must reach the calls at that stop place's quays.

In NeTEx, a StopPlace is the parent object and the actual platforms are its Quays.
Timetable data references **quays**. A SIRI-SX message may be tagged on either, and a
stop place tag implies every quay beneath it.

Today it does not work. `Affects.addStopPlace` (`Affects.java:51-54`) puts the stop
place id into the same `stopRefs` set as quay ids, so `SituationMatcher` indexes
`NSR:StopPlace:451` literally while a call carries `NSR:Quay:749`. The two can never
meet.

Concrete case from production: `BNR:SituationNumber:1234-1234` is tagged on
`NSR:StopPlace:451`, and the timetable for `VYG:ServiceJourney:80808_548292-R` should
carry it on the call at `NSR:Quay:749`. It currently does not.

Entur also sometimes tags situations on a **multimodal parent** stop place — a stop
place that is itself the parent of other stop places — so resolution must climb more
than one level.

## Non-goals

- **No descending resolution.** Resolution climbs only. `stopRef: "NSR:StopPlace:451"`
  matches situations naming that stop place or a multimodal parent above it, but never
  ones naming its individual quays. This is a deliberate asymmetry — see "The
  asymmetry, stated plainly".
- **No expansion of stored data.** Nothing resolved is ever written back onto
  `Affects`. `affects { stopPoints }` and `affects { stopPlaces }` keep returning
  exactly what the producer named. Expanding at ingest would add quays the producer
  never mentioned and fan one interchange situation out into dozens of refs.
- **No new GraphQL field and no schema change.**
- **No vehicle-monitoring change.** `VehicleUpdate` has no `situations` field yet.
- **No change to journey-level matching.** Line, service journey and dated service
  journey matching are untouched; only stop matching gains ancestor resolution.
- **No change to `NSRService`'s existing responsibility** beyond retaining a
  relationship it already reads and currently discards.

## Verified facts

Read from the code and the dependency, not assumed:

| Fact | Where |
|---|---|
| Stop place ids land in the same `stopRefs` set as quay ids | `Affects.java:51-54` |
| `SituationMatcher` matches a call on its literal stop id | `SituationMatcher.match(Call)` |
| `SituationFilter` matches `stopRef` with a single `contains` | `SituationFilter.java:127` |
| `SituationTriggeredRepublisher` matches calls on literal stop id | `isAffected(...)` |
| `NSRService` already iterates each stop place's quays and discards the relation | `NSRService.java:72-99` |
| The parser publishes a ready-made quay→stop-place map | `NetexEntitiesIndex.getStopPlaceIdByQuayIdIndex()` |
| `StopPlace` exposes its parent | `Site_VersionStructure.getParentSiteRef()`, returning `SiteRefStructure` |
| That ref's id is readable | `VersionOfObjectRefStructure.getRef()` |
| NSR lookup is enabled in every deployed environment | `helm/vehicle-positions-2/templates/configmap.yaml:67` |
| NSR lookup defaults to off locally and in tests | `application.properties:47`, `src/test/resources/application.properties:3` |

Two of these shape the design. The parser already publishing
`getStopPlaceIdByQuayIdIndex()` means the quay→stop-place map needs no hand-rolling.
And `vehicle.nsr.lookup.enabled=true` being **hardcoded** in the configmap rather than
templated per environment means the relationship data is genuinely present at runtime
everywhere the service is deployed.

## Architecture

```
NSRService.warmUpCache()            (existing @PostConstruct, NeTEx already parsed)
    ├─ index.getStopPlaceIdByQuayIdIndex()      → quay → stop place
    ├─ per stop place: getParentSiteRef()       → stop place → parent stop place
    └─ flatten once  → ancestorsByRef : Map<String, Set<String>>
                              │
                              └─ expandWithAncestors(stopRef) : Set<String>
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        │                              │                              │
  SituationMatcher              SituationFilter          SituationTriggeredRepublisher
  .match(Call)                  (via Query/Subscription)  .isAffected(...)
  the ET join                   the standalone feed       so subscribers are told
```

### 1. The data

`NSRService` gains `Map<String, Set<String>> ancestorsByRef`, built during the
existing `warmUpCache()`:

- Quay→stop-place comes from `index.getStopPlaceIdByQuayIdIndex()`. The index is not
  retained after parsing, so the contents are copied.
- Stop-place→parent comes from `getParentSiteRef().getRef()` on each stop place in the
  loop that already runs at `NSRService.java:57`.

Both are then **flattened once** into, for each quay, the set of every ancestor above
it — its stop place, and any multimodal parent above that.

Flattening at startup rather than walking the chain per lookup is deliberate.
`SituationTriggeredRepublisher`'s scan touches every call of every stored journey on
every situation change, so lookup sits on a hot path and must be O(1).

The flattening walk carries a **depth cap of 10 and a visited set**. This data is external
and outside our control; a circular or absurdly deep `ParentSiteRef` chain must not
hang startup. Hitting either guard logs a WARN naming the ref and stops climbing that
chain, leaving the ancestors found so far intact.

### 2. The primitive

```java
Set<String> expandWithAncestors(String stopRef)
```

Returns the ref itself plus every ancestor. For an unknown ref, a null ref, or when
NSR lookup is disabled, it returns just the ref — so the caller never has to
special-case anything.

### 3. The three consumers

All three currently match a stop on its literal id, and all three need the same
expansion. Missing any one of them leaves a visible inconsistency.

**`SituationMatcher.match(Call)` — the ET join.** The constructor gains a
`Function<String, Set<String>>`, defaulting to `ref -> Set.of(ref)`. This preserves
the class's deliberate freedom from Spring and GraphQL, which is what lets the match
rule be unit-tested directly. `SituationJoinController` supplies
`nsrService::expandWithAncestors`. Each expanded ref is looked up in `byStopRef`;
deduplication is already handled by the existing `Identity`-keyed map, so a situation
naming both a quay and its parent still appears once.

The per-call temporal rule is unchanged. An ancestor-matched situation is still tested
against that call's own window, so a stop place message that lapses before the vehicle
arrives still does not attach.

**`SituationFilter` — the standalone feed.** The `stopRef` field becomes a
`Set<String> stopRefs`, and the check at `SituationFilter.java:127` becomes a disjoint
test against `affects.getStopRefs()`. `Query` and `Subscription` resolve the incoming
argument before constructing the filter, so `SituationFilter` stays a pure value
object with no service dependency. The GraphQL argument itself is unchanged — still a
single `stopRef: String`.

**`SituationTriggeredRepublisher.isAffected` — so subscribers are told.** Without
this, a stop-place situation would attach correctly on a `timetables` *query* but
never reach an active *subscription*, because the republisher would not recognise the
affected journeys. The two features would disagree, which is worse than either being
wrong alone.

### 4. Degradation

With `vehicle.nsr.lookup.enabled=false` the map stays empty, `expandWithAncestors` is
the identity, and every consumer behaves exactly as it does today. That is the
existing local and test configuration, so no test needs NSR data unless it is
specifically testing resolution.

There is no new failure mode: a missing or malformed NeTEx file already leaves
`NSRService` degraded, and this adds no new dependency on it.

## The asymmetry, stated plainly

Resolution climbs, never descends. The rule is uniform — **any** ref expands to itself
plus its ancestors — so:

- A call at `NSR:Quay:749` matches situations naming that quay, **or**
  `NSR:StopPlace:451`, **or** a multimodal parent above it.
- `situations(stopRef: "NSR:Quay:749")` behaves the same way.
- `situations(stopRef: "NSR:StopPlace:451")` matches situations naming that stop place
  **or** a multimodal parent above it — but **not** ones naming its individual quays.

So the asymmetry is precisely this: a caller filtering the standalone feed by a stop
place still misses a quay-specific disruption at that station. That is a conscious
choice, not an oversight. The reported problem is entirely child-to-ancestor, and
adding the descending direction would change results for existing clients of the
standalone feed who may depend on the narrower behaviour. It can be added later
without disturbing anything here.

Stating it as one uniform climbing rule, rather than as a special case for quays,
matters: a rule with an exception is the kind of thing a later change quietly gets
wrong.

## Error handling

- **A circular or over-deep `ParentSiteRef` chain** — the depth cap and visited set
  stop the walk, a WARN names the ref, and startup continues with the ancestors
  resolved so far.
- **A quay with no known stop place** — `expandWithAncestors` returns just the ref.
  Not logged; this is normal for refs outside the NeTEx dataset.
- **NSR disabled or the NeTEx file unavailable** — empty map, identity expansion,
  today's behaviour. Already logged by the existing code.

## Testing

No test performs network I/O. The ancestor map is injectable so resolution can be
tested without a NeTEx file.

- **The reported case, end to end** — `BNR:SituationNumber:1234-1234` on
  `NSR:StopPlace:451`, a journey for `VYG:ServiceJourney:80808_548292-R` calling at
  `NSR:Quay:749`, asserted through the real schema: the situation appears on that
  call. This is the bug, and it is asserted at the level a client sees.
- **Multimodal, two hops** — a situation on a parent stop place above the stop place
  that owns the quay still reaches the call.
- **Cycle guard** — a `ParentSiteRef` chain that loops back on itself does not hang
  startup, and the refs resolved before the cycle are still usable.
- **The standalone filter** — `situations(stopRef: "NSR:Quay:749")` returns a
  situation tagged on the parent stop place.
- **The asymmetry holds** — `situations(stopRef: "NSR:StopPlace:451")` does NOT return
  a situation tagged only on `NSR:Quay:749`, but DOES return one tagged on a
  multimodal parent above it. Both directions asserted: the first keeps a later
  well-meaning change from silently widening the contract, the second proves the
  climbing rule is uniform rather than a quay-only special case.
- **Republishing** — with an active `timetables` subscription and no ET traffic,
  adding a situation on `NSR:StopPlace:451` delivers an event for the journey calling
  at `NSR:Quay:749`. Without the republisher change this fails, which is the point.
- **Deduplication** — a situation naming both a quay and its parent stop place appears
  once on the call, not twice.
- **The temporal rule survives** — a stop place situation that lapses before the
  vehicle reaches the quay does not attach to that call.
- **Disabled NSR** — with lookup off, matching falls back to literal stop ids and
  every existing test still passes.
- **Regression** — the existing 170 tests pass unchanged.

## Success criteria

1. `timetables { calls { stopPoint { id } situations { situationNumber } } }` returns
   a stop-place-tagged situation on the calls at that stop place's quays.
2. The same holds when the situation is tagged on a multimodal parent.
3. `situations(stopRef: "NSR:Quay:749")` returns situations tagged on its ancestors.
4. `situations(stopRef: "NSR:StopPlace:451")` matches that stop place and any
   multimodal parent above it, but NOT situations naming only its quays.
5. An active `timetables` subscription is told when a stop-place situation appears.
6. With `vehicle.nsr.lookup.enabled=false`, behaviour is identical to today.
7. A circular `ParentSiteRef` chain does not prevent startup.
8. `mvn clean install` passes with the existing tests unmodified.

## Open questions

None. All design decisions are settled above.
