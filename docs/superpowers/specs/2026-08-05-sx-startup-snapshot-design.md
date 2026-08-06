# SIRI-SX startup snapshot

Date: 2026-08-05
Status: Approved design, ready for implementation planning

Builds on: `docs/superpowers/specs/2026-08-05-sx-situation-exchange-design.md`

## Goal

Bootstrap the in-memory situation store at startup from a complete snapshot of
current situations, fetched over REST, before the Pub/Sub stream begins.

Situations are long-lived — many stay open for weeks, and 41 of the 343 in a
sampled production snapshot carry no validity end time at all. The Pub/Sub topic
carries updates, not state: a situation published before this service started is
never re-sent, so a stream-only service has a permanently incomplete picture
until each producer happens to republish. That undermines both the feed itself
and the data-quality tooling built on `openEnded` / `minAge`, which is precisely
about situations that have been open a long time.

## Non-goals

- **No periodic re-sync.** This is a startup-only bootstrap. A recurring refetch
  would heal drift on long-running pods, but every cycle reintroduces the hazard
  of a snapshot record landing on top of fresher streamed data (see "Ordering").
  Startup-only avoids that entirely. Revisit if drift proves to be a real problem.
- **No new ingest path.** Snapshot records go through the existing
  `SituationRepository.add(...)`, so mapping, enrichment, the version guard and
  expiration behave identically to streamed updates.
- **No Journey Planner integration.** See "Why not Journey Planner" below.
- **No change to VM or ET.** Neither stream is affected.

## Background — verified facts

All of the following was measured against the live endpoints on 2026-08-05, not
inferred.

### The endpoint

`GET https://api.entur.io/realtime/v1/rest/sx?SIRI_VERSION=2.1`

| Request | Response |
|---|---|
| `Accept: application/avro+json` | JSON in the Avro record shape |
| no `Accept` header | SIRI XML |

The response `content-type` is `application/json` in both JSON cases; the
`Accept` header is what selects the representation.

Payload shape maps onto the generated Avro classes exactly:

```
SiriRecord
  └─ serviceDelivery            (ServiceDeliveryRecord)
       └─ situationExchangeDeliveries[]   (SituationExchangeDeliveryRecord)
            └─ situations[]               (PtSituationElementRecord)
```

Observed volumes:

| Environment | Situations | Size | Deliveries | `moreData` |
|---|---|---|---|---|
| prod (`api.entur.io`) | 343 | 1.4 MB | 1 | `false` |
| dev (`api.dev.entur.io`) | 538 | 9.7 MB | 1 | — |

Rate limits advertised in the response headers: 5 requests per minute, spike
arrest at 1 request per 100 ms. A single startup call is well inside both.

Content characteristics of the production snapshot:

- `progress`: 297 `OPEN`, 37 `CLOSED`, 9 absent
- 41 situations have no `endTime` in any validity period (open-ended)
- 7 situations carry more than one validity period
- 20 distinct codespaces
- **`version` is null on 323 of 343 situations**

That last figure is load-bearing — see "Ordering".

### The encoding problem

`JsonReader.readPtSituationElement(String)` decodes with Avro's strict
`JsonDecoder`, which requires union-typed values to be wrapped by branch name:

```json
{"participantRef": {"string": "VKT"}}
```

The endpoint returns them plain:

```json
{"participantRef": "VKT"}
```

Feeding the endpoint's JSON straight to `JsonReader` therefore fails with
`AvroTypeException: Expected start-union. Got VALUE_STRING`. This was confirmed
by running it.

A schema-driven pass that rewrites plain values into union-wrapped form resolves
it. A ~40-line prototype was run against all 343 production situations:
**343 parsed, 0 failed**, with the progress distribution preserved
(`OPEN=297, CLOSED=37, null=9`).

### Why not Journey Planner

Journey Planner v3 exposes `situations(codespaces, severities)` and the service
already has a client for it, so it would be less work to call. It cannot serve
this purpose. Measured on the same day, comparing both sources:

| | SX REST | Journey Planner |
|---|---|---|
| Situations | 343 | 311 |
| Of the 37 `CLOSED` in SX, present | 37 | **0** |
| `progress` field | present | **absent from the schema** |
| `validityPeriod` | list | **singular** |
| Present only in this source | 37 | 5 |

Three consequences, any one of which is disqualifying:

1. Journey Planner has no `progress` field and omits closed situations entirely,
   so a situation that closed while the service was down would never appear and
   the close would never be published to subscribers.
2. Its singular `validityPeriod` cannot represent the 7 situations carrying
   several, which is what `validNow` and `openEnded` are computed from.
3. The two sources disagree on membership (5 entries exist only in Journey
   Planner), so bootstrapping from one and streaming from the other would hold
   two different shapes of the same situation, with the version guard unable to
   reconcile them because `version` is mostly null.

## Architecture

```
Spring context refresh
    │
    ├─ SituationSnapshotService @PostConstruct   (blocking)
    │     ├─ GET  .../rest/sx?SIRI_VERSION=2.1   Accept: application/avro+json
    │     ├─ Jackson → serviceDelivery.situationExchangeDeliveries[].situations[]
    │     ├─ per situation: union-wrap → JsonReader.readPtSituationElement
    │     └─ SituationRepository.add(record)
    │
    └─ PubSubSXSubscriber  @DependsOn("situationSnapshotService")
          └─ starts consuming the stream
```

### 1. Ordering

`SituationSnapshotService` is a `@Component` whose `@PostConstruct` performs the
whole fetch-and-load synchronously. `PubSubSXSubscriber` carries
`@DependsOn("situationSnapshotService")`, so Spring finishes the snapshot bean's
initialisation before the subscriber bean is created at all.

The ordering is not cosmetic. `SituationRepository`'s version guard only rejects
an incoming update when both the stored and incoming `version` are non-null, and
`version` is null on 323 of 343 situations. A snapshot record arriving after a
newer streamed update for the same situation number would therefore overwrite it
with stale data, silently. Loading strictly before the stream starts removes the
race rather than relying on a guard that mostly cannot fire.

Startup blocks for the duration of the load — roughly the time to transfer a few
MB and parse a few hundred records. The readiness probe stays down until it
completes, which is the intended behaviour.

The whole thing is gated on the existing `entur.vehicle-positions.sx.enabled`
flag. There is no second flag: if the SX stream is off there is nothing to
bootstrap.

This ordering has a counterpart gap in the other direction. Because the Pub/Sub
subscription is created only *after* the snapshot load, and is fresh per pod (no
backlog replay), any SX update published between when the snapshot was generated
upstream and when this pod's subscription is created — roughly the load window,
measured at ~1.5s, plus subscription setup — is never delivered to that pod. The
in-memory map keeps the snapshot's copy of that situation until its producer
happens to republish it. This is the accepted counterpart to eliminating the
snapshot-overwrites-fresher-data hazard described above, and it is a far smaller
problem: a handful of situations briefly stale at startup, versus a permanently
incomplete picture for the service's whole lifetime.

### 2. Fetching

A dedicated `WebClient`, not the shared `JourneyPlannerGraphQLClient` — that one
caps `maxInMemorySize` at 500 KB and the dev payload is 9.7 MB.

- `Accept: application/avro+json`
- `ET-Client-Name`, reusing `vehicle.journeyplanner.EtClientName`
- `maxInMemorySize` 32 MB
- connect/read/write timeout from `vehicle.sx.snapshot.timeout` (default `PT60S`)

### 3. Parsing

Read the response into a Jackson tree and navigate to
`serviceDelivery.situationExchangeDeliveries[].situations[]`, iterating all
deliveries (the samples contain one, but the field is a list).

For each situation, rewrite it into union-wrapped Avro JSON, driven by
`PtSituationElementRecord.getClassSchema()`:

- **union** — a null or missing value becomes `null`; otherwise wrap the value as
  `{"<branch fullname>": <converted>}` using the first non-null branch
- **record** — recurse per declared field, so absent fields become explicit nulls
- **array** — recurse into the element schema
- **map** — recurse into the value schema
- **anything else** — pass through unchanged

Then `JsonReader.readPtSituationElement(...)` on the rewritten JSON, giving the
identical `PtSituationElementRecord` the Pub/Sub path produces.

Taking the *first* non-null branch is safe, not merely convenient: walking
`PtSituationElementRecord.getClassSchema()` finds 54 unions across 23 nested
record types, and **every one of them is `["null", X]`** — exactly one non-null
branch. If a future schema version introduced a union with two non-null
branches, this rule would silently pick the wrong one, so the transform should
fail loudly rather than guess if it ever encounters one.

This lives in its own class so it can be tested independently of the HTTP call.

### 4. Loading

Each record goes to `SituationRepository.add(...)`. Nothing downstream changes:
same `SituationMapper`, same enrichment through `LineService`/`OperatorService`/
`NSRService`, same version guard, same expiration rules, same publishing.

Closed situations in the snapshot (37 of 343 in production) are loaded like any
other. `SituationMapper` sets their expiration to now, so they are purged after
the grace period on the normal path. They are not special-cased. No subscriber
exists yet at startup, so nothing is published to a client that never saw them
open.

### 5. Failure handling

Two levels, both non-fatal:

- **The fetch or the overall parse fails** — log at ERROR and return. Startup
  continues and the service runs stream-only. The consequence is an incomplete
  situation set until producers republish, which is the pre-existing behaviour,
  so degrading to it is safe.
- **A single situation fails to parse or map** — log at WARN with its
  situation number and continue with the rest. One malformed record must not
  discard the other 342.

The count loaded, the count skipped and the elapsed time are logged at INFO so
the outcome is visible in startup logs.

### 6. Configuration

```properties
vehicle.sx.snapshot.url=https://api.dev.entur.io/realtime/v1/rest/sx?SIRI_VERSION=2.1
vehicle.sx.snapshot.timeout=PT60S
```

The default points at dev, matching `vehicle.journeyplanner.url`; helm overrides
it per environment. The snapshot URL and the Pub/Sub topic project must move
together — pointing the snapshot at production while the stream reads
`ent-anshar-dev` would merge two inconsistent datasets into one map.

## Testing

No test performs network I/O.

- **Union-wrapper** — a committed fixture of a handful of real situations,
  trimmed from a live payload, covering: a null union value, a populated string
  union, a nested record (`source`, `affects`), an array of records
  (`validityPeriods`, `summaries`), and an absent field. Asserts the rewritten
  JSON is accepted by `JsonReader` and that the resulting record carries the
  expected values.
- **Single-branch assumption** — asserts every union in
  `PtSituationElementRecord.getClassSchema()` has exactly one non-null branch,
  so that a future `siri-avro-model` bump which violates it fails here rather
  than by silently mis-parsing a field in production.
- **Loader** — given a fixture response body, asserts the repository ends up
  holding the expected situations, including one that is `CLOSED`.
- **Resilience** — a fixture where one situation is malformed asserts the
  remaining ones still load and the failure is counted; a fetch that throws
  asserts startup is not aborted and the repository is simply left empty.
- **Ordering** — asserts `PubSubSXSubscriber` declares
  `@DependsOn("situationSnapshotService")`, since that annotation is the whole
  ordering guarantee and deleting it would break it silently with no other test
  noticing.
- **Regression** — the existing 81 tests must still pass unchanged.

## Success criteria

1. With `sx.enabled=true`, startup logs the number of situations loaded from the
   snapshot before the Pub/Sub subscriber logs that it has started.
2. `query { situations { situationNumber } }` returns the snapshot's situations
   immediately after startup, without waiting for any Pub/Sub message.
3. `situations(openEnded: true, minAge: "P30D")` returns long-lived situations
   that were never seen on the stream.
4. An unreachable snapshot URL logs an ERROR and the service still starts and
   serves the SX stream.
5. `mvn clean install` passes with the existing tests unmodified.

## Open questions

None. All design decisions are settled above.
