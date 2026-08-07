# Situations on the Estimated Timetable API

Date: 2026-08-06
Status: Approved design, ready for implementation planning

Builds on:
- `docs/superpowers/specs/2026-08-05-sx-situation-exchange-design.md`
- `docs/superpowers/specs/2026-08-05-sx-startup-snapshot-design.md`

## Goal

Attach relevant SIRI-SX situations to estimated timetable data, so a consumer
fetching a journey also receives the disruptions affecting it, without having to
query the standalone `situations` feed and correlate the two themselves.

Two new fields:

- `EstimatedTimetableUpdate.situations` — those affecting the journey as a whole
- `Call.situations` — those affecting that specific stop, at the time the vehicle
  is there

The two partition: a situation reported against a call is not repeated on the
journey, so a client selecting both never sees the same disruption twice — and a
client selecting only the journey field does not see the whole picture. See "The
relationship between the two fields".

This is the join that the original SX design deliberately deferred. Its
"Non-goals" section stated that the flat identifier sets on `Affects` were built
to be the basis of exactly this, and they are.

## Non-goals

- **No `VehicleUpdate.situations`.** Vehicles are out of scope for this change.
  The same matcher will serve them later, but `VehicleUpdate` carries a single
  `monitoredCall` rather than a call list, so its stop matching is a point rather
  than a window and deserves its own decision.
- **No new GraphQL type.** Both fields return the existing `Situation` type
  already exposed by the standalone feed.
- **No filter arguments on the new fields.** No `severity:` or `includeClosed:`
  on `situations` here. Clients that need arbitrary filtering have the standalone
  `situations` query.
- **No change to the standalone feed**, to the SX ingest paths, or to VM.
- **No persistent reverse index.** See "Mechanics".

## Background — measured, not assumed

Taken from a live production snapshot of 343 situations (306 not closed), on
2026-08-05:

| What the situation names | Count (of 306 open) |
|---|---|
| Specific journeys | 35 |
| Lines, but no journeys | 169 |
| Only stops | 81 |
| Nothing usable | 21 |

Totals across those situations: 245 line references, 201 stop references, 2667
journey references.

Two findings shaped the design:

1. **No situation affects only an operator, and none affects only a mode.** None
   uses network-wide `AllLines` either. The fear that one situation could fan out
   across every journey of an operator is theoretical, not present in this data —
   so operator and mode are not match dimensions.
2. **Stops are the widest tier that does matter**: 81 situations are reachable
   only via a stop. Dropping stop matching would discard a quarter of all
   disruption information, but including it naively is what makes the temporal
   rule below necessary.

## The match rule

A situation attaches when **any** of the following holds. Closed situations
(`progress == closed`) never attach.

| Match | Identifier test | Temporal test |
|---|---|---|
| Service journey | `affects.serviceJourneyIds` contains the journey id | overlaps the journey span |
| Dated service journey | `affects.datedServiceJourneyIds` contains the dated id | overlaps the journey span |
| Line | `affects.lineRefs` contains the line ref | overlaps the journey span |
| Stop | `affects.stopRefs` contains a called-at stop id | overlaps **that call's own window** |

### Why stop matching is per-call

A situation on a quay is only relevant if it is in force when the vehicle is
actually there. A quay message valid until 12:00 must not attach to a journey
arriving at 12:30, even though the situation is valid at the time of the request.
Testing the situation against the journey as a whole would attach it; testing it
against the call does not.

This is what makes stop matching safe to include at all. Without it, the 81
stop-only situations would attach to every journey passing through those stops
regardless of when, which is noise rather than information.

### Time windows

**A call's window** is `[arrival, departure]`, where each end is resolved in
order of confidence:

- arrival: `actualArrivalTime` → `expectedArrivalTime` → `aimedArrivalTime`
- departure: `actualDepartureTime` → `expectedDepartureTime` → `aimedDepartureTime`

If only one end resolves, the window is that instant. If neither resolves, the
window is **unbounded** — the situation attaches rather than being silently
dropped. Missing data must not cause a disruption to disappear.

**A journey's span** is the earliest resolved time across all calls to the latest.
If no call yields any time, the span is unbounded, by the same reasoning.

**Overlap** is inclusive at both ends: a situation ending exactly at the arrival
time still attaches. A validity period with no `endTime` is open-ended and
overlaps everything, so open-ended situations always attach when their identifier
matches — consistent with how `openEnded` behaves everywhere else in this
service. A situation with no validity periods at all is treated the same way.

### The relationship between the two fields

The two fields **partition**. No situation appears in both, so a client
rendering both lists never has to deduplicate across them.

`Call.situations` is the per-stop result: situations naming that call's stop,
valid during that call's window. A situation naming several of the journey's
stops is reported against every one of them — a client marks each affected stop,
so it needs the situation on all of them. Duplication *across calls* is the
point; duplication *between the two fields* is not.

`EstimatedTimetableUpdate.situations` is the journey-level matches (journey,
dated journey, line) overlapping the journey span, deduplicated by situation
number, **minus any situation that also matched one of the journey's calls**.
The stop is the more specific placement, so a situation naming both a line and
one of the journey's stops is reported against that call alone.

Exclusion follows what actually matched, not what the situation names. If a stop
reference had already lapsed by the time the vehicle called there, the stop match
never fired and the journey keeps the situation — otherwise a line-wide
disruption would vanish from the journey because of a stop reference that was
never in force when it mattered.

**The cost of this rule**, accepted deliberately: a client selecting only
`timetables { situations }` no longer sees stop-scoped disruptions. In the
measured production snapshot that is 81 of 306 open situations — a quarter of all
disruption information — reachable only through `calls { situations }`. The
alternative was listing those situations in both places, which pushes
deduplication onto every client.

This reverses the union rule the first version of this design specified. It was
changed because SIRI-SX producers normally scope stops *within* a line or journey
rather than in addition to it, so the union reported one disruption twice in the
common case.

## Mechanics

### Batch resolvers

Two `@BatchMapping` resolvers, in a new `SituationJoinController` in
`org.entur.vehicles.graphql`:

- `EstimatedTimetableUpdate.situations`
- `Call.situations`

`@BatchMapping` is the load-bearing choice. GraphQL resolves a field once per
parent object, so a per-object resolver over a large timetable result would
rebuild its working set for every journey. A batch resolver receives the whole
collection at once and builds its index once.

This is a new pattern for this codebase — no field-level `@SchemaMapping` or
`@BatchMapping` resolver exists today; the annotation appears only as a
class-level marker on domain types.

### Index built per batch, not maintained

For each batch, build a `Map<String, List<SituationUpdate>>` from the live
situation map: every line ref, stop ref, service journey id and dated service
journey id a non-closed situation names points at that situation. Then each ET
message costs `3 + calls.size()` lookups, and each call costs one.

The index is **built per batch and discarded**, not maintained in
`SituationRepository`. A persistent reverse index would be cheaper per read, but
it has to stay consistent with situation replacement and with the purge thread —
and a stale entry means a closed situation still attached to journeys. Rebuilding
from the current map is always correct by construction, and the cost is
`O(situations)` once per batch against `O(ET messages × calls)` lookups saved.

If profiling later shows the rebuild hurts on high-rate subscriptions, caching it
behind a generation counter bumped on write is the natural next step. Not now.

### Cost is opt-in

Neither field is computed unless the client selects it. A consumer of
`timetables` that does not ask for `situations` pays nothing — no index, no
lookups, no change to today's behaviour.

## Code organisation

New:

- `org.entur.vehicles.graphql.SituationJoinController` — the two batch resolvers
- `org.entur.vehicles.data.SituationMatcher` — the index and the match rule, with
  no Spring or GraphQL dependency so it can be unit-tested directly

Modified:

- `SituationUpdate` — gains `boolean isValidDuring(ZonedDateTime from, ZonedDateTime to)`,
  where a null bound means unbounded on that side
- `ValidityPeriod` — gains `boolean overlaps(ZonedDateTime from, ZonedDateTime to)`
- `Call` — gains a resolved-window accessor pair used by the matcher
- `vehicle-updates.graphqls` — `situations: [Situation]` on both types

`TimetableRepository`, `SituationRepository`, `SituationMapper`, the SX ingest
paths and everything VM-related are untouched.

## Testing

- **Match rule** — unit tests on `SituationMatcher` for each of the four match
  dimensions, and for a situation matching none of them.
- **Per-call temporal rule** — the case that motivated the design: a stop
  situation valid until 12:00 must NOT attach to a call arriving at 12:30, and
  MUST attach to a call arriving at 11:30. Both directions, on the same fixture.
- **Time resolution precedence** — a call with an `actualArrivalTime` uses it in
  preference to `expected`, and `expected` in preference to `aimed`.
- **Missing times** — a call with no timestamps at all still attaches a matching
  situation rather than dropping it.
- **Open-ended** — a situation with no validity end attaches regardless of the
  call's time; one with no validity periods at all behaves the same.
- **Closed** — a closed situation never attaches, on either field.
- **Deduplication** — a situation matching both the line and the service journey
  appears exactly once in `EstimatedTimetableUpdate.situations`.
- **Partition** — a stop-triggered situation appears on its own call, not on the
  journey and not on the journey's other calls. A situation naming both a line
  and one of the journey's stops appears on that call alone.
- **Every affected stop** — a situation naming two of the journey's stops is
  reported against both calls, since a client marks each affected stop.
- **Exclusion follows the match, not the tag** — a situation naming a line and a
  stop, whose validity has lapsed by the time the vehicle reaches that stop,
  stays on the journey via its line match and appears on no call.
- **Wiring** — extend the existing `ApplicationGraphQlSchemaTests` with a query
  selecting `timetables { situations { situationNumber } calls { situations { situationNumber } } }`,
  proving both fields resolve through the real schema.
- **Opt-out** — a `timetables` query that does not select `situations` produces no
  matching work. Assert observably, e.g. via a counter on the matcher, rather than
  by inspecting logs.
- **Regression** — the existing 98 tests pass unchanged.

## Success criteria

1. `timetables { situations { situationNumber severity } }` returns the
   situations affecting each journey as a whole, and none that is already
   reported against one of its calls.
2. `timetables { calls { stopPoint { id } situations { situationNumber } } }`
   returns, per call, only the situations affecting that stop while the vehicle is
   there.
3. A quay situation ending before the vehicle arrives does not appear on that
   call, nor via that stop on the journey.
4. A closed situation appears on neither field.
5. A `timetables` query that does not select `situations` behaves exactly as
   today.
6. `mvn clean install` passes with the existing tests unmodified.

## Open questions

None. All design decisions are settled above.
