# Startup snapshots: skip the NeTEx and NSR parses on pod startup

Date: 2026-09-02
Status: Implemented and measured in dev 2026-09-02; enabled in tst and prd from the same date

## Goal

Cut pod startup time by not parsing the two large NeTEx exports on every start. Both
parses produce a small dataset from a large file, and every replica produces the same
dataset from the same export. Do each parse once per export, keep the result in a bucket,
and let every later pod read the result instead.

Measured on a dev pod started 2026-09-02 11:20 (Europe/Oslo), from the log timeline of
`vehicle-positions-2-556c8d4cd5-g4572`:

| Startup stage | Time |
|---|---|
| JVM and Spring context up to the planned-data load | 3 s |
| Download of the aggregated NeTEx zip (280 MB) | 5 s |
| StAX parse of the zip into `PlannedDataset.Builder` | 67 s |
| `Builder.build()` (canonicalise, resolve refs) | 1.5 s |
| Download of the NSR stop-place zip (42 MB) | 8 s |
| JAXB parse of the NSR zip and cache warm-up (`NSRService`) | 18 s |
| Remaining beans, GraphQL, Tomcat | 9 s |
| **Total to "Started Application"** | **112 s** |

Across the 15 dev startups in the preceding 24 hours the planned-data stage
("Planned data loaded in") took 66 to 96 s and the NSR warm-up 24 to 26 s. The parses
are the cost. What they yield is small:

| Dataset | Content | Cardinality (2026-09-02) |
|---|---|---|
| Planned data | operators, lines, service journeys, dated service journeys, journey patterns, service links with geometry | 273 / 4,767 / 359k / 2.2M / 29k / 137k |
| NSR | stop places and quays with name and location, child-to-parent refs | 104k refs with ancestors; stop points on the order of 200k |

### Success criteria

- A pod that finds both snapshots spends under 10 s on the planned-data stage and under
  3 s on the NSR stage, measured by the existing "Planned data loaded in" and
  "NSRService cache warm-up took" log lines.
- A pod that does not find a snapshot behaves exactly as today, plus writes one.
- The bucket is a cache, never a dependency: any failure on the snapshot path degrades
  to the current full parse, and readiness still waits for the datasets.
- A snapshot is only ever used for the exact export it was built from.

## Decisions already made

- **No separate cron job.** The pod that misses the cache parses and uploads. A separate
  job would be a second deployable with its own IAM and a format-version handshake
  between two images; the pods already contain the extractors and already run under a
  service account. See Alternatives.
- **Snapshots are keyed by the export's identity, not by time.** The object name carries
  the export's ETag, so a hit is by construction the data the pod would otherwise have
  parsed. Both exports live on `storage.googleapis.com` and return an MD5-hex ETag.
- **One store, one downloader, one key scheme, two datasets.** `SnapshotStore`, the
  HTTP download with ETag capture, the background uploader and the object naming are
  shared. Planned data and NSR each have their own record format, format version and
  object path.
- **Planned data snapshot content is the extractor's raw output, replayed through the
  builder.** The snapshot holds the tuples `NetexPlannedDataExtractor` emits, and a hit
  feeds them into `PlannedDataset.Builder` and calls `build()` as today. Canonicalisation,
  ref resolution, the unresolved counters, `Stats` and the sanity checks all stay in one
  place and stay identical on both paths. `build()` costs 1.5 s, which is inside budget.
- **NSR snapshot content is the two maps `NSRService` ends up with**: stop points and
  child-to-parent refs. The JAXB parse builds them in memory before anything is
  installed, so the snapshot is written from the finished maps, not teed. The ancestor
  flattening runs on both paths from the child-to-parent map, so it stays in one place.
- **Hand-written binary format, no new serialisation library.** Tagged record streams
  over `DataOutputStream`, gzipped. Nothing else in the service needs a serialisation
  library, and each format is under 150 lines including its reader.
- **Format version in the object path.** An image with a newer format never finds an
  older snapshot, and vice versa. No in-band migration.
- **Upload happens after the dataset is live, on a background thread.** The miss path's
  readiness time must not grow by the gzip and upload.

## Non-goals

- Parallelising the planned-data zip parse or replacing the NSR JAXB parse with StAX.
  Both would speed up the miss path only; they are orthogonal and can follow later.
- Adding a nightly reload to `NSRService`. It has none today; a pod keeps its NSR data
  until it restarts. Unchanged.
- Staggering the nightly planned-data reload so only one replica parses. The reload is
  off the readiness path; all replicas parsing once a day is acceptable. The winner's
  upload still benefits every pod started later that day.
- Sharing one bucket across dev, tst and prd. All three environments read the same
  production exports, so they could share, but cross-environment IAM is not worth the
  saving. One bucket per environment.
- Fixing the reload schedule. The reload runs at 08:00 Europe/Oslo (06:00 UTC in summer)
  and the export lands after 07:30 UTC, so the reload picks up the previous day's export
  (dev logs from 2026-09-02 show 359,069 service journeys at the 08:00 reload and 359,065
  from the export that pods started at 11:19 fetched). ETag keying makes this harmless
  for snapshots; the schedule itself is a separate change.

## Architecture

The same shape for both datasets. Shown for planned data; NSR differs only in what
"parse" and "replay" mean and in having no tee.

```
load()
    │
    ├─ HEAD export URL ──────────► etag
    │
    ├─ SnapshotStore.open(key(etag)) ── hit ──► reader.replay(in) ► dataset
    │                                                       │
    │                                     miss / any error  │
    │                                           │           │
    ├─ GET export URL ► zip (etag from response headers)    │
    │       │                                               │
    │       └─ parse ► dataset (+ snapshot temp file)       │
    │                                                       │
    ├─ sanity checks ► install dataset ◄────────────────────┘
    │
    └─ (miss only, async) gzip temp file ► SnapshotStore.putIfAbsent(key(etag), file)
```

Keys:

```
<prefix>/planned-data/v<PLANNED_FORMAT_VERSION>/<etag>.bin.gz
<prefix>/nsr/v<NSR_FORMAT_VERSION>/<etag>.bin.gz
```

Each format version is a constant next to its reader, bumped whenever that record
layout or the set of extracted fields changes. The ETag is taken verbatim from the
`ETag` header with surrounding quotes and any `W/` prefix stripped.

Two consistency points that matter:

- **The ETag used for the upload comes from the GET that produced the zip**, not from
  the earlier HEAD. If Marduk replaces an export between HEAD and GET, the snapshot is
  still named after the content it was built from.
- **Uploads use a does-not-exist precondition.** Several replicas that miss at the same
  time all parse and all try to upload; the first wins with HTTP 200, the rest get 412
  and log at info. No coordination, no leader.

## Shared components

New package `org.entur.vehicles.service.snapshot`.

### `SnapshotStore` (interface) with two implementations

```
Optional<InputStream> open(String key)      // empty on not-found; throws on other errors
boolean putIfAbsent(String key, Path file)  // false if the key already existed
void put(String key, Path file)             // unconditional; used only to replace a snapshot that failed to read
```

- `GcsSnapshotStore`: `com.google.cloud:google-cloud-storage` from the existing
  `libraries-bom`. Authenticates through Application Default Credentials the same way
  `google-cloud-pubsub` already does, so the `application` service account's Workload
  Identity binding covers it. `open` streams the object; the caller wraps it in
  `GZIPInputStream`. `putIfAbsent` uploads with `Storage.createFrom(info, file,
  BlobWriteOption.doesNotExist())` and maps 412 to `false`; `put` uploads with no
  precondition. Content type
  `application/octet-stream`.
- `FileSnapshotStore`: a directory. Used by the tests and available for local runs so a
  developer parses once and restarts fast afterwards. `putIfAbsent` writes to a temp
  name and renames without `ATOMIC_MOVE` so an existing target raises
  `FileAlreadyExistsException` (POSIX `rename(2)` with `ATOMIC_MOVE` would overwrite
  silently); `put` renames with `REPLACE_EXISTING`.

Chosen from one property, `vehicle.snapshot.uri`: `gs://bucket/prefix` selects GCS,
`file:///path` selects the directory, empty disables snapshots for both datasets. One
Spring bean, `SnapshotStore`, or an `Optional`-style disabled instance when the
property is empty, injected into both services.

### `ExportDownloader`

`java.net.http.HttpClient` wrapper with two methods: `head(url)` returning the ETag or
empty, and `download(url, target)` returning the response ETag or empty. 60 s connect
and read timeouts for both. Replaces `FileUtils.copyURLToFile` in `PlannedDataService`
(same timeouts) and `NSRService` (which today uses 5 s timeouts and returns null on
failure, which then fails the JAXB parse with an unhelpful exception; the new path
throws a clear `IOException` instead).

`HttpRequest.timeout` bounds only the time to the response headers, so `download` also
enforces an overall body deadline of 10 minutes: past it the request is cancelled and an
`IOException` is thrown, so a stalled 280 MB body cannot hang startup forever.

### `SnapshotUploader`

Owns a single-thread executor. `upload(store, key, rawFile, replaceExisting)` gzips the
raw file at `Deflater.BEST_SPEED` to a second temp file, calls `putIfAbsent` or `put`,
logs the outcome with key, size and duration, counts it, and deletes both temp files.
Never throws to the caller. Shut down on context close; an in-flight upload finishes
or is abandoned, both are fine since the precondition makes a partial re-upload
impossible and the next miss simply retries.

### `SnapshotKey`

`key(prefix, datasetName, formatVersion, etag)` and the ETag normalisation, in one
place so both datasets name objects the same way.

## Planned data

### `PlannedDataSink` (new interface, package `service.planned`)

The seven `add*` methods `NetexPlannedDataExtractor` calls, extracted from
`PlannedDataset.Builder` into an interface the builder implements:

```
addOperator(id, name)
addLine(id, name, publicCode)
addServiceLink(id, int[] geometry)        // geometry may be null
addJourneyPattern(id, List<String> serviceLinkIds)
addServiceJourney(id, journeyPatternId, lineId)   // refs may be null
addDatedServiceJourney(id, serviceJourneyId, operatingDayId)
addOperatingDay(id, calendarDate)
```

The extractor's signature changes from `Builder` to `PlannedDataSink`. The interface
methods return `PlannedDataSink`; `Builder` overrides them with the covariant return
type `Builder`, so the chained `new Builder().addLine(...).addOperator(...)` style the
GraphQL tests use keeps compiling. `TeeSink` and the snapshot writer return `this`.

### `TeeSink` (package-private)

A `PlannedDataSink` that forwards every call to two sinks: the builder and the
snapshot writer. Used only on the miss path.

### `PlannedDataSnapshot` (writer and reader)

Writer is a `PlannedDataSink` over a `DataOutputStream` on a buffered `FileOutputStream`
(uncompressed; compression happens at upload). Reader replays a `DataInputStream` into
any `PlannedDataSink`.

Format:

```
magic       4 bytes   "VPPD"
version     int       PLANNED_FORMAT_VERSION
etag        UTF       export ETag the records came from
createdAt   long      epoch millis
records     *         tag byte, then fields
end         byte      0xFF
count       int       number of records written, for a cheap truncation check
```

Record fields are `writeUTF` for strings (nullable fields carry a presence byte first),
`writeInt` counts, and `writeInt` per coordinate for geometry. NeTEx ids and names are
far below the 64 KB `writeUTF` limit; geometry is `int[]` and never goes through it.

Reader rules: wrong magic or version throws `SnapshotFormatException`; a record count
mismatch at the end marker throws; any `IOException` (including `EOFException` from a
truncated gzip member) propagates. The caller treats every exception the same way:
discard the builder, fall back to the full parse.

Expected sizes: 2.2M dated service journeys at roughly 110 bytes of raw ids each
dominate, giving about 250 MB uncompressed and an estimated 30 to 50 MB gzipped. Ids
compress well because they share long prefixes and run in sequences. The dev rollout
measured 118 MB gzipped — two to four times the estimate, since `BEST_SPEED` gzip on
`writeUTF` output exploits far less of that id structure than assumed. See Measured.

### `PlannedDataService` (changed)

`load()` becomes:

1. If snapshots are enabled: `downloader.head(url)`. On an ETag, `store.open(key)`. On
   a hit, gunzip and `reader.replay(in, builder)`, then go to step 3 with
   `source = "snapshot"`. Missing ETag, not-found, or any exception: log at info
   (not-found) or warn (anything else), remember whether an object existed but failed
   to read, and continue to step 2.
2. `downloader.download(url, zip)` capturing the response ETag. Parse through a
   `TeeSink` into the builder and a snapshot writer on a second temp file. If snapshots
   are disabled or no ETag came back, parse into the builder alone as today.
   `source = "netex"`.
3. `builder.build()`, the existing minimum-size and suspiciously-small checks,
   `current.set(fresh)`, metrics and the "Planned data loaded in {} ms" log line, now
   with `source` and the ETag appended.
4. Miss path only, after step 3: hand the raw temp file to `SnapshotUploader` with
   `replaceExisting` set if step 1 found an unreadable object. Delete the zip.

Temp files: the zip (280 MB) and the raw snapshot (about 250 MB) coexist briefly on the
container's ephemeral storage. The deployment currently sets no ephemeral-storage
request; add one of 1 GiB so scheduling accounts for it.

### `PlannedDataLoader` (changed)

`load(Path zip)` becomes `load(Path zip, PlannedDataSink sink)`; the loader no longer
owns the builder. Entry iteration, per-entry error handling, the line-file check and the
skipped-entries warning are unchanged.

## NSR

### `NsrData` (new record, package `service`)

```
record NsrData(Map<String, StopPoint> stopPoints, Map<String, String> childToParent)
```

Immutable maps. This is what the JAXB parse produces and what the snapshot round-trips.

### `NsrNetexParser` (extracted from `NSRService.warmUpCache`)

`NsrData parse(Path zip)`: the existing `NetexParser` call and the loop over stop
places and quays, moved out of the service unchanged, returning `NsrData` instead of
putting into the service's caches. Pure function of the zip, so the round-trip test can
compare its output with a replayed snapshot.

### `NsrSnapshot` (writer and reader)

Same header layout as the planned-data format with magic `"VNSR"` and
`NSR_FORMAT_VERSION`. Two record types:

```
tag 1  stopPoint   id UTF, name UTF (presence byte), longitude double, latitude double
tag 2  parent      childId UTF, parentId UTF
```

Written from an `NsrData`; read back into a new `NsrData`. Expected size is a few MB
gzipped, so it is written straight to the raw temp file after the parse and uploaded
through the shared uploader like the other one.

### `NSRService` (changed)

`warmUpCache` becomes:

1. If snapshots are enabled: `downloader.head(url)`, `store.open(key)`, on a hit gunzip
   and `NsrSnapshot.read(in)` to an `NsrData`, `source = "snapshot"`. Any failure falls
   through as for planned data.
2. `downloader.download(url, zip)`, `NsrNetexParser.parse(zip)`, `source = "nsr"`.
   Write `NsrSnapshot` to a temp file if an ETag came back.
3. `install(NsrData)`: put every stop point into `stopPointCache`, `flattenAncestors`
   the child-to-parent map into `ancestorsByRef`. This is the only place the caches are
   filled, on both paths. The existing "resolved ancestors for {} stop refs" and
   "cache warm-up took" log lines gain `source` and ETag.
4. Miss path only: hand the temp file to `SnapshotUploader`.

The test-seam constructor that takes a child-to-parent map stays and calls `install`
with an empty stop-point map, so `NSRServiceAncestorTest` is unchanged. A download or
parse failure remains fatal on startup, as today, but now with a clear cause instead of
a null path.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `vehicle.snapshot.uri` | empty | `gs://bucket/prefix`, `file:///dir`, or empty to disable snapshots for both datasets |

Helm: `configMap.snapshotUri`, set per environment in `env/values-*.yaml`, empty in
`values.yaml`. Bucket names follow whatever the platform's bucket provisioning
produces; the property carries the full URI so the code has no opinion on naming.

Bucket setup per environment, in `terraform/snapshots.tf`:

- Standard storage, single region matching the cluster, uniform bucket-level access. All
  three come from `terraform-google-cloud-storage//modules/bucket`, which also picks the
  location: `EUROPE-WEST1` in dev and tst, `EU` in prd.
- Named `ent-gcs-vpos-snapshots-<env>-001` by the module from `name_override`.
- Lifecycle rule: delete objects older than 7 days. One new object per export per format
  version per dataset, so steady state is a handful of objects. Object versioning and
  offsite backup are off — every object is rebuildable from its export.
- IAM: `roles/storage.objectAdmin` on the bucket for the `application` Kubernetes service
  account's Google service account (`module.init.service_accounts.default`). That role
  covers get and create; it also covers delete, which nothing uses. The narrower
  `roles/storage.objectUser` would fit better but is not on the platform's
  assignable-roles allowlist (`entur/ai`, `guides/platform/iam-roles.md`), so the policy
  guard rejects it at plan time.

## Error handling

Identical for both datasets unless a row says otherwise.

| Situation | Behaviour |
|---|---|
| Snapshot URI empty | Today's code path, no HEAD, no upload |
| HEAD fails or has no ETag | Info log, full parse, no upload (there is no key to upload under) |
| Bucket unreachable or 403 on open | Warn log, full parse; upload still attempted and will log its own failure |
| Object not found | Info log, full parse, upload |
| Snapshot corrupt, truncated, wrong magic or version | Warn log with the key, discard, full parse, then upload *without* the does-not-exist precondition so the bad object is replaced. Concurrent replacements carry identical content, so the race is harmless. |
| Snapshot temp file cannot be created or written on the miss path | Warn log, dataset still installed from the parse, no upload |
| Parse skipped one or more NeTEx entries | Dataset installed as today, no upload (a partial parse must not become the fleet's snapshot) |
| Replayed planned dataset fails the size checks | Same `PlannedDataLoadException` as today: fatal on startup, logged on reload |
| Export download or parse fails | Fatal on startup for both datasets, as today; logged on the planned-data reload |
| Upload gets 412 | Info log, done |
| Upload fails otherwise | Warn log, failure counter, temp files deleted |
| Export replaced between HEAD and GET | Harmless: upload is keyed by the GET's ETag |

The startup contract stays: readiness waits for both datasets, and a load that produces
no acceptable dataset is fatal on startup.

## Observability

- The existing "Planned data loaded in {} ms: {}" and "NSRService cache warm-up took"
  lines gain `from snapshot|export` and `etag=...`.
- New log lines from the shared components: snapshot hit or miss with key, upload
  outcome with key, size and duration.
- Metrics, in `PrometheusMetricsService`, all with a `dataset=planned|nsr` label:
  - `vehicle_snapshot_source` gauge with label `source`, 1 for the last load's source
    and 0 for the other, so a dashboard shows which path each pod took.
  - `vehicle_snapshot_upload_total` counter with label
    `outcome=uploaded|exists|failed`.
  - `vehicle_planned_data_load_duration_millis` already exists. Add
    `vehicle_nsr_load_duration_millis` alongside it.

## Alternatives considered

- **Separate cron job that parses and uploads.** Rejected: a second deployable, its own
  service account, and a version handshake between the job's format and the pods'
  reader. It also does nothing on the day a new format version ships until the job has
  run. The pods already have the extractors and the credentials.
- **Serialise the built `PlannedDataset` instead of the raw records.** Saves the 1.5 s
  build and some bytes, but needs a second constructor path into the dataset,
  duplicates the meaning of `Stats`, and makes snapshot equivalence harder to test. Not
  worth 1.5 s. NSR goes the other way, serialising the finished maps, because its parse
  produces them fully in memory anyway and there is no builder step to share.
- **Java serialisation, Kryo, protobuf.** Java serialisation is slow and brittle across
  versions; Kryo and protobuf are new dependencies for two files. The record streams
  are simpler than any of them.
- **Gzip inline during the planned-data parse.** Costs about 10 s on the miss path at
  the default level. Deferring compression to the background thread keeps the miss path
  at today's cost.
- **Key by `Last-Modified` instead of ETag.** Second resolution and not guaranteed to
  change if an object is rewritten with identical timestamps. ETag is the content
  identity. If a future export host omits ETag, snapshots are off for that load.
- **One combined snapshot object for both datasets.** The exports change independently
  and at different times of day, so a combined key would miss whenever either changed.
  Two objects, two keys.

## Expected result

| | Today | Target | Measured (dev) |
|---|---|---|---|
| Planned-data stage | 66 to 96 s | under 10 s | 14.2 s |
| NSR stage | 24 to 26 s | under 3 s | 5.2 s |
| Startup to "Started Application" | about 112 s | about 20 s | 31.7 s |

## Measured

Dev rollout of `73ed02a`, 2026-09-02 18:09 to 18:12 (Europe/Oslo). Two pods, one per
path, both on the same exports (planned data `78502bf4…`, NSR `b7cc7d32…`) and both
reporting identical `Stats[...]`: 379 796 service journeys, 2 227 779 dated service
journeys, 0 unresolved refs. The replay reproduces the parse exactly.

Miss path, `vehicle-positions-2-57b5654799-kn4gg`:

| Step | Time |
|---|---|
| Planned data from export | 74 465 ms |
| Planned-data snapshot uploaded | 117 875 434 bytes in 8 890 ms |
| NSR from export | 38 300 ms |
| NSR snapshot uploaded | 4 456 464 bytes in 647 ms |
| Started Application | 123.707 s |

Hit path, `vehicle-positions-2-57b5654799-jldfp`:

| Step | Time |
|---|---|
| Planned data from snapshot | 14 206 ms |
| NSR from snapshot | 5 174 ms |
| Started Application | 31.676 s |

Startup falls by a factor of four, but every stage is slower than the target. The
planned-data snapshot is 118 MB gzipped against the 30 to 50 MB estimated above, so a
large share of the 14.2 s is download and decompress rather than replay. Shrinking the
record encoding — the dated-service-journey ids dominate and repeat heavily — is the
obvious follow-up, and would move the hit path closer to the target. Not in scope here.

The miss pod started in 123.7 s against the roughly 112 s baseline measured before the
change. The two parses themselves are within their earlier ranges, so the difference
sits in the snapshot path — most plausibly the planned-data upload, which gzips about
250 MB on the background thread while the NSR parse runs. It is a single-digit-percent
cost paid by the first pod of a rollout only, and it was not isolated further.

## Deployment and rollout

1. Merge. Terraform creates all three buckets (`terraform.yml` applies dev on the PR, tst
   and prd on merge), and `snapshotUri` is set in dev only, so dev is the only environment
   whose behaviour changes. Elsewhere the property stays empty and the only runtime diffs
   are the `HttpClient` downloads and the extracted NSR parser.
2. Done 2026-09-02: the dev rollout's first pod missed on both datasets and uploaded both;
   the second hit both. See Measured.
3. Set `snapshotUri` in tst and prd. The buckets already exist
   (`ent-gcs-vpos-snapshots-tst-001`, `ent-gcs-vpos-snapshots-prd-001`), so each
   environment's next rollout pays one miss and then hits.

Rollback is clearing the property.

## Testing

Existing tests in `src/test/java/org/entur/vehicles/service/planned/` keep passing with
the sink interface introduced; `PlannedDataLoaderTest` passes a builder explicitly.
`NSRServiceAncestorTest` and `NSRServiceSpringWiringTest` are unchanged.

New tests:

- **Planned-data round trip.** Extract the GOA test zip into a `TeeSink` (builder plus
  writer); replay the written file into a fresh builder; assert both `build()` results
  have equal `Stats`, equal lookups for a fixed set of ids from every entity type, equal
  `pointsOnLink` output, and equal catalogue listings. This is the test that guarantees
  a hit is indistinguishable from a parse.
- **NSR round trip.** A small NSR-shaped NeTEx zip fixture (two stop places, one with a
  parent site, three quays, one quay without a name) parsed by `NsrNetexParser`, written
  and read back; assert equal `NsrData`, then `install` both into two services and
  assert equal `getStop` and `ancestorsOf` results.
- **Format guards**, for both readers. Wrong magic, wrong version, truncated file, and
  a record-count mismatch each throw.
- **Service behaviour with `FileSnapshotStore`**, for both services, using the
  fixture zip served through a tiny in-test HTTP server that sets an ETag (the `file:`
  scheme has no headers):
  - first load misses, produces a dataset, and leaves exactly one object in the
    directory named by dataset, version and ETag;
  - second load with the same ETag hits and produces an equal dataset, and the log or
    metric reports `source=snapshot`;
  - a corrupt object under the right key falls back to the parse, still produces the
    dataset, and replaces the object;
  - a changed ETag misses;
  - a store whose `open` throws still produces the dataset from the parse.
- **`FileSnapshotStore` semantics**: `putIfAbsent` returns false on the second write
  and leaves the first file intact; `put` replaces. `GcsSnapshotStore` is covered by
  the dev rollout, not by unit tests; it is a thin wrapper over one client call per
  method.
- **Measured section** appended to this spec after the dev rollout: hit-path times for
  both stages, snapshot object sizes and upload durations. Done 2026-09-02; see
  Measured. Ephemeral-storage peak was not captured — no pod was evicted and no
  disk-pressure event was raised during the rollout, so the 1Gi request holds, but the
  headroom against the 118 MB snapshot plus the export zip is unmeasured.
