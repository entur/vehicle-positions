# Situations tagged on journeys and stops together

Date: 2026-09-03
Status: Approved design, ready for implementation planning

## Goal

Preserve the pairing SIRI-SX carries between an affected journey (or line) and the stops
that journey is affected *at*, and expose the polyline for just that part of the journey.

A situation is frequently tagged on a combination rather than on a single object. The
producer nests the stops inside the journey they apply to:

```xml
<Affects><VehicleJourneys>
  <AffectedVehicleJourney>
    <DatedVehicleJourneyRef>VYG:DatedServiceJourney:1123_STB-MOS_26-09-03</DatedVehicleJourneyRef>
    <Route><StopPoints>
      <AffectedStopPoint>
        <StopPointRef>NSR:StopPlace:157</StopPointRef>
        <StopCondition>startPoint</StopCondition>
        <StopCondition>notStopping</StopCondition>
      </AffectedStopPoint>
      <AffectedStopPoint><StopPointRef>NSR:StopPlace:152</StopPointRef>…</AffectedStopPoint>
      <AffectedStopPoint><StopPointRef>NSR:StopPlace:288</StopPointRef>…</AffectedStopPoint>
    </StopPoints></Route>
  </AffectedVehicleJourney>
  <AffectedVehicleJourney>
    <DatedVehicleJourneyRef>VYG:DatedServiceJourney:518_KBG-EVL_26-09-03</DatedVehicleJourneyRef>
    <Route><StopPoints><AffectedStopPoint>
      <StopPointRef>NSR:StopPlace:157</StopPointRef>…
    </AffectedStopPoint></StopPoints></Route>
  </AffectedVehicleJourney>
</VehicleJourneys></Affects>
```

The meaning is "this journey is affected, at these stops" - so the situation is only
relevant to a traveller whose trip visits them.

Two things are wrong today.

**The pairing is discarded at ingest.** `SituationMapper.mapAffects` reads
`AffectedVehicleJourneyRecord` for its line, operator and journey refs only; the `Route` /
`StopPoints` inside it is never read (`src/main/java/org/entur/vehicles/repository/SituationMapper.java:227-256`),
and neither are `AffectedLineRecord.getRoutes()`. `Affects` is a deliberately flat set of
parallel lists and id sets (`src/main/java/org/entur/vehicles/data/model/Affects.java`), so
there is nowhere to put the pairing even if the mapper read it.

**Matching therefore over-matches.** `SituationMatcher.match(Call)` matches on stop ref
alone through `byStopRef` (`src/main/java/org/entur/vehicles/data/SituationMatcher.java:150-160`).
Were the stops above parsed into the flat set, the situation would attach to *every*
journey calling at Oslo S, Nationaltheatret or Skøyen - a bus, another operator's train,
anything - not to the three `VYG:DatedServiceJourney` refs it names.

**And there is no way to draw the affected part.** Geometry is per journey pattern:
`PlannedDataset.patternLinks` holds an ordered `ServiceLinkRef[]` and `pointsOnLink()`
stitches the whole thing (`src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java:243-267`).
The pattern's stop sequence is not extracted at all, so nothing today can express "the
polyline from NSR:StopPlace:157 to NSR:StopPlace:288".

## Decisions already made

1. **Both halves ship together**: the structured `Affects` *and* a server-cut polyline.
   Clients get the affected segment directly rather than reconstructing it.
2. **Matching is tightened, not just presentation.** A stop named only inside a
   journey/line entry matches a call only when that call's journey or line is the one the
   entry names. Top-level `<StopPoints>` / `<StopPlaces>` keep matching any journey.
3. **The affected segment is the span from the first to the last named stop** in route
   order, as one polyline. Not the union of links between adjacent named stops: a
   producer that omits an intermediate stop must still render as one continuous line.
4. **Stops are located on the geometry by projection**, not by extracting the pattern's
   stop sequence from NeTEx. See Approach below - this was chosen over the exact
   alternative with its trade-off understood.
5. **No polyline on line-level entries.** A line has many journey patterns, so there is no
   single geometry to cut. Line entries carry their structured stops only.

## Non-goals

- No change to the NeTEx extractor, the snapshot format, or startup cost. Neither
  `PlannedDataSnapshot.FORMAT_VERSION` nor `NsrSnapshot.FORMAT_VERSION` moves.
- `AffectedSection` / `IndirectSectionRef` stay unmapped. They exist in the Avro model
  (`AffectedRouteRecord.getSections()`) but Norwegian producers tag route stop points.
- No new geometry beyond the affected span: no per-stop offsets, no partial links, no
  cutting *within* a service link between two vertices.
- `StopCondition` is carried through to clients but does not drive the segment rule.

## Approach: projection, and what it costs

Locating a named stop on a pattern's geometry has an exact solution and a cheap one.

The exact one extracts the stop sequence from the data: `ServiceLink` already carries
`<FromPointRef>` / `<ToPointRef>` (both `ScheduledStopPoint`; see
`src/test/resources/netex/fragment-shared-data.xml:36-42`), and because a pattern's links
are ordered, link *i* is stop *i* → stop *i+1*, so index alignment falls out for free. One
further element, `PassengerStopAssignment`, maps ScheduledStopPoint → `NSR:Quay`, and
`NSRService.ancestorsOf` lifts a quay to the `NSR:StopPlace` a situation names. It costs
two new maps in `PlannedDataset`, a snapshot format bump, and growth on an already 118 MB
snapshot.

**We are taking the cheap one: projection.** NSR carries a `Location` for every stop place
and quay - `NsrNetexParser` builds them from each `Centroid`, `NsrSnapshot` serialises
lon/lat, and `NSRService.install` fills the cache - so a named stop's coordinates are
already in memory. Project them onto the stitched polyline and cut at the nearest vertex.
No extractor change, no snapshot bump, no new maps, no startup cost.

The known weakness is that projection has no concept of route order: where a route passes
the same place twice - a ring, an out-and-back branch, a large interchange - the nearest
vertex may be on the wrong pass, and the cut is then silently wrong. Two mitigations are
part of the design rather than afterthoughts:

- **Tightest span** (below): candidates are all local minima within a threshold, and the
  chosen window is the shortest run of the route touching every named stop. On an
  out-and-back this defeats the spurious far-side vertex.
- **All-or-nothing** (below): a named stop that cannot be located does not shrink the span,
  it suppresses the polyline entirely.

If projection proves too coarse in production, the exact approach replaces `PolylineSlicer`
behind the same `affectedPointsOnLink` field, with no schema change.

## Architecture

```
PubSub SX → SituationMapper.mapAffects
                ├─ AffectedVehicleJourney entries (journey refs + stops + conditions)
                ├─ AffectedLine entries (line + stops + conditions)
                └─ flat lists + id sets, DERIVED from the entries (unchanged output)
                        ↓
                   Affects  ──→ SituationFilter   (allStopRefs: discovery)
                            ──→ SituationMatcher  (stopRefs + scoped entries: attachment)
                        ↓
     GraphQL: affects { vehicleJourneys { stops, affectedPointsOnLink } }
                        ↓ (lazy, only when selected)
        AffectedGeometryController → PlannedDataset.stitchedGeometry(pattern)
                                   + NSRService.getStop(ref).getLocation()
                                   → PolylineSlicer → PointsOnLink
```

## Components

### `Affects` - flat view kept, structured view added

Every existing list and id set stays exactly as it is, so no client breaks and
`SituationFilter` / `SituationMatcher` keep their constant-time lookups. Two entry lists
are added, and `mapAffects` builds the entries *first* and derives the flat lists from
them, so deduplication behaviour stays identical to today's.

One deliberate split in the id sets:

| Set | Contents | Consumer |
|---|---|---|
| `stopRefs` | top-level `<StopPoints>` / `<StopPlaces>` only | `SituationMatcher` - attachment |
| `allStopRefs` | `stopRefs` ∪ every stop named inside an entry | `SituationFilter` - discovery |

Widening `stopRefs` with scoped stops would re-create the exact over-matching this change
fixes, so it stays unscoped-only. The same holds for the flat `stopPoints` and `stopPlaces`
*lists*: they keep returning top-level stops only, so `affects { stopPoints }` returns
exactly what it returns today. A scoped stop appears in its entry and in `allStopRefs`, and
nowhere else. The other flat lists - `lines`, `serviceJourneys`, `datedServiceJourneys`,
`operators`, `vehicleModes` - are derived from the entries and keep today's contents,
because the refs they hold are precisely what the entries name. `SituationFilter.stopRefs` matches against `allStopRefs`
instead (`src/main/java/org/entur/vehicles/data/SituationFilter.java:137`), because a client
filtering on `NSR:StopPlace:157` reasonably expects to find the Oslo S closure. Filtering
is discovery; matching is attachment; the two no longer have to be the same rule. Nothing
regresses: those stops are not parsed at all today, so the filter finds nothing today.

### New model types

In `org.entur.vehicles.data.model`:

- `AffectedStop` - a resolved `StopPoint` (via `NSRService.getStop`, exactly as the existing
  stop paths do, so NSR stays the source of truth for names) plus its
  `List<StopConditionEnumeration>`.
- `AffectedVehicleJourney` - `serviceJourney`, `datedServiceJourney`, `line`, `operator`,
  `List<AffectedStop> stops`. Carries no geometry field; `affectedPointsOnLink` is resolved
  lazily by a controller.
- `AffectedLine` - `line` plus `List<AffectedStop> stops`.

`StopConditionEnumeration` in `org.entur.vehicles.data` follows the existing
`SeverityEnumeration.fromValue` pattern: `exceptionalStop`, `destination`, `notStopping`,
`requestStop`, `startPoint`. An unrecognised value is dropped with a debug log, mirroring
`SituationMapper.resolveMode`.

### Schema

```graphql
type Affects {
    # …every existing field, unchanged…
    # The journey↔stops pairing SIRI carries. A journey listed here is also present in
    # serviceJourneys/datedServiceJourneys, so existing clients are unaffected.
    vehicleJourneys: [AffectedVehicleJourney]
    affectedLines: [AffectedLine]
}

type AffectedVehicleJourney {
    serviceJourney: ServiceJourney
    datedServiceJourney: DatedServiceJourney
    line: Line
    operator: Operator
    stops: [AffectedStop]
    # The part of this journey's geometry between the first and last affected stop.
    # Null when the journey has no pattern geometry, or fewer than two stops locate on it.
    affectedPointsOnLink: PointsOnLink
}

type AffectedLine {
    line: Line
    stops: [AffectedStop]
}

type AffectedStop {
    stop: Stop
    stopConditions: [StopConditionEnumeration]
}

enum StopConditionEnumeration {
    exceptionalStop, destination, notStopping, requestStop, startPoint
}
```

### Mapping

The Avro chain carries everything needed, verified against `siri-avro-model` 2.0.1:
`AffectedVehicleJourneyRecord.getRoutes()` → `AffectedRouteRecord.getStopPoints()` →
`StopPointsRecord.getStopPoints()` → `AffectedStopPointRecord.getStopConditions()`. The
same `getRoutes()` exists on `AffectedLineRecord`.

`mapAffects` keeps its existing ordering constraint: the framed `DatedVehicleJourneyRef`
is still added before the bare `vehicleJourneyRefs`, so the `dataFrameRef` date is not
lost to deduplication (`SituationMapper.java:238-246`).

An entry with no stops is still emitted - it is the journey-level tagging that exists
today, and the matcher distinguishes the two cases by whether `stops` is empty.

### Matching

`Call` has no back-reference to its journey, and `Call.situations` is a `@BatchMapping`
over bare `Call` objects (`SituationJoinController.callSituations`), so the scoped rule has
nowhere to read the journey from. Add a package-visible owner reference on `Call`, set in
`TimetableRepository` where calls are built. It is not exposed in the schema and costs one
pointer per call.

`SituationMatcher` gains a fifth index: scoped entries keyed by service journey id, dated
service journey id and line ref, alongside its four existing maps. The rules become:

- **`match(Call)`** - the unscoped stop match as today, including the ancestor climb
  (`ancestorResolver`), *plus* a scoped match: an entry names this call's journey, its dated
  journey or its line **and** one of the entry's stops equals the call's stop or one of its
  ancestors. The temporal rule is untouched - an ancestor-matched or scope-matched situation
  is still tested against the call's own window. This is the only method whose rule changes.
- **`match(EstimatedTimetableUpdate)` is left alone.** Journey-level matching still runs
  off the flat `lineRefs` / `serviceJourneyIds` / `datedServiceJourneyIds` sets, and the
  existing loop that removes whatever already matched at call level
  (`SituationMatcher.java:126-132`) does the relocation for free: a scoped entry that
  matched this journey's calls is reported there and dropped from the journey's own list.
  The documented partition between `EstimatedTimetableUpdate.situations` and
  `Call.situations` is preserved without a second rule.
- **The safety net therefore stays, unchanged.** If a stop-scoped entry matches the journey
  but none of its calls - a stop the ET does not cover, or one whose validity had lapsed by
  the time the vehicle called there - nothing is removed, and the situation is still
  reported at journey level rather than disappearing. No client loses a situation because
  of this change; a situation only moves from one field to the other.

The existing `!matched.isEmpty()` shortcut before that loop stays valid, and the reason is
worth stating because it is not obvious: a scoped call match requires an entry naming the
call's journey, dated journey or line, and every such ref is also in the flat id sets that
drive journey-level matching. So `match(Call)` can never return a situation the journey did
not already match, and skipping the loop on an empty journey-level result cannot lose one.

Effect on the example: it attaches to the three named dated service journeys' calls at Oslo
S, Nationaltheatret and Skøyen, and stops attaching to unrelated journeys calling there.

### Geometry

`PolylineSlicer`, a public class in `org.entur.vehicles.service.planned` - that package
because `Polyline.stitch` and `Polyline.encode` are package-private there. It takes an
`int[]` geometry and the stops' coordinates and returns a `PointsOnLink` or null. Free of
Spring and GraphQL, so the rule is unit-testable directly, as `SituationMatcher` is.

`PlannedDataset` gains `public int[] stitchedGeometry(String journeyPatternId)`, stitching
from `patternLinks` + `linkGeometry` **without caching**; `buildPointsOnLink` is refactored
to call it. The existing encoded-string cache (`patternPolylines`) is untouched, so this
adds no steady-state memory - the array is built per request and discarded.

`AffectedGeometryController` in `org.entur.vehicles.graphql` resolves
`AffectedVehicleJourney.affectedPointsOnLink` lazily, mirroring
`ServiceJourneyGeometryController`: nothing is computed unless a client selects the field.

The algorithm, given an entry:

1. Resolve the pattern. A dated service journey resolves through
   `ServiceJourneyService.findDatedServiceJourney` to its service journey; then
   `PlannedDataset.journeyPatternOf(serviceJourneyId)`. Prefer the dated ref when the entry
   carries both. No pattern, or no geometry → null.
2. Look up each named stop's `Location` through `NSRService.getStop(ref)`. Coordinates
   exist for stop places and quays alike.
3. Project each stop onto the vertex array. Coordinates are interleaved lat/lon
   microdegrees (`Polyline`'s convention). Distance is an equirectangular approximation,
   compared as squared metres - adequate at Norwegian latitudes and free of `sqrt` and
   trigonometry in the inner loop.
4. Keep **every local minimum** within `maxSnapMeters`, not just the global nearest - the
   candidates for "which pass of the route is this". A plateau of equal distances
   contributes its first index only. Candidates per stop are capped at 64 nearest, so a
   pathological geometry cannot blow up step 5.
5. Choose one candidate per stop **minimising `max - min`**: the tightest run of the route
   touching every named stop, by the standard k-way merge over the per-stop sorted
   candidate lists, O(N log k). This is the loop mitigation - on an out-and-back the tight
   window beats the spurious far-side vertex.
6. Cut `[min, max]` inclusive from the vertex array, `Polyline.encode` it, and set `length`
   to the point count - the same convention as `buildPointsOnLink`.

**All-or-nothing.** If any named stop has no location in NSR, or no candidate within
`maxSnapMeters`, the whole polyline is null. A partial span is worse than no span for a map
overlay: it draws a confident line over the wrong part of the route. The client still has
the full `pointsOnLink` and the stop list to fall back on. This is viable precisely because
the stops are per-entry - the producer lists the stops of *that* journey - so a stop that
does not project onto that journey's route is a signal something is wrong, not the norm.
Should production show otherwise, the relaxation (drop unlocatable stops, keep ≥2) is a
one-line change to the same method.

**Fewer than two located stops → null.** A single named stop is a point, not a segment.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `vehicle.situations.affected-geometry.max-snap-meters` | `500` | How far a stop may sit from the route geometry and still be considered on it. Sized for rail: a large stop place's centroid (Oslo S) is a few hundred metres from the track it is measured against; bus quays land within tens of metres. |

Everything else rides on existing configuration. The feature needs `vehicle.nsr.lookup.enabled=true`
for coordinates, which the deployed environments already set
(`helm/vehicle-positions-2/templates/configmap.yaml:67`); with NSR lookup off, stops resolve
without a `Location` and `affectedPointsOnLink` is simply null everywhere - the structured
fields and the matching change are unaffected.

## Error handling

Every failure on the geometry path yields null rather than an error: an unknown journey, a
pattern with no links, a pattern whose links carry no geometry, a stop absent from NSR, a
stop too far from the route. `affectedPointsOnLink` is nullable in the schema and clients
must treat it so.

Malformed SX is handled as it is today - an unparseable stop condition is dropped, a stop
ref that resolves to nothing yields a bare `StopPoint` with just the ref, and a situation
whose codespace cannot be resolved is still skipped whole.

`Polyline.stitch` skips links without geometry, leaving a gap rather than breaking the
sequence. A projection can therefore snap across such a hole. This is pre-existing
behaviour of the stitched geometry, not introduced here, and it is logged at debug when a
chosen window spans one.

## Testing

Test-driven throughout; each component's rule is testable without Spring.

**`PolylineSlicerTest`** (new) - the hard cases, as pure unit tests over hand-built vertex
arrays:
- a straight route, two named stops → the span between them
- a straight route, three named stops with a gap → one continuous span, first to last
- **a route revisiting a point** (out-and-back) → the tight window, not the far-side vertex.
  This is the test that pins the known weakness of projection.
- a stop beyond `maxSnapMeters` → null, not a shrunken span
- a stop with no NSR location → null
- one named stop → null
- unknown pattern, and a pattern whose links carry no geometry → null

**`SituationMapperTest`** - an SX fixture in `src/test/resources/sx` shaped exactly like the
example above: one `AffectedVehicleJourney` with three `AffectedStopPoint`s carrying
`startPoint` / `notStopping`, and a second journey with one. Asserts the entries and their
conditions, **and** that the flat lists and id sets are byte-identical to today's output.
A second fixture covers `AffectedLine` with routes.

**`SituationMatcherTest`** - the over-matching case directly:
- journey A + stops X,Y does **not** attach to journey B's call at X
- it does attach to A's calls at X and at Y
- a top-level (unscoped) stop still attaches to any journey's call there
- an entry naming a journey with no stops is still journey-level
- an entry with stops that matches no call still falls back to journey level
- the ancestor climb still applies to scoped stops (situation on a stop place, call on a quay)

**`SituationFilterTest`** - filtering by `stopRef` finds a situation whose only mention of
that stop is inside an entry.

**`SituationGraphQLTests`** - the new fields end to end, including `affectedPointsOnLink`
selected and not selected (the latter must not touch `PlannedDataset`).

## Risks

| Risk | Mitigation |
|---|---|
| Projection snaps to the wrong pass on a ring or out-and-back | Tightest-span selection over all local minima; the out-and-back case is a required test. Mitigated, not eliminated - see Approach. |
| A stop legitimately off the encoded geometry suppresses the whole polyline | Deliberate (all-or-nothing). Relaxation is a one-line change; decide on production evidence. |
| Tightened matching removes situations clients currently see | It only removes ones that were never about that journey. The journey-level fallback guarantees a situation is never lost outright, only relocated between `situations` and `calls { situations }`. |
| Per-call owner reference grows the timetable map | One pointer per call, no schema surface. |
| Stitched geometry rebuilt per request | Only when a client selects `affectedPointsOnLink` on a situation, which is rare next to the ingest path; array copies, no parsing. Cache if measurement says otherwise. |
