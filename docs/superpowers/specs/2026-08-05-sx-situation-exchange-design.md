# SIRI-SX (Situation Exchange) support

Date: 2026-08-05
Status: Approved design, ready for implementation planning

## Goal

Add a third real-time data stream to the service. Today it ingests Vehicle
Monitoring (VM) and Estimated Timetables (ET) from Google Pub/Sub and exposes
both through GraphQL queries and WebSocket subscriptions. This design adds
Situation Exchange (SX) — service messages describing disruptions, deviations
and other situations affecting transit.

Scope is a **standalone situation feed**: clients query and subscribe to
situations filtered by codespace, line, stop, journey, operator, mode, severity
and validity, and correlate with vehicles and timetables themselves. The feed is
designed so that a later change can attach situations directly to
`VehicleUpdate` and `EstimatedTimetableUpdate`, but that join is **not** built
here.

A secondary goal drives one important lifecycle decision: the feed doubles as a
data-quality tool. Situations published without an end time must be retained
indefinitely so that producers who never close them can be identified.

## Non-goals

- No `situations` field on `VehicleUpdate` or `EstimatedTimetableUpdate`, and no
  reverse index from affected object to situation. The flat id-sets described in
  "Domain model" are what such an index would later be built from.
- No modelling of `AffectedRoute`, `AffectedSection`, stop-place components,
  accessibility assessments, or consequences. These exist in the avro model but
  are dropped from the GraphQL surface.
- No Prometheus instrumentation of open-ended situations. Visibility for the
  quality tooling is via GraphQL only.
- No persistence. Like VM and ET, situations live in memory only.

## Background

`siri-avro-model` 2.0.4 — already a dependency — ships the full SX object graph
(`PtSituationElementRecord`, `AffectsRecord`, `AffectedNetworkRecord`,
`AffectedStopPlaceRecord`, …) and `JsonReader.readPtSituationElement(String)`.
No new dependency is required.

The Pub/Sub topic `avro.situation_exchange` already exists in the same project
as the VM and ET topics, declared in
`entur/anshar-deployment-config` at `terraform/main.tf`.

In the avro schema, `progress`, `severity`, `reportType` and `sourceType` are
plain strings documented as *"Value from WorkflowStatusEnum"* etc., not avro
enums. The corresponding GraphQL enums are therefore defined by this service,
with defensive parsing.

## Architecture

The pipeline mirrors the existing ET path exactly:

```
Pub/Sub avro.situation_exchange
    ↓
PubSubSXSubscriber  (JsonReader.readPtSituationElement)
    ↓
SituationRepository.add()  — enrichment via LineService / OperatorService / NSRService
    ↓
AutoPurgingSituationMap  (in-memory, keyed by SituationKey)
    ↓
SituationUpdateRxPublisher  (Reactor Sinks.Many)
    ↓
Query.situations / Subscription.situations
```

### 1. Ingest

`PubSubSXSubscriber extends PubSubSubscriber`, a direct mirror of
`PubSubETSubscriber`. Its `MessageReceiver` calls
`JsonReader.readPtSituationElement(pubsubMessage.getData().toStringUtf8())`,
passes the record to `SituationRepository.add(...)`, and acks only after the
work completes.

New properties in `application.properties`:

```properties
entur.vehicle-positions.gcp.topic.name.sx=avro.situation_exchange
entur.vehicle-positions.gcp.subscription.name.sx=vehicle-positions.graphql-${random.uuid}
entur.vehicle-positions.sx.enabled=false
situation.updates.purge.interval=PT1M
situation.updates.expiry.grace.period=PT10M
```

The subscriber is disabled by default, matching `entur.vehicle-positions.et.enabled`.

### 2. Domain model

`org.entur.vehicles.data.SituationUpdate` is a **standalone class**. It does not
extend `AbstractUpdate`, because that base assumes a single line, operator and
service journey, whereas a situation affects many of each.

Fields:

| Field | Type | Source |
|---|---|---|
| `situationNumber` | `String` | `getSituationNumber()` |
| `participantRef` | `String` | `getParticipantRef()` |
| `codespace` | `Codespace` | derived, see below |
| `version` | `Integer` | `getVersion()` |
| `sourceType` | `String` | `getSource().getSourceType()` |
| `progress` | `WorkflowStatusEnumeration` | `getProgress()` |
| `severity` | `SeverityEnumeration` | `getSeverity()` |
| `priority` | `Integer` | `getPriority()` |
| `reportType` | `String` | `getReportType()` |
| `keywords` | `List<String>` | `getKeywords()` |
| `planned` | `Boolean` | `getPlanned()` |
| `creationTime` | `ZonedDateTime` | `getCreationTime()` |
| `versionedAtTime` | `ZonedDateTime` | `getVersionedAtTime()` |
| `validityPeriods` | `List<ValidityPeriod>` | `getValidityPeriods()` |
| `summaries` | `List<TranslatedString>` | `getSummaries()` |
| `descriptions` | `List<TranslatedString>` | `getDescriptions()` |
| `advices` | `List<TranslatedString>` | `getAdvices()` |
| `details` | `List<TranslatedString>` | `getDetails()` |
| `infoLinks` | `List<InfoLink>` | `getInfoLinks()` |
| `affects` | `Affects` | `getAffects()` |
| `lastUpdated` | `ZonedDateTime` | `versionedAtTime`, else `creationTime`, else now |
| `expiration` | `ZonedDateTime` (nullable) | computed, see "Lifecycle" |

Codespace is derived from `participantRef`. When `participantRef` is absent,
fall back to the prefix of `situationNumber` before the first `:` (situation
numbers follow the `RUT:SituationNumber:1234` pattern). If neither yields a
value the update is rejected and logged, consistent with how
`TimetableRepository` treats malformed records.

New supporting model classes in `org.entur.vehicles.data.model`:

- `TranslatedString(value, language)` — from `TranslatedStringRecord`
- `ValidityPeriod(startTime, endTime)` — from `ValidityPeriodRecord`, `endTime` nullable
- `InfoLink(uri, labels)` — from `InfoLinkRecord`
- `Affects` — described below

New enums in `org.entur.vehicles.data`:

- `WorkflowStatusEnumeration`: `draft`, `pendingApproval`, `approvedDraft`,
  `open`, `published`, `closing`, `closed`
- `SeverityEnumeration`: `unknown`, `verySlight`, `slight`, `normal`, `severe`,
  `verySevere`, `noImpact`, `undefined`

Both get a `fromValue(String)` that returns a defined fallback (`open` and
`undefined` respectively) rather than throwing on an unrecognised value,
following the existing `OccupancyStatus.fromValue` pattern. `reportType` stays a
plain `String` — its value set is not stable enough to pin into the schema.

#### Affects

`Affects` flattens the SIRI affects tree into lists of types this service
already has, reusing `Line`, `StopPoint`, `ServiceJourney`,
`DatedServiceJourney`, `Operator` and `VehicleModeEnumeration`:

| Field | Populated from |
|---|---|
| `lines` | `affects.networks[].affectedLines[].lineRef`, plus `affects.vehicleJourneys[].lineRef` |
| `stopPoints` | `affects.stopPoints[].stopPointRef` |
| `stopPlaces` | `affects.stopPlaces[].stopPlaceRef` |
| `serviceJourneys` | `affects.vehicleJourneys[].vehicleJourneyRefs[]` and `framedVehicleJourneyRef` |
| `datedServiceJourneys` | `affects.vehicleJourneys[].datedVehicleJourneyRefs[]` |
| `operators` | `affects.networks[].affectedOperators[].operatorRef`, plus `affects.vehicleJourneys[].operator.operatorRef` |
| `vehicleModes` | `affects.networks[].vehicleMode` |

Several rows draw from two places — `lines` and `operators` are each populated
from both the affected networks and the affected vehicle journeys. Entries are
deduplicated by reference, so a line named in both appears once.

Stop names come from `NSRService` and nowhere else. NSR is the single source of
truth for official stop names, so the `stopPointNames` and `placeNames` a
producer carries inside a situation are ignored, and a stop NSR does not know
has no name rather than falling back to the producer's. Ignoring them also means
the shared cached `StopPoint` that `NSRService` hands out — the same instance
`TimetableRepository` puts into `Call.stopPoint` — is never modified.

Alongside these display lists, `Affects` precomputes flat `Set<String>` id-sets:
`lineRefs`, `stopRefs` (stop points and stop places combined),
`serviceJourneyIds`, `datedServiceJourneyIds`, `operatorRefs`. These are built
once at ingest and are what `SituationFilter` matches against.

This matters for performance. Without them, every filter evaluation walks the
nested affects lists for every situation in the map on every query and every
published update; with them each criterion is an O(1) set lookup. These sets are
also the natural basis for the future reverse index.

Enrichment reuses the existing services exactly as `TimetableRepository` does:
`lineService.getLine(lineRef)` (falling back to `new Line(lineRef)` on
`ExecutionException`), `OperatorService.getOperator(operatorRef)`, and
`nsrService.getStop(stopRef)` for stop coordinates. Since a single situation can
affect many lines, line lookups run against the existing Guava `LoadingCache`;
no additional caching layer is introduced.

### 3. Storage

`AutoPurgingMap<T>` is currently `abstract class AutoPurgingMap<T> extends
ConcurrentHashMap<StorageKey, T>`. Generify it to `AutoPurgingMap<K, V> extends
ConcurrentHashMap<K, V>`. The existing subclasses become:

```java
class AutoPurgingVehicleMap   extends AutoPurgingMap<StorageKey, VehicleUpdate>
class AutoPurgingTimetableMap extends AutoPurgingMap<StorageKey, EstimatedTimetableUpdate>
class AutoPurgingSituationMap extends AutoPurgingMap<SituationKey, SituationUpdate>
```

This is a mechanical change — the purge-scheduling logic in the base class is
untouched, and `StorageKey` itself is unchanged.

`SituationKey(codespace, situationNumber)` follows `StorageKey`'s shape:
immutable, `hashCode` precomputed in the constructor, `equals` over both fields.

`AutoPurgingSituationMap` uses `situation.updates.purge.interval` (default
`PT1M`) and `situation.updates.expiry.grace.period` (default `PT10M`).

### 4. Lifecycle

**Version guard.** Pub/Sub gives no ordering guarantee, so a redelivered or
out-of-order record can carry an older version of a situation already stored. On
`add`, if an entry exists for the key and the incoming `version` is lower than
the stored one, the update is ignored. A null version on either side is treated
as "no information" and the update is accepted.

**Expiration** is nullable, where `null` means *never expires*:

1. `progress == closed` → expiration is `now`. The closed situation is stored and
   published to subscribers once, so clients observe `progress: closed` and can
   remove it from display, then it is purged after the grace period.
2. Otherwise, if any validity period carries an `endTime` → expiration is the
   latest such `endTime`.
3. Otherwise (open-ended) → expiration is `null` and the situation is retained
   indefinitely.

`AutoPurgingSituationMap.removeExpiredVehicles()` therefore skips entries with a
null expiration:

```java
entrySet().removeIf(entry -> {
    ZonedDateTime expiration = entry.getValue().getExpiration();
    return expiration != null
        && expiration.plus(gracePeriod).isBefore(ZonedDateTime.now());
});
```

An open-ended situation leaves the map only when a later version supplies an end
time or sets `progress` to `closed`.

**Accepted consequence.** Memory grows without bound if producers never close
their situations. That is the defect the quality tooling is meant to detect, so
retention is deliberate. Given SX volumes (thousands of situations nationally,
not millions) the memory cost is acceptable. Note that with GraphQL-only
visibility (see "Metrics") nothing alerts automatically on this growth — the
quality tool polling `situations(openEnded: true)` is the sole signal.

### 5. Filtering

A new `org.entur.vehicles.data.SituationFilter` with
`boolean isMatch(SituationUpdate)`.

`QueryFilter` is left untouched. It already carries a 16-argument constructor and
two near-duplicate `isMatch` methods; adding SX criteria would grow it further,
and SX matching is structurally different — it tests membership in *collections*
of affected objects rather than equality against single values.

`SituationFilter` declares its own `bufferSize` and `bufferTimeMillis` fields.
An earlier idea to extract these into a shared base with `QueryFilter` was
dropped: `QueryFilter extends AbstractUpdate`, and Java's single inheritance
makes a common superclass impossible without restructuring the update hierarchy.
Duplicating two fields is cheaper than that refactor.

Criteria, each ignored when null:

| Criterion | Matches when |
|---|---|
| `situationNumbers: Set<String>` | `situationNumber` is in the set |
| `codespaceId` | codespace matches |
| `operatorRef` | `affects.operatorRefs` contains it |
| `lineRef` | `affects.lineRefs` contains it |
| `stopRef` | `affects.stopRefs` contains it (stop points **and** stop places) |
| `serviceJourneyId` | `affects.serviceJourneyIds` contains it |
| `datedServiceJourneyId` | `affects.datedServiceJourneyIds` contains it |
| `mode` | `affects.vehicleModes` contains it |
| `severity` | equals |
| `reportType` | equals |
| `validNow: Boolean` | `true` → some validity period has started and has not ended |
| `openEnded: Boolean` | `true` → no validity period carries an `endTime` |
| `minAge: Duration` | `creationTime` is older than `now - minAge` |
| `includeClosed: Boolean` | when false, `progress != closed` |

Matching short-circuits on the first failing criterion, following `QueryFilter`'s
existing style.

`includeClosed` defaults to `false` on `Query.situations` but to `true` on
`Subscription.situations` — deliberately: a live subscriber needs to observe a
situation transitioning to `progress: closed` so it can remove it from display,
whereas a one-time query snapshot has no use for a situation that is already
gone. See "GraphQL API" below.

### 6. GraphQL API

`SituationUpdateRxPublisher` mirrors `EstimatedTimetableUpdateRxPublisher`: a
`Sinks.Many` multicast sink, `startWith` an initial snapshot from the
repository, filter by the supplied `SituationFilter`, `bufferTimeout` on
`bufferSize`/`bufferTime`, and `onBackpressureDrop()`.

Schema additions in `src/main/resources/graphql/vehicle-updates.graphqls`:

```graphql
type Situation {
    situationNumber: String!
    participantRef: String
    codespace: Codespace
    version: Int
    sourceType: String
    progress: WorkflowStatusEnumeration
    severity: SeverityEnumeration
    priority: Int
    reportType: String
    keywords: [String]
    planned: Boolean
    creationTime: DateTime
    versionedAtTime: DateTime
    validityPeriods: [ValidityPeriod]
    summary: [TranslatedString]
    description: [TranslatedString]
    advice: [TranslatedString]
    detail: [TranslatedString]
    infoLinks: [InfoLink]
    affects: Affects
    lastUpdated: DateTime
    lastUpdatedEpochSecond: Float
    expiration: DateTime
    expirationEpochSecond: Float

    # True when no validity period carries an end time.
    openEnded: Boolean
    # Time elapsed since creationTime. Null when creationTime is absent.
    age: Duration
}

type Affects {
    lines: [Line]
    stopPoints: [Stop]
    stopPlaces: [Stop]
    serviceJourneys: [ServiceJourney]
    datedServiceJourneys: [DatedServiceJourney]
    operators: [Operator]
    vehicleModes: [VehicleModeEnumeration]
}

type ValidityPeriod {
    startTime: DateTime
    endTime: DateTime
}

type TranslatedString {
    value: String
    language: String
}

type InfoLink {
    uri: String
    labels: [TranslatedString]
}

enum WorkflowStatusEnumeration {
    draft
    pendingApproval
    approvedDraft
    open
    published
    closing
    closed
}

enum SeverityEnumeration {
    unknown
    verySlight
    slight
    normal
    severe
    verySevere
    noImpact
    undefined
}
```

`expiration` is nullable in the schema already by virtue of GraphQL defaults; a
null value means the situation never expires.

Query and subscription, sharing the same filter arguments:

```graphql
type Query {
    situations(
        situationNumbers: [String]
        codespaceId: String
        operatorRef: String
        lineRef: String
        stopRef: String
        serviceJourneyId: String
        datedServiceJourneyId: String
        mode: VehicleModeEnumeration
        severity: SeverityEnumeration
        reportType: String
        validNow: Boolean
        openEnded: Boolean
        minAge: Duration
        includeClosed: Boolean = false) : [Situation]
}

type Subscription {
    situations(
        situationNumbers: [String]
        codespaceId: String
        operatorRef: String
        lineRef: String
        stopRef: String
        serviceJourneyId: String
        datedServiceJourneyId: String
        mode: VehicleModeEnumeration
        severity: SeverityEnumeration
        reportType: String
        validNow: Boolean
        openEnded: Boolean
        minAge: Duration
        # Defaults to true here, unlike Query.situations - a live subscriber needs to
        # observe a situation closing so it can drop it from display.
        includeClosed: Boolean = true
        # Number of updates buffered before data is pushed. May be used in combination with bufferTime.
        bufferSize: Int = 20
        # How long - in milliseconds - data is buffered before data is pushed. May be used in combination with bufferSize.
        bufferTime: Int = 250) : [Situation]
}
```

The two defaults differ deliberately: the query's snapshot has no use for a
situation that is already gone, while a live subscription must surface the
`progress: closed` transition so clients can react to it. One consequence is
that a new subscription's initial snapshot also includes situations closed
within the grace period, not just live ones.

The quality tooling's primary query is
`situations(openEnded: true, minAge: "P30D")`.

`Query.situations` and `Subscription.situations` follow the existing controller
methods in `Query.java` and `Subscription.java`: build a `SituationFilter`, log
at debug, delegate to repository or publisher.

### 7. Metrics

Parity with VM and ET only:

- `markSituationUpdate(int count, Codespace codespace)` incrementing counter
  `app.vehicles.situation.data`, tagged by `codespaceId`, following
  `markTimetableUpdate`'s shape including the rate logging.
- `markSituationsQuery()` and a `situations` query-type label alongside the
  existing `vehicles`, `lines`, `operators` labels.

No gauges for open-ended or stale situations; that surface is GraphQL only.

## Testing

- **`SituationGraphQLTests`** — mirrors `VehicleGraphQLTests`. Construct
  `PtSituationElementRecord` instances by hand with mocked `LineService`,
  `OperatorService` and `NSRService`, add them via `SituationRepository`, and
  assert each filter criterion in isolation and in combination. Covers the
  affects-matching paths for lines, stops, journeys, operators and modes.
- **`SituationFilterTest`** — unit tests for the validity window (`validNow`
  against periods that have not started, are current, and have ended),
  `openEnded`, `minAge`, and `includeClosed` defaulting to exclusion.
- **`SituationLifecycleTest`** — the version guard (an older version does not
  overwrite a newer one), expiration computation for all three branches, and
  purge behaviour using an `AutoPurgingSituationMap` built with `PT1S`
  durations. Must assert that an open-ended situation survives a purge cycle.
- **Regression** — the existing VM and ET test suites must pass unchanged after
  the `AutoPurgingMap<K, V>` generification. That refactor has no behavioural
  component; a green existing suite is the acceptance criterion.

## Success criteria

1. With `entur.vehicle-positions.sx.enabled=true`, situations from
   `avro.situation_exchange` are ingested and returned by
   `query { situations { situationNumber } }`.
2. Every filter argument narrows results correctly, individually and combined.
3. A subscription delivers an initial snapshot followed by ongoing updates,
   respecting `bufferSize` and `bufferTime`.
4. A situation whose `progress` becomes `closed` is published once to active
   subscribers, then disappears from queries after the grace period.
5. A situation with no validity end time is still present after several purge
   cycles, and is returned by `situations(openEnded: true)`.
6. `mvn clean install` passes with the existing VM and ET tests unmodified.

## Open questions

None. All design decisions are settled above.