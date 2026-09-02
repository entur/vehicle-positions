# Planned-data snapshot v2: an operating-date window and a compact encoding

Date: 2026-09-03
Status: Design
Extends: `2026-09-02-planned-data-snapshot-design.md`

## Goal

Cut the planned-data hit path below the 14.2 s measured in dev, and cut the heap the
dataset holds, by storing less and storing it better. Two changes, one format bump:

1. **An operating-date window.** Keep only dated service journeys whose operating date is
   at most N days ahead. Configurable, unlimited by default.
2. **A compact encoding.** Replace repeated NeTEx id strings with dictionary indices, pack
   the id shapes NeTEx codespaces actually produce, and delta-encode geometry.

## What the measurements say

From the dev snapshot of export `78502bf4…` (2 227 779 dated service journeys, 470 MB
uncompressed, 118 MB gzipped):

| | DSJs kept | Uncompressed | Gzipped |
|---|---:|---:|---:|
| today (v1, no window) | 2 227 779 | 470 MB | 118 MB |
| v1 + 7-day window | 201 378 | 175 MB | 63.5 MB |
| v2 encoding, no window | 2 227 779 | 105 MB | 70 MB |
| **v2 + 7-day window** | **201 378** | **53 MB** | **~33 MB** |

Where the bytes are today: dated service journeys 68.9% (136.7 MB of own ids, 184.7 MB of
references), service links 17.6% (76.4 MB of it geometry), service journeys 7.5%, journey
patterns 5.9% (26.5 MB of it link references).

Two facts about the data drive the encoding:

- **References repeat.** 2.2M dated service journeys reference 93 609 distinct service
  journeys and 2 549 distinct operating days; 627 537 journey-pattern link references
  point at 137 044 distinct links.
- **Ids are structured.** Every id is `CODESPACE:Type:local`, and all 2.78M ids in the
  export share 334 distinct `CODESPACE:Type:` prefixes. 2 005 942 dated-service-journey
  locals are `djj-` plus 32 hex characters — a 128-bit value written as 36 characters.
  75 559 service-journey locals are bare hex32 and 7 175 are UUIDs. Service-link geometry
  is 18.96M microdegree ints whose consecutive values differ by tens to hundreds.

## The window

`vehicle.planned.data.dsj.future-days`: an integer horizon, empty or absent meaning
unlimited, which is today's behaviour.

A dated service journey survives when its resolved operating date is at most
`today + futureDays`. Past-dated journeys are kept — the export prunes those itself, and
the dev export still carries 48 278 of them, 2.2%, so dropping them here would be a second
policy in a second place. A journey whose operating day never resolved is kept: it cannot
be dated, and dropping it would silently change lookups when the export has a data error.

`today` is `LocalDate.now(Europe/Oslo)`, from an injected supplier so tests are
deterministic, and in the zone the nightly reload cron already uses.

The filter lives in `PlannedDataset.Builder`, applied before `build()`, so the parse path
and the snapshot-replay path drop exactly the same journeys.

**Behaviour change.** `PlannedDataset.serviceJourney(id)` falls back to the dated map, so a
reference to a journey beyond the horizon — from a SIRI vehicle update or a GraphQL
`datedServiceJourney` / `serviceJourneys` query — resolves to null, as if the export did
not know it. With the property unset nothing changes.

## Snapshot format v2

v1 tees raw sink records through `TeeSink` as the extractor emits them. Two things make
that impossible now: reference indices need the referenced record to be known, and the
extractor interleaves record types (14 940 tag switches in the dev file, with dated service
journeys starting at record 397 while the first operating day appears at 16 263). The
window has the same problem: an operating day's date is not known when its journeys are
read.

So v2 writes the snapshot **after the parse, from the builder's completed state** — the
shape `NsrSnapshot` already uses. `TeeSink` goes away.

```
header    magic "VPP2", int version=2, UTF etag, long createdAt,
          int futureDays (-1 unlimited), long asOfEpochDay (-1 unlimited),
          int duplicateIds
prefixes  varint count, then each: varint length + UTF-8 bytes ("RUT:DatedServiceJourney:")
sections  in dependency order, each: varint count, then records
            1 operators             id, str name
            2 lines                 id, str name, str publicCode
            3 operatingDays         id, varint date (0 null, else zigzag epochDay + 1)
            4 serviceLinks          id, varint intCount, then zigzag varint per int,
                                    each delta against the value two positions back
            5 journeyPatterns       id, varint linkCount, then a ref per link
            6 serviceJourneys       id, ref journeyPattern, ref line
            7 datedServiceJourneys  id, ref serviceJourney, ref operatingDay
trailer   byte 0xFF, varint total record count
```

- **id**: varint prefix index, then a kind byte — 0 raw (varint length + bytes), 1 `djj-`
  hex32 (16 bytes), 2 hex32 (16 bytes), 3 UUID (16 bytes), 4 digits (varint).
- **ref**: varint. 0 is null. 1..N is an index into the named section. N+1 introduces a
  literal id, written like any other id. The literal case is what keeps dangling references
  — the ones `Stats.unresolved*Refs` counts — surviving a round trip unchanged.
- **str**: varint 0 for null, else varint length+1 and UTF-8 bytes.

Geometry deltas run per axis (value minus the value two positions back) because the array
is interleaved lat/lon; the two-back rule handles an odd-length array without a special
case.

`duplicateIds` rides in the header because the builder's maps have already collapsed
duplicates by the time we serialise, and `Stats.duplicateIds` must match what the parse
reported.

## Keys

`SnapshotKey` gains a variant: `dataset/vN/<etag>.bin.gz` when it is empty, and
`dataset/vN/<etag>_<variant>.bin.gz` otherwise. Planned data sets the variant to
`f<futureDays>_<asOfDate>` when a window is configured, and leaves it empty when it is not.
NSR keys are unchanged.

Consequence: with a window configured, the first pod of each day misses and re-parses; the
rest of that day's pods hit. The bucket's 7-day delete lifecycle already reaps the daily
objects. Without a window the key is exactly what it is today.

The reader also validates the header's `futureDays` and `asOfEpochDay` against the running
configuration and today's date, and treats a mismatch as a corrupt snapshot: a miss, then
a parse. The key already separates them; this is the second line.

## Error handling and observability

Unchanged from the v1 spec: every failure on the snapshot path logs and falls back to the
parse. Two additions:

- `Stats` gains `datedServiceJourneysDropped`, logged with the rest of the load line.
- A partial parse (`skipped > 0`) still never becomes a snapshot; with the write moved
  after the parse this is a plain early return rather than a discarded temp file.

## Testing

- Codec round trips: varint and zigzag boundaries, every id kind, ids that match no kind,
  strings with non-ASCII and null.
- Section round trip through a builder: a fixture with dangling pattern, link, journey,
  line and operating-day references; empty and odd-length geometry; null names and public
  codes. Assert the rebuilt dataset equals the parsed one field by field, `Stats` included.
- Window: a journey exactly on the horizon is kept and one a day past it is dropped;
  past-dated and undated journeys are kept; the dropped count is reported.
- Both paths agree: parse with a window, snapshot, replay, assert identical `Stats` and
  identical lookups.
- Header validation: a snapshot written for another horizon or another day is refused.
- Format guards from v1 still apply: bad magic, wrong version, truncation, count mismatch.

## Rollout

Merge with `future-days` unset everywhere: the only behaviour change is the format bump,
which makes every pod miss once and re-upload in the new encoding. Then set dev to 7 days,
measure the hit path and the heap, and decide tst and prd from there.
