# Replacing JourneyPlanner lookups with internal NeTEx planned data

Date: 2026-08-25
Status: Approved design, ready for implementation planning

## Goal

Stop calling the JourneyPlanner GraphQL API for planned data. Load the data this service
needs from the aggregated NeTEx export instead, hold it in memory, and refresh it nightly.

Today every vehicle and timetable update is enriched with line, operator, service journey
and dated service journey metadata fetched from JourneyPlanner
(`src/main/java/org/entur/vehicles/service/`):

| Service | Data fetched | Pattern | Load profile |
|---|---|---|---|
| `LineService` | id, name, publicCode | All lines at startup, then per-miss async | Light |
| `OperatorService` | id, name | All operators at startup, hourly refresh | Light |
| `ServiceJourneyService` (SJ) | `pointsOnLink` geometry | Per journey, async, throttled (2 threads, 50 ms sleep) | Heavy: one request per active journey, thousands at startup |
| `ServiceJourneyService` (DSJ) | operatingDay + SJ geometry | Per DSJ, **synchronous** in the ingest path, 1 h cache | Heavy, and blocks ingestion |

This puts a large, bursty load on an external service - worst at startup, when every
replica re-requests every active journey - and makes ingestion latency depend on it.

Everything JourneyPlanner supplies is derivable from the aggregated NeTEx export
(`rb_norway-aggregated-netex.zip`, 262 MB zipped, 6.0 GB unzipped, 4,395 files), which is
what JourneyPlanner's own graph is built from. Measured cardinalities, 2023-11-23 export:

| Entity | Count |
|---|---|
| ServiceJourney | 465,407 |
| DatedServiceJourney | 152,730 |
| JourneyPattern | 27,147 |
| ServiceLink | 139,151 (122,444 with geometry) |
| Geometry coordinates | 19.8 M points |
| Line / FlexibleLine | 4,190 / 141 |
| Operator | 263 |

## Decisions already made

- **Geometry is kept.** `pointsOnLink` is schema-deprecated but in use; clients must see
  no change in format.
- **Nightly reload** with an atomic swap, not load-once.
- **JourneyPlanner is removed entirely** - no fallback, no feature flag. Its graph comes
  from the same export, so a miss here is a miss there too.
- **Readiness is gated on the load.** A pod serves nothing until the dataset is in place;
  every stored entry is fully enriched from the first message.
- **Streaming extraction** (StAX), not JAXB via `netex-parser-java`. JAXB materialises
  every element; 6 GB of XML is not viable in a 5 GB heap and is 5-10x slower. A
  pre-processed artifact produced by a separate job was considered and rejected as
  over-engineering for now; the extractor built here is the natural seed for one if it is
  ever needed.

## Non-goals

- **`Query.lines`, `Query.operators`, `Query.serviceJourneys`** keep returning what they
  return today (derived from active vehicles). Serving the full planned catalogue from the
  dataset is a separate feature.
- **No straight-line fallback for geometry-less ServiceLinks** (about 17k of 139k). OTP
  draws a straight line between stops there; we leave a gap. Follow-up if fidelity matters
  - it needs a ScheduledStopPoint -> Quay -> NSR coordinate chain.
- **No re-enrichment of stored entries** after a nightly swap. Entries keep the objects
  they were enriched with until their next update.
- **No change to `NSRService`.** It keeps loading the stop register via JAXB as today.

## Architecture

```
GCS (marduk-production/outbound/netex/rb_norway-aggregated-netex.zip)
    |  download to temp file (startup + nightly)
    v
NetexPlannedDataExtractor  -- XMLStreamReader per zip entry, token-level skip of everything
    |                          not listed below
    v
PlannedDataset.Builder     -- collects refs; resolves them once in build()
    |
    v
PlannedDataService         -- AtomicReference<PlannedDataset>, swap on success
    |
    v
LineService / OperatorService / ServiceJourneyService   -- unchanged public API, O(1) lookups
    |
    v
VehicleRepository / TimetableRepository / SituationMapper  -- untouched
```

## Components

All new code lives in `org.entur.vehicles.service.planned`.

### `PlannedDataset`

Immutable snapshot, one per load. Contents:

| Field | Type | Source element | Approx. size |
|---|---|---|---|
| `operators` | `Map<String, Operator>` | `Operator` (id, `Name`) | 263 |
| `lines` | `Map<String, Line>` | `Line` and `FlexibleLine` (id, `Name`, `PublicCode`) | 4.3k |
| `serviceJourneyPattern` | `Map<String, String>` | `ServiceJourney` id -> `JourneyPatternRef` | 465k |
| `datedServiceJourneys` | `Map<String, DatedJourneyRef>` | `DatedServiceJourney` id -> (`ServiceJourneyRef`, operating date) | 153k |
| `patternLinks` | `Map<String, String[]>` | `JourneyPattern` id -> ordered `ServiceLinkRef`s from `linksInSequence` | 27k |
| `linkGeometry` | `Map<String, int[]>` | `ServiceLink` id -> interleaved lat/lon from `gis:posList`, as microdegrees | 139k links, 19.8 M points, ~160 MB |
| `patternPolylines` | `ConcurrentHashMap<String, PointsOnLink>` | computed lazily, see Geometry | up to ~80 MB if every pattern is requested |

`DatedJourneyRef` is a record `(String serviceJourneyId, String operatingDate)`. The
`OperatingDayRef` is resolved to the `CalendarDate` at build time; the dataset never
holds OperatingDay ids.

Coordinates are stored as `int` microdegrees (`Math.round(value * 1e6)`), which keeps the
full 6-decimal precision of the export at half the size of `double`.

The dataset exposes typed lookups: `operator(id)`, `line(id)`, `serviceJourney(id)`,
`datedServiceJourney(id)`, `pointsOnLink(patternId)`. Misses return `null`; the services
translate a miss into today's bare-ref fallback objects.

`PlannedDataset.EMPTY` is the dataset used when planned data is disabled.

### `NetexPlannedDataExtractor`

Streams one zip entry through `javax.xml.stream.XMLStreamReader` and feeds a
`PlannedDataset.Builder`. Handled elements and the fields taken from each:

- `Operator`: `@id`, `Name`
- `Line`, `FlexibleLine`: `@id`, `Name`, `PublicCode`
- `ServiceLink`: `@id`, the `gis:posList` text under `projections/LinkSequenceProjection`
- `JourneyPattern`: `@id`, the `ServiceLinkRef/@ref` values under `linksInSequence`,
  in document order
- `ServiceJourney`: `@id`, `JourneyPatternRef/@ref`
- `DatedServiceJourney`: `@id`, `ServiceJourneyRef/@ref`, `OperatingDayRef/@ref`
- `OperatingDay`: `@id`, `CalendarDate`

Everything else is skipped at the token level, so memory is bounded by what is kept, not
by the size of the XML. `posList` text is parsed straight into an `int[]` without
intermediate `String[]` splitting - at 19.8 M points, that matters.

Files are processed in zip order. Nothing depends on that order: cross-file references
(line file -> shared-data ServiceLink or OperatingDay) are resolved once in
`Builder.build()`.

### `PlannedDataService`

Owns `AtomicReference<PlannedDataset>` and exposes `current()`.

- `@PostConstruct`: if enabled, run one load; on failure, throw. The Spring context - and
  therefore `/actuator/health` - does not come up until the dataset is in place. This is
  the same pattern `NSRService` uses and is the readiness gate.
- `@Scheduled(cron = "${vehicle.planned.data.reload.cron}")`: run one load; on failure,
  log at ERROR, increment the failure counter, keep the current dataset.
- A load is: download the zip to a temp file (as `NSRService.readUrl` does today), open
  it as a `ZipFile`, stream every `*.xml` entry through the extractor, `build()`, validate,
  swap, delete the temp file. The previous dataset becomes garbage on swap; peak memory is
  roughly two datasets for the duration of a build.

### Changed services

`LineService`, `OperatorService`, `ServiceJourneyService` keep their public methods:

- `LineService.getLine(String)`
- `OperatorService.getOperator(String)` (static today - stays static to avoid touching
  `SituationMapper`, `VehicleRepository`, `TimetableRepository`; it reads a static
  reference set by the service on construction)
- `ServiceJourneyService.getServiceJourney(String)`
- `ServiceJourneyService.getDatedServiceJourney(String)`

Internals become lookups on `PlannedDataService.current()`. Misses return exactly what a
failed lookup returns today: `new Line(ref)`, `null` operator, `new ServiceJourney(id)`,
`new DatedServiceJourney(id, new ServiceJourney(id))`.

`getServiceJourney` returns a **fresh** `ServiceJourney` per call, sharing only the
immutable `PointsOnLink`. `VehicleRepository` and `TimetableRepository` call
`setDate()` on the returned object; today that mutates a cached instance shared across
every vehicle on that journey. This change removes that shared mutation.

`getDatedServiceJourney` returns a fresh `DatedServiceJourney` with `operatingDay` set
from the dataset and a fresh `ServiceJourney` carrying `date = operatingDay` and the
pattern's `PointsOnLink` - the same shape `lookupDatedServiceJourney` builds today.

Removed from these services: the Guava `LoadingCache`s, the executor thread pools, the
`concurrentRequests`/`sleepTime` throttling, the `initialized` bookkeeping, and the
`contains(":Line:")`-style id shortcuts (a map miss is free, so they no longer earn their
place).

### Deleted

- `JourneyPlannerGraphQLClient`
- `service/graphql/Data`, `service/graphql/Response`
- `PrometheusMetricsService.markJourneyPlannerRequest` / `markJourneyPlannerResponse`
- Config: `vehicle.journeyplanner.url`, `vehicle.journeyplanner.EtClientName`,
  `vehicle.line.lookup.enabled`, `vehicle.operator.lookup.enabled`,
  `vehicle.serviceJourney.lookup.enabled`, `vehicle.datedserviceJourney.lookup.enabled`,
  `vehicle.line.concurrent.*`, `vehicle.serviceJourney.concurrent.*` - in
  `application.properties`, the helm `configmap.yaml`, `values.yaml`, and every
  `env/values-*.yaml`.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `vehicle.planned.data.enabled` | `false` | `true` in helm for every environment. When false, `current()` is `PlannedDataset.EMPTY` and every lookup returns bare refs - today's local-dev behaviour. |
| `vehicle.planned.data.url` | `https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip` | Verified reachable 2026-08-25. A `file:` URL works for local runs against a downloaded copy. |
| `vehicle.planned.data.reload.cron` | `0 0 8 * * *` (Europe/Oslo) | The export observed on 2026-08-25 was written at 07:33 UTC, so a reload earlier than ~08:30 Oslo time would pick up the previous day's file. **Confirm Marduk's actual completion time before setting this in helm.** |
| `vehicle.planned.data.min.service.journeys` | `50000` | A fresh dataset with fewer service journeys than this is rejected outright, before the relative shrink check and even on the very first load - guards a truncated-but-non-empty export against every restarting pod, since the relative `< 50%` guard has nothing to compare against on a first load. `0` in tests. |

## Geometry

`pointsOnLink` for a service journey is computed per journey pattern, on first request,
and cached in `patternPolylines` for the life of the dataset:

1. Walk `patternLinks[patternId]` in order. For each link with geometry, append its
   points. If the link's first point equals the previous link's last point, skip that
   duplicate join point.
2. Links without geometry are skipped, leaving a gap. (See non-goals.)
3. Encode as a Google encoded polyline at 5-decimal precision - the format OTP returns in
   `pointsOnLink.points`. `length` is the number of points, as OTP defines it.

A pattern with no resolvable geometry at all yields `null` `pointsOnLink`, which is what
a JourneyPlanner miss yields today.

## Error handling

| Situation | Behaviour |
|---|---|
| Download fails or zip unreadable, at startup | Throw from `@PostConstruct`; the pod fails to start and k8s restarts it with backoff. Consistent with `NSRService`. |
| Download fails or zip unreadable, nightly | Log ERROR, count, keep current dataset. |
| A single zip entry fails to parse | Log ERROR with the entry name, skip it, continue. |
| Zero line files parsed | Treat the load as failed (startup: throw; nightly: keep current). |
| Nightly dataset has < 50% of the current dataset's service journeys | Reject the swap, log ERROR, count. Guards against a truncated export. |
| Fresh dataset has fewer than `vehicle.planned.data.min.service.journeys` (default 50,000) service journeys | Reject, regardless of load count - applies to the first load too, where the relative guard above has nothing to compare against. |
| SJ -> unknown pattern, pattern -> unknown link, DSJ -> unknown SJ or OperatingDay | Resolve to "no geometry" / "no date". Count each kind; log one summary line per load. Never throw. |
| Same id declared in more than one file | Last one wins. Count, log in the summary. |
| Lookup for an id not in the dataset | Bare-ref fallback object, exactly as a failed JourneyPlanner lookup today. Counted per type. |

## Observability

Replacing the JourneyPlanner request/response counters:

- `planned_data_load_duration_seconds` (last load)
- `planned_data_last_success_timestamp`
- `planned_data_entities{type=operator|line|serviceJourney|datedServiceJourney|journeyPattern|serviceLink}`
- `planned_data_load_failures_total`
- `planned_data_unresolved_refs_total{kind=...}` from the build summary
- `planned_data_lookup_miss_total{type=line|operator|serviceJourney|datedServiceJourney}`

The miss counters are the signal that producers reference ids the export lacks.

## Deployment

- Raise `startupProbe.failureThreshold` in `helm/vehicle-positions-2/templates/deployment.yaml`
  from 25 to 60 (5 minutes at `periodSeconds: 5`). Streaming 6 GB is expected to take on
  the order of a minute on top of the NSR load; the final value is set from the measured
  load time, not this estimate.
- Memory: dataset ~250-400 MB resident, ~2x during a build. Confirm current heap headroom
  in prod against the 5 GB `-Xmx` before merging.
- Every replica (up to 20) downloads 262 MB nightly from public GCS. Acceptable; no
  jitter for now.

### Measured (2026-08-25, full Norway export, local run)

- Load time: 20.8 s (download 0.3 s + parse/build 20.5 s), for the 262 MB zipped /
  ~6 GB unzipped `rb_norway-aggregated-netex.zip` export, `-Xmx5G -XX:+UseG1GC`,
  local run (`Planned data loaded in 20815 ms`, `Download of ... took 288 ms`,
  `Started Application in 23.586 seconds`). This is well inside the sanity band
  (60-120 s expected); much faster than the GOA-fixture extrapolation suggested.
- Retained heap after load and GC: 302 MB (523 MB before `jcmd GC.run`, 302 MB
  after) - within the expected 250-400 MB band.
- Prod heap peak (7 days, before this change): not measured — check Grafana.
  `jvm_memory_used_bytes{area="heap"}` on `ent-vpos-prd` returned zero series via
  Kompass `read_metrics` (two attempts); the Grafana dashboard for `ent-vpos-prd`
  was not checked directly. The memory headroom check in the paragraph above
  (retained size x2 + prod peak < 5 GB) is therefore unconfirmed - confirm in
  Grafana before merging.
- NSR warm-up time: not measured — check Grafana/prod logs. `vehicle.nsr.lookup.enabled=true`
  in `helm/vehicle-positions-2/templates/configmap.yaml`, so NSR warm-up does run
  and block readiness in prod, but no `NSRService cache warm-up took` log line was
  found on `ent-vpos-prd` in the last 24h (likely no pod restart in that window).
  The probe arithmetic below therefore omits this component and is a lower bound.
- Startup probe `failureThreshold` set to: left at 60 (unchanged). Local
  arithmetic: (download 0.3 s + parse/build 20.5 s) x 2 / `periodSeconds: 5` =
  41.6 / 5 ≈ 9, well under 60. This excludes the unmeasured NSR warm-up
  component that does run in prod; re-run this arithmetic with a real NSR
  warm-up number (from a prod log line after the next pod restart) before
  concluding 60 is definitely sufficient.

## Testing

- **Extractor unit tests** with small hand-written NeTEx fragments under
  `src/test/resources/netex/`: one per handled element, plus dangling refs, duplicate ids,
  a `ServiceLink` without `gis:posList`, and an entry with malformed XML.
- **Geometry tests:** join-point deduplication, gap handling, `length`, and polyline
  encoding checked against a couple of real `pointsOnLink` values captured from the dev
  JourneyPlanner for the same journeys - the one thing worth verifying against the real
  source while it is still reachable.
- **Integration test** over `rb_goa-aggregated-netex.zip` (1.3 MB, checked in under
  `src/test/resources/netex/`): zip -> `PlannedDataset`, asserting entity counts and a
  spot-check of one line, operator, service journey and dated service journey.
- **Service tests** for the three services against a fixture dataset: hit, miss,
  disabled, and that `getServiceJourney` returns distinct instances.
- **Reload tests:** a successful load swaps the reference; a failing load keeps the old
  dataset; the shrunk-dataset guard rejects.
- **Manual check before merging:** run the full Norway file locally with
  `-Xmx5G`, record load time and heap delta, and set the probe threshold from that.

Existing `NSRService*`, repository and GraphQL tests are expected to pass unchanged; the
service signatures the repositories depend on do not move.
