# Geometry for situations tagged on a line

Date: 2026-09-04
Status: Approved design, ready for implementation planning

## Goal

Give `AffectedLine` the polyline that `AffectedVehicleJourney` already has, so a situation
tagged on a line - or on a line and the stops it is affected at - can be drawn on a map.

A producer tags a line rather than its journeys whenever the disruption is not journey-
specific: a closed section of track, a diverted bus route, a station shut for the day. SIRI
carries it under `Affects/Networks`:

```xml
<Affects><Networks><AffectedNetwork>
  <AffectedLine>
    <LineRef>RUT:Line:31</LineRef>
    <Routes><Route><StopPoints>
      <AffectedStopPoint><StopPointRef>NSR:StopPlace:58366</StopPointRef></AffectedStopPoint>
      <AffectedStopPoint><StopPointRef>NSR:StopPlace:58404</StopPointRef></AffectedStopPoint>
    </StopPoints></Route></Routes>
  </AffectedLine>
</AffectedNetwork></Networks></Affects>
```

`SituationMapper.mapAffects` already parses this into an `AffectedLine` with its stops
(`src/main/java/org/entur/vehicles/repository/SituationMapper.java:274-283`), and
`AffectedGeometryController` already knows how to cut a span out of a journey pattern
(`src/main/java/org/entur/vehicles/graphql/AffectedGeometryController.java:77-108`). The
one thing missing is the connection between them: which pattern is *this line's* shape.

This revisits decision #5 of the affected-stops design
(`docs/superpowers/specs/2026-09-03-situation-affected-stops-design.md:74`) - "No polyline
on line-level entries. A line has many journey patterns, so there is no single geometry to
cut." That is still true. The answer here is not to invent a single geometry but to pick
one pattern and say plainly, in the schema, that it is a representative.

## Decisions already made

1. **One representative pattern, not a list.** `affectedPointsOnLink` on `AffectedLine` is
   a single nullable `PointsOnLink`, the same shape as the journey field. A list of every
   variant's shape would be more honest but forces every client to handle a second cardinality
   for a field whose whole purpose is "draw this on a map".
2. **Stops-aware first fit.** With stops, the representative is the first pattern the
   affected stops actually project onto, tried longest-first - so a situation naming
   southbound stops gets the southbound shape rather than a null or the wrong direction.
3. **Without stops, the longest pattern's whole route.** A line affected as a whole is
   affected along all of it; the longest pattern is the most complete shape the line has.
4. **The line to pattern index is built eagerly** in `PlannedDataset.build()`, from maps
   the builder already holds.
5. **Null stays null.** Every case the journey field returns null for returns null here too.
   A partial or wrong-direction span is worse than nothing for a map overlay.

## Non-goals

- **No stop sequences per pattern.** The obvious way to ask "which pattern serves these
  stops" is the pattern's ordered stop points, and `NetexPlannedDataExtractor.readJourneyPattern`
  does not extract them - it keeps `ServiceLinkRef`s only
  (`src/main/java/org/entur/vehicles/service/planned/NetexPlannedDataExtractor.java:102-117`).
  Adding them means a wider parse, a bigger dataset and a `PlannedDataSnapshot.FORMAT_VERSION`
  bump. Geometric projection answers the question well enough; revisit only on production
  evidence that it does not.
- **No change to matching.** This is a display field. `SituationMatcher` is untouched, and
  no situation attaches to anything it did not attach to before.
- **No geometry on the flat `Affects.lines` list.** That list is a set of `Line` objects
  shared with every other consumer of `Line`; the pairing of a line with its affected stops
  exists only on `AffectedLine`.

## Approach

The line's patterns come from the journeys on it: `serviceJourneyLine` maps journey to line
and `serviceJourneyPattern` maps journey to pattern, so composing them and deduplicating
gives a line's distinct patterns. The builder already walks `serviceJourneyLine` to produce
`lineServiceJourneys` (`src/main/java/org/entur/vehicles/service/planned/PlannedDataset.java:476-490`);
the new index is derived in the same pass.

Ordering is by **vertex count descending**, ties broken by pattern id so a reload is
deterministic. Vertex count is the sum of `linkGeometry[link].length` over the pattern's
links - available at build without stitching anything. Longest-first encodes "most complete
shape first", which is what makes first fit pick sensibly and what makes the safety cap drop
the least representative variants.

At query time the resolver walks that list:

- no stops -> `dataset.pointsOnLink(patterns[0])`, served from the dataset's own encoded
  polyline cache; the identical value `ServiceJourney.pointsOnLink` returns for a journey on
  that pattern.
- with stops -> `PolylineSlicer.slice(stitched(pattern), locations, maxSnapMeters)` for each
  pattern in order, returning the first non-null. The slicer already returns null unless
  *every* stop snaps within `maxSnapMeters`, which is exactly the "does this pattern serve
  these stops" test, so first fit needs no new geometry code.

One asymmetry is worth stating because it is a gain: the no-stops case needs no stop
coordinates, so a whole-line situation yields geometry **even when NSR lookup is disabled**.
The stop-span case still requires it, as the journey field does.

## Architecture

```
PlannedDataset.build()
    serviceJourneyLine + serviceJourneyPattern + linkGeometry
        └─> lineJourneyPatterns:  lineId -> patternId[] (distinct, longest first)

GraphQL request
    AffectedLine.affectedPointsOnLink
        └─ AffectedGeometryController
             ├─ stops.isEmpty()  -> dataset.pointsOnLink(patterns[0])        (cached encoding)
             ├─ stops.size() == 1 -> null
             └─ else, patterns[0..cap)
                  ├─ stitched geometry, memoised per request in GraphQLContext
                  └─ PolylineSlicer.slice(...) -> first non-null, else null
```

## Components

### Schema

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

Additive and nullable: no existing client sees a change. The field mirrors
`AffectedVehicleJourney.affectedPointsOnLink` in name, type and null semantics, so a client
already drawing journey spans draws line spans with the same code.

### `PlannedDataset` - one new map, one new accessor

```java
/** Line id -> the distinct journey patterns of its service journeys, longest geometry first. */
private final Map<String, String[]> lineJourneyPatterns;

/** The line's journey patterns, most vertices first; empty when the line has none. */
public String[] journeyPatternsOf(String lineId);
```

Built in `build()` after `journeysByLine` is populated and after `serviceJourneyPattern` has
been canonicalised, so the ids stored are the shared instances. For each line, collect the
distinct pattern ids of its journeys, drop those whose vertex count is zero (a pattern with
no usable geometry can never produce a span, and keeping it would only waste a slice
attempt), sort by vertex count descending then id ascending, and store as an array.

`journeyPatternsOf` returns a shared array, so callers must not mutate it - the same contract
`lineServiceJourneys` already has internally. The accessor returns an empty array rather than
null for an unknown line.

This is derived state: a snapshot replay feeds the same builder, so the index is recomputed
and **no `PlannedDataSnapshot.FORMAT_VERSION` bump is needed**.

Memory: one entry per (line, pattern) pair, which is bounded by the number of journey
patterns in the export. Strings are already canonicalised and shared, so the cost is the
arrays and the map's own overhead - single-digit MB against a dataset already around 300 MB.

`Stats` gains nothing. The build summary log is enough: if any line ends up with an empty
pattern array, log the count at INFO alongside the existing summary.

### `AffectedLine` - documentation only

The class javadoc says "Unlike a journey entry this carries no geometry"
(`src/main/java/org/entur/vehicles/data/model/AffectedLine.java:11-12`). It is replaced with
a sentence describing the representative. No field, no constructor change: geometry is
resolved lazily from the line ref, never stored on the entry, so `equals`/`hashCode` and
therefore the republisher's change detection are untouched.

### `AffectedGeometryController` - a second `@SchemaMapping`

```java
@SchemaMapping(typeName = "AffectedLine", field = "affectedPointsOnLink")
public PointsOnLink affectedPointsOnLink(AffectedLine affectedLine, GraphQLContext context)
```

Same class as the journey resolver, deliberately: it shares the per-request stitched-geometry
memo under `GEOMETRY_MEMO_KEY`, so a situation naming a line *and* journeys on that line
stitches each shared pattern once for the whole request. Per object rather than batched, for
the reason the class javadoc already documents - the field is nullable and a `@BatchMapping`
returning a list cannot express that.

Order of checks, mirroring the journey resolver:

1. line or line ref null -> null.
2. `stops.size() == 1` -> null (a point is not a span).
3. `journeyPatternsOf(lineRef)` empty -> null.
4. `stops.isEmpty()` -> `dataset.pointsOnLink(patterns[0])`.
5. otherwise: resolve each stop's location through `NSRService` once, then for at most
   `maxLinePatterns` patterns in order, memoise `stitchedGeometry`, skip arrays shorter than
   4, and return the first non-null `PolylineSlicer.slice`. All null -> null.

The stop locations are resolved once before the loop, not per pattern.

## Configuration

```properties
vehicle.situations.affected-geometry.max-snap-meters=500   # existing
vehicle.situations.affected-geometry.max-line-patterns=25  # new
```

The cap bounds the worst case: a line with 60 variants and stops that fit none would
otherwise stitch all 60 in one field resolution. Longest-first ordering means the cap drops
the least representative shapes. 25 is chosen to sit above any plausible real line's variant
count while still bounding a pathological one; if production shows lines legitimately needing
more, raising it is a properties change, not a code change.

## Error handling

Every failure is a null field, never an exception and never a partial span:

| Case | Result |
|------|--------|
| Line unknown to the planned data | null |
| Line has no pattern with geometry | null |
| Exactly one affected stop | null |
| A stop unknown to NSR, or NSR lookup disabled | null (the slicer rejects a null location) |
| No pattern the stops project onto within `maxSnapMeters` | null |
| More than `maxLinePatterns` patterns, fit only beyond the cap | null |

## Testing

**`PlannedDatasetTest`** - the index: distinct patterns per line; ordering by vertex count
descending; ties broken by id; a pattern shared by journeys on two lines appearing under
both; a pattern with no geometry excluded; a line whose journeys all lack patterns yielding
an empty array; `journeyPatternsOf` on an unknown line returning empty, not null.

**`AffectedGeometryControllerTest`** - the resolver, over hand-built datasets:
- line with no stops returns the longest pattern's whole polyline, and that value is equal to
  what `ServiceJourney.pointsOnLink` gives for a journey on that pattern;
- two-direction line where the affected stops project only onto the second pattern returns
  that pattern's span (the first-fit case that justifies the whole design);
- one affected stop returns null;
- stops that fit no pattern return null;
- a fit beyond `maxLinePatterns` returns null;
- a request naming a line entry and a journey entry on the same pattern stitches it once
  (assert via a counting dataset or by observing the memo).

**`ApplicationGraphQlSchemaTests`** - the new field is present and nullable.

**`SituationMapperTest`** - unchanged; the mapper does not touch geometry. Worth a read to
confirm the existing `AffectedLine` with routes case still passes untouched.

## Risks

| Risk | Mitigation |
|------|------------|
| First fit picks the wrong direction when both directions accept the same stop pair | Accepted. Only stop sequences per pattern would settle it, and that is a non-goal. Documented in the schema so clients know the field is a representative. |
| A long-pattern line pays several stitches per request | Bounded by `maxLinePatterns`, and the per-request memo means a situation naming many lines on shared patterns pays each pattern once. |
| The representative shape changes across a nightly reload | Ordering is deterministic (vertex count, then id), so it changes only when the export's patterns change - which is the correct reason to change. |
| Clients read the field as "the line's shape" rather than "one pattern's shape" | The schema comment says representative explicitly, in the same place they read the field. |
| The per-request stitched-geometry memo retains one `int[]` per distinct journey pattern touched by the request. The line resolver widens what lands there: a client sweeping `situations { affects { affectedLines { affectedPointsOnLink } } }` retains one stitched array per distinct pattern of every line named in the response. `max-line-patterns` bounds the cost per entry, not the retention per request. | The memo dies with the request and the field is opt-in; watch heap during such a sweep after rollout, and cap the memo's size (falling back to an uncached stitch past the cap) if it proves to matter. |
| For a line affected as a whole, "longest pattern" can pick a rare long express variant and draw a corridor the ordinary line never serves. | Deliberate - the stops path depends on the same longest-first ordering, and making only the no-stops branch use a different key ("the pattern most journeys run") would make one field answer two different questions. Revisit first if production shapes look wrong. |
