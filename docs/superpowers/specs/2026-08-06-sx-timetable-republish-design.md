# Republishing estimated timetables when a situation changes

Date: 2026-08-06
Status: Approved design, ready for implementation planning

Builds on:
- `docs/superpowers/specs/2026-08-05-sx-situation-exchange-design.md`
- `docs/superpowers/specs/2026-08-05-sx-startup-snapshot-design.md`
- `docs/superpowers/specs/2026-08-06-sx-in-et-api-design.md`

## Goal

An active `timetables` subscription must be told when a situation affecting one of
its journeys changes, even when the journey's own estimated timetable data has not.

Today it is not. `Subscription.timetables` is fed exclusively by
`EstimatedTimetableUpdateRxPublisher`, and the only code that ever publishes to it
is `TimetableRepository.add()`. `SituationRepository.add()` publishes to
`SituationUpdateRxPublisher`, which feeds only the standalone `situations`
subscription. There is no path between the two.

The `situations` field on an estimated timetable is resolved once per emitted
event. No ET event, no re-resolution — so a situation that appears, changes or
closes is invisible to a `timetables` subscriber until that journey's producer
happens to send another ET message.

For an actively-updating journey that window is short. It bites in two cases:

1. A journey whose producer has gone quiet keeps the pre-situation view for the
   lifetime of the subscription.
2. A situation that **closes** stays on the client's display until the next ET
   message arrives, which is the wrong direction to fail in.

It is also asymmetric with the standalone feed: a `situations` subscriber sees the
change immediately, a `timetables` subscriber does not.

## Non-goals

- **No vehicle-monitoring equivalent.** `VehicleUpdate` has no `situations` field
  yet. When it gains one, this mechanism extends to it; not before.
- **No purge-driven republish.** Situations also leave the map by expiry, without
  passing through `add()`. This is deliberately not handled, and the reasoning is
  that it is very nearly a non-event: a *closed* situation is republished at the
  moment it closes (which does pass through `add()`), and the matcher excludes it
  from that instant, so its later purge changes nothing. A merely *expired*
  situation only ever matched calls whose windows overlapped its now-past validity,
  and those journeys are themselves at or near their own expiry. Hooking the purge
  path would buy almost nothing for real added complexity.
- **No change to the `situations` subscription**, to the read-path matcher, or to
  the SX ingest and snapshot paths beyond the single call site described below.
- **No new client-visible field.** See "The republished message".
- **No reverse index.** See "Finding the affected journeys".

## Verified facts

Read from the code, not assumed:

| Fact | Where |
|---|---|
| `timetables` subscription is fed only by `EstimatedTimetableUpdateRxPublisher` | `Subscription.java:146` |
| Only `TimetableRepository.add()` publishes to it | `TimetableRepository.java:267` |
| `SituationRepository.add()` publishes to a different sink | `SituationRepository.java:78` |
| The ET sink is `Sinks.many().multicast().directBestEffort()` | `EstimatedTimetableUpdateRxPublisher.java:18` |
| Each subscriber filters, buffers, then `onBackpressureDrop()` | `EstimatedTimetableUpdateRxPublisher.java:36-38` |
| `currentSubscribers()` already exists | `EstimatedTimetableUpdateRxPublisher.java:42` |
| `AutoPurgingTimetableMap extends ConcurrentHashMap` | `AutoPurgingTimetableMap.java:13`, `AutoPurgingMap.java:9` |
| `SituationRepository.add()` already obtains the previous value via `compute()` | `SituationRepository.java:62-63` |

The sink being `directBestEffort` with `onBackpressureDrop()` is load-bearing: a
burst does not queue, it is **discarded** for any subscriber that cannot keep up,
and the sink cannot tell a republish from a genuine ET update. That is what makes
pacing a correctness concern rather than a nicety.

`ConcurrentHashMap` iteration being weakly consistent is what makes scanning the
timetable map safe while ingest writes to it.

## Architecture

```
SituationRepository.add(record)
    │  (existing) version guard via compute() → publishUpdate → metrics
    │
    └─ republisher.onSituationChanged(previous, accepted)     [non-blocking]
            │
            └─ queue ── SituationTriggeredRepublisher worker thread
                          ├─ drain queue, union the ref sets      (coalesce)
                          ├─ return immediately if no subscribers
                          ├─ scan timetableMap for candidates
                          └─ emit to EstimatedTimetableUpdateRxPublisher, chunked
```

One new component, `org.entur.vehicles.repository.SituationTriggeredRepublisher`.
It depends on `AutoPurgingTimetableMap` and `EstimatedTimetableUpdateRxPublisher`.
`SituationRepository` gains a dependency on it. There is no cycle:
`SituationRepository → republisher → {timetable map, ET publisher}`.

### 1. The trigger, and why the previous version is needed

`SituationRepository.add()` calls `onSituationChanged(previous, accepted)`
immediately after its existing `publisher.publishUpdate(situation)` — outside the
`compute()` lock, for the reason already documented at that call site.

`compute()` receives the previously stored value but the current code discards it.
It is captured into a local single-element holder inside the mapping function. This
does not violate the existing "must stay side-effect free" constraint recorded
there: that constraint exists because the function runs while `ConcurrentHashMap`
holds a bin lock, so it must not block or do I/O. Assigning a reference does
neither, and `ConcurrentHashMap.compute` does not re-invoke the function.

The previous version is needed because matching only the new state is wrong twice
over:

- When a situation **closes**, the matcher excludes it, so matching the new state
  finds no journeys and nothing would be republished — precisely the case that most
  needs to reach the client.
- When a situation **narrows** — say from stop A to stop B — the new state does not
  name A, so journeys calling at A would keep showing it.

So the trigger set is the union of the refs named by `previous` and by `accepted`:
`lineRefs`, `stopRefs`, `serviceJourneyIds` and `datedServiceJourneyIds` from each
one's `Affects`. `previous` is null for a situation seen for the first time.

### 2. Skip everything when nobody is listening

Before any scan: if `etPublisher.currentSubscribers() == 0`, return.

This is not just an optimisation. It makes the startup snapshot — 343 situations
loaded in a burst before any subscriber can exist — cost nothing, and it makes the
whole feature free for every deployment that does not use `timetables`
subscriptions.

### 3. Finding the affected journeys

Scan `timetableMap.values()`. A journey is a candidate when any of the following is
in the trigger ref set:

- its line ref
- its service journey id
- its dated service journey id
- the stop point id of any of its calls

Read those the same way `SituationMatcher` does. In particular,
`AbstractUpdate.getServiceJourney()` does not return the field of that name — when
a `datedServiceJourney` is set it delegates to
`datedServiceJourney.getServiceJourney()`. Test fixtures must therefore use the
two-arg `DatedServiceJourney(id, serviceJourney)` constructor, which is what
`TimetableRepository` actually builds; a one-arg fixture makes the service-journey
dimension silently never fire while the test still passes.

**This is deliberately looser than the match rule.** It ignores validity windows and
`progress` entirely, testing only whether the situation *names* something the
journey touches. The consequence is that some journeys are republished whose
situation list did not actually change.

That is the safe direction and it is chosen on purpose. Computing an exact
before/after diff would require knowing what was last sent to each subscriber, which
is per-subscription state this service does not keep and should not start keeping. A
redundant republish costs one message carrying data the client already has — which
it applies idempotently, exactly as it applies every other event on this stream. A
missed republish costs a client showing a disruption that has ended.

**Why a scan rather than an index.** A reverse ref→journeys index would make this
O(1), but it has to stay consistent with journey replacement *and* with the
1-minute purge; a stale entry republishes a journey that no longer exists or misses
one that does. This is the same trade the ET-join design already rejected for the
read path, for the same reason, and it is rejected here for the same reason.
Rebuilding from the current map is correct by construction, and situation changes
are orders of magnitude rarer than ET updates.

### 4. Coalescing and pacing

A single worker thread owns all of this. `onSituationChanged` enqueues the trigger
ref set and returns, so the SX Pub/Sub executor threads never wait on a scan.

The worker **drains everything currently queued and unions the ref sets into one
scan**. A burst of situation changes therefore costs one pass over the timetable
map, not one per situation. This bounds the cost of any burst, including a snapshot
reload, without a separate rate limiter.

Emission is **chunked**: N journeys, then a short pause, until the candidate set is
exhausted. A line-wide or major-hub situation can match a large share of stored
journeys, and emitting those in one tight loop into a `directBestEffort` sink would
discard messages for slower subscribers — possibly genuine ET updates rather than
the republishes, since the sink cannot distinguish them. Pacing keeps one situation
from monopolising the stream.

Defaults, both configurable:

```properties
vehicle.sx.republish.chunk.size=100
vehicle.sx.republish.chunk.delay=PT0.05S
```

At those values 5,000 journeys drain in about 2.5 seconds, which meets the
"within seconds" requirement with room to spare.

The queue is bounded. If it is full, the incoming ref set is merged into the tail
entry rather than dropped — merging is always safe, because the trigger set is a
union and a larger set only causes extra redundant republishes, never a miss.

### 5. The republished message

The stored `EstimatedTimetableUpdate` instance is emitted through
`etPublisher.publishUpdate(v)` — the same call `TimetableRepository.add()` makes,
carrying the same object.

It is **indistinguishable from an ordinary ET update**, by decision. Clients apply
it exactly as they already apply every event on this stream, so nothing changes for
existing consumers and no schema change is needed. Marking the reason would let a
client skip re-rendering unchanged timetable data, but it is new public API to
support forever and it leaks an internal mechanism into the contract.

Situation-triggered republishes do **not** call
`metricsService.markTimetableUpdate(...)`. That counter measures ingest, and
inflating it with republishes would corrupt an existing operational signal. They get
their own counter instead — see "Observability".

### 6. A pre-existing hazard this makes real

`TimetableRepository.add()` mutates the stored update in place, including
`v.getCalls().clear()` before repopulating. The whole-branch review of the ET-join
work already flagged this as a latent `ConcurrentModificationException` risk, since
the GraphQL serializer walks the same list.

This feature makes it materially more likely: the scan reads `getCalls()` on **every
stored journey**, on a background thread, while ingest keeps writing.

Handling: each journey is examined inside a `try`/`catch` for
`ConcurrentModificationException`; on catch the journey is skipped and a counter
incremented. Skipping is safe — that journey is mid-update, so an ET event for it is
about to be published anyway, carrying the fresh situations with it.

This is containment, not a fix. The real fix is for `TimetableRepository` to build a
new call list and swap it in rather than clearing in place. That is out of scope
here — `TimetableRepository` is otherwise untouched by this change — but it should
be recorded as follow-up work rather than left as folklore.

### 7. Observability

- A counter of journeys republished, and one of scans performed. Both are readable
  from the republisher itself, so tests can assert on them directly rather than
  inferring behaviour from logs or timing.
- A counter of journeys skipped on `ConcurrentModificationException`.
- Scan duration and candidate count logged at DEBUG.
- A single scan exceeding a threshold logged at WARN, since that is the signal that
  the timetable map has grown past what a full scan per situation change can carry.

## Error handling

- **The worker thread must never die.** Its run loop catches `RuntimeException` per
  drained batch, logs at WARN and continues. A dead worker would silently stop all
  republishing with the rest of the service healthy.
- **A failure in `onSituationChanged` must not break SX ingest.** Enqueueing is
  wrapped so that any failure logs at WARN and returns; a situation that fails to
  trigger a republish is still correctly stored and still reaches the `situations`
  subscription.
- **Interruption** during a chunk pause exits the worker loop cleanly and restores
  the interrupt flag.

## Testing

No test performs network I/O, and none depends on wall-clock timing beyond the
existing latch-with-timeout pattern in `ApplicationGraphQlSchemaTests`.

- **The headline case** — an active `timetables` subscription, no ET traffic at all,
  add a matching situation, assert an event arrives for that journey carrying the
  situation. This is the behaviour the whole change exists for.
- **Close** — with the same subscription live, close that situation and assert a
  further event arrives with the situation gone. This is the case that fails if the
  previous version is not captured, so it must be asserted directly rather than
  inferred.
- **Narrowing** — a situation moves from stop A to stop B; the journey calling at A
  is republished.
- **Trigger dimensions** — a journey is found via its line, its service journey, its
  dated service journey, and via a call's stop, one test each.
- **No subscribers, no work** — with `currentSubscribers() == 0`, assert on the
  republisher's scan counter that no scan runs. Not by inspecting logs.
- **Coalescing** — two situation changes enqueued back to back produce one scan, not
  two, asserted on the scan counter.
- **Pacing** — a candidate set larger than the chunk size is emitted in more than one
  chunk. Assert on observed chunk boundaries, not on elapsed time.
- **Unaffected journeys are not republished** — a stored journey naming none of the
  situation's refs produces no event.
- **Worker survives a failure** — a scan that throws does not stop the next one from
  running.
- **Regression** — the existing 128 tests pass unchanged.

## Success criteria

1. With an active `timetables` subscription and no ET traffic, adding a matching
   situation delivers an event for the affected journey within seconds.
2. Closing that situation delivers a further event with the situation gone.
3. A situation matching no stored journey produces no events.
4. With no `timetables` subscribers, a situation change performs no scan.
5. A situation matching several thousand journeys does not starve concurrent ET
   updates on the same sink.
6. `mvn clean install` passes with the existing tests unmodified.

## Open questions

None. All design decisions are settled above.
