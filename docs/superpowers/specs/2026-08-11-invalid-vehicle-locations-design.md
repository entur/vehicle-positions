# Opt-in filtering of vehicles with invalid locations

Date: 2026-08-11

## Problem

The API returns vehicles whose reported coordinates are obviously bogus — latitude/longitude
of `0, 0`, `-1, -1` and `1, 1`. These come from upstream data producers and land in the
repository because `VehicleRepository.add()` only requires a non-null location. Users see
vehicles floating off the coast of Africa in map clients.

The data must still be stored and still be retrievable, but only when a client explicitly
asks for it.

## Solution overview

Add an optional `includeInvalidLocations: Boolean = false` argument to the `vehicles` query
and the `vehicles` subscription. Vehicles at a known-invalid coordinate are filtered out of
the response unless the client passes `true`. Ingestion, storage, enrichment and purging are
unchanged.

## Design

### 1. `InvalidLocationRegistry`

New Spring component, `org.entur.vehicles.service.InvalidLocationRegistry`.

Holds the set of coordinates considered invalid, read from configuration at construction:

```properties
# application.properties
# Comma-separated lat/lon pairs that are treated as invalid vehicle locations
vehicle.invalid.locations=0.0/0.0,-1.0/-1.0,1.0/1.0
```

- The property value is parsed into a `Set<Coordinate>`, where `Coordinate` is a private
  nested `record Coordinate(double latitude, double longitude)` on the registry — it is an
  implementation detail and is not exposed to callers.
- The order within a pair is `latitude/longitude`.
- Whitespace around entries and around the separator is trimmed.
- A malformed entry (wrong number of components, unparseable number) is logged at `WARN`
  and skipped. Startup does not fail.
- An empty or blank property value yields an empty set, which disables the filtering
  entirely.

Public API:

```java
boolean isInvalid(Location location)
```

- Returns `false` for a `null` location. Vehicles without a location are never stored, so
  this case does not arise in practice; returning `false` keeps the method total.
- Matching is exact double equality against the configured set. The three default values
  (`0.0`, `1.0`, `-1.0`) are exactly representable in binary floating point, so no epsilon
  is required.

Deliberate trade-off: only the exact configured coordinates are treated as invalid. A new
variant of the same upstream bug — say `0.0, 0.5` — is not caught until the property is
updated. Because the list is configuration rather than code, that is a config change and
redeploy, not a code change and release.

### 2. Filter application point

Every path that returns vehicle data passes through `QueryFilter.isMatch(VehicleUpdate)`:

- `Query.getVehicles` → `VehicleRepository.getVehicles(filter)` → `filter::isMatch`
- `Subscription.vehicles` → `VehicleUpdateRxPublisher.getPublisher(filter, uuid)`, where the
  initial snapshot is emitted via `startWith(initialdata)` *before* the
  `.filter(vehicleUpdate -> template == null || template.isMatch(vehicleUpdate))` stage, so
  both the snapshot and the live updates are filtered.

A single check in `QueryFilter.isMatch(VehicleUpdate)` therefore covers queries,
subscription snapshots and subscription updates.

`QueryFilter` gains two fields:

```java
private InvalidLocationRegistry invalidLocations;
private boolean includeInvalidLocations;
```

and the check, placed immediately after the bounding-box check so it exits early:

```java
if (isCompleteMatch && !includeInvalidLocations && invalidLocations != null) {
  isCompleteMatch = !invalidLocations.isInvalid(vehicleUpdate.getLocation());
}
```

The two existing `QueryFilter` constructors already take 14 and 16 arguments and are called
from eight test sites, so the new state is supplied by a fluent setter rather than by growing
them:

```java
public QueryFilter withLocationValidity(InvalidLocationRegistry registry, boolean includeInvalidLocations)
```

It returns `this` so call sites can chain it onto construction. When it is not called — the
timetable and situation filters, and existing tests — `invalidLocations` stays `null` and
behaviour is unchanged.

`isMatch(EstimatedTimetableUpdate)` is not touched.

### 3. Schema and resolvers

`src/main/resources/graphql/vehicle-updates.graphqls`, on `Query.vehicles` and
`Subscription.vehicles` only:

```graphql
# Include vehicles whose reported coordinates are known to be invalid (e.g. 0,0).
# Excluded by default.
includeInvalidLocations: Boolean = false
```

`timetables` and `situations` are not changed.

`Query` and `Subscription` each:

- take `InvalidLocationRegistry` as a constructor dependency,
- accept `@Argument Boolean includeInvalidLocations` on their `vehicles` method,
- call `.withLocationValidity(invalidLocationRegistry, Boolean.TRUE.equals(includeInvalidLocations))`
  on the filter they build.

`Boolean.TRUE.equals(...)` treats an explicit `null` (a client passing `null` rather than
omitting the argument) as `false`, matching the schema default.

### 4. Behaviour summary

| Request | Result |
|---|---|
| `vehicles(...)` — argument omitted | Vehicles at configured invalid coordinates are excluded |
| `vehicles(includeInvalidLocations: false)` | Same as above |
| `vehicles(includeInvalidLocations: true)` | All vehicles, exactly as today |
| `subscription { vehicles(...) }` | Applies to initial snapshot and to live updates alike |
| `timetables`, `situations` | Unchanged |

Storage, enrichment and purge behaviour are unchanged in all cases. Prometheus
`markFilterMatch` continues to fire only for vehicles that match the whole filter, so
excluded invalid-location vehicles are simply not counted.

## Testing

**`InvalidLocationRegistryTest`**

- Default property value yields the three documented coordinates.
- A custom property value is parsed, including surrounding whitespace.
- A malformed entry is skipped and the remaining valid entries are still parsed.
- An empty property value yields an empty set and `isInvalid` then returns `false` for
  `0, 0`.
- `isInvalid` returns `true` for each of `(0,0)`, `(-1,-1)`, `(1,1)`; `false` for a valid
  Oslo coordinate; `false` for `null`.

**`QueryFilterTest`**

- A vehicle at `(0,0)` does not match a filter configured with the registry and
  `includeInvalidLocations = false`.
- The same vehicle matches when `includeInvalidLocations = true`.
- A vehicle at a valid coordinate matches in both cases.
- A filter on which `withLocationValidity` was never called matches the `(0,0)` vehicle,
  confirming the unchanged default for timetable and situation filters.

**`VehicleGraphQLTests`**

- Repository seeded with one valid vehicle and one at `(0,0)`; `Query.getVehicles` with the
  argument omitted returns one vehicle, with `includeInvalidLocations = true` returns two.

## Out of scope

- Rejecting or dropping invalid-location vehicles at ingestion time. The data must stay
  queryable.
- Heuristic validity rules (near-origin boxes, geographic sanity boxes). Rejected in favour
  of an explicit configured list.
- Any change to `timetables` or `situations`.
- Stop-point coordinates from `NSRService`.