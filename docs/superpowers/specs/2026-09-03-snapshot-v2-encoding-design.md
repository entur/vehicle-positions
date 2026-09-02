# Planned-data snapshot v2: a compact encoding

Date: 2026-09-03
Status: Implemented (2026-09-03) — measured results pending dev rollout
Extends: `2026-09-02-planned-data-snapshot-design.md`

## Goal

Cut the planned-data hit path below the 14.2 s measured in dev by storing the same data
better: replace repeated NeTEx id strings with dictionary indices, pack the id shapes NeTEx
codespaces actually produce, and delta-encode geometry.

**Scope note (2026-09-03).** This design first carried a second change: an operating-date
window that would have dropped dated service journeys beyond a configurable horizon,
cutting the retained map from 2.23M entries to about 200k. That is abandoned — the map
must answer for every future dated service journey the export carries, because the map
shows future situations. Nothing here filters records; the encoding is the whole change.

## What the measurements say

From the dev snapshot of export `78502bf4…` (2 227 779 dated service journeys, 470 MB
uncompressed, 118 MB gzipped):

| | Uncompressed | Gzipped |
|---|---:|---:|
| v1, today | 470 MB | 118 MB |
| **v2 encoding** | **105 MB** | **70 MB** |

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

The gzipped win is smaller than the raw one because gzip already exploited the repeated id
strings. What v2 buys beyond the 48 MB is decode cost: 105 MB to inflate instead of 470 MB,
and no `readUTF` for 2.8M strings whose prefixes are now read once.

## Snapshot format v2

v1 tees raw sink records through `TeeSink` as the extractor emits them. Reference indices
make that impossible: the extractor interleaves record types — 14 940 tag switches in the
dev file, with dated service journeys starting at record 397 while the first operating day
appears at 16 263 — so when a record is written, the record it references may not have
been seen.

So v2 writes the snapshot **after the parse, from the builder's completed state** — the
shape `NsrSnapshot` already uses. `TeeSink` goes away.

```
header    magic "VPP2", int version=2, UTF etag, long createdAt, int duplicateIds
prefixes  varint count, then each: varint length + UTF-8 bytes ("RUT:DatedServiceJourney:")
sections  in dependency order, each: varint count, then records
            1 operators             id, str name
            2 lines                 id, str name, str publicCode
            3 operatingDays         id, varint date (0 null, else zigzag(epochDay) + 1)
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
  — the ones `Stats.unresolved*Refs` counts — surviving a round trip.
- **str**: varint 0 for null, else varint length+1 and UTF-8 bytes.

Geometry deltas run per axis (value minus the value two positions back) because the array
is interleaved lat/lon; the two-back rule handles an odd-length array without a special
case.

`duplicateIds` rides in the header because the builder's maps have already collapsed
duplicates by the time we serialise, and `Stats.duplicateIds` must match what the parse
reported.

## Keys

Unchanged from v1: `dataset/v<FORMAT_VERSION>/<etag>.bin.gz`. One snapshot per export, read
by every pod of every day the export stands, exactly as today. Only the version in the path
moves, from 1 to 2, so no pod ever reads the other format's objects.

## Error handling and observability

Unchanged from the v1 spec: every failure on the snapshot path logs and falls back to the
parse. One change: a partial parse (`skipped > 0`) still never becomes a snapshot, but with
the write moved after the parse this is a plain early return rather than a discarded temp
file.

## Testing

- Codec round trips: varint and zigzag boundaries, every id kind, ids that match no kind,
  strings with non-ASCII and null.
- Section round trip through a builder: a fixture with dangling pattern, link, journey,
  line and operating-day references; empty and odd-length geometry; null names and public
  codes. Assert the rebuilt dataset equals the parsed one field by field, `Stats` included.
- Both paths agree: parse, snapshot, replay, assert identical `Stats` and identical lookups.
- Format guards from v1 still apply: bad magic, wrong version, truncation, count mismatch.

## Rollout

Merge and let it run. The format bump makes every pod miss once and re-upload in the new
encoding; from the second pod of that rollout on, hits read the smaller object. Nothing is
configurable and no behaviour changes — the dataset a pod ends up with is identical to
what it builds today.
