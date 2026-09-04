package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.ServiceJourney;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One immutable snapshot of the planned data this service needs from the NeTEx export.
 * Built once per load by {@link Builder}; replaced wholesale on reload, never mutated.
 * <p>
 * The only mutable member is the per-pattern polyline cache, which is filled lazily on
 * first request and dies with the snapshot.
 */
public final class PlannedDataset {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataset.class);

    public static final PlannedDataset EMPTY = new Builder().build();

    private final Map<String, Operator> operators;
    private final Map<String, Line> lines;
    private final Map<String, String> serviceJourneyPattern;
    private final Map<String, DatedJourneyRef> datedServiceJourneys;
    private final Map<String, String[]> patternLinks;
    private final Map<String, int[]> linkGeometry;
    private final Map<String, String> serviceJourneyLine;
    /** Line id -> service journey ids on it, sorted. Only lines the export declares. */
    private final Map<String, String[]> lineServiceJourneys;
    /** Line id -> the distinct journey patterns of its journeys, most vertices first. */
    private final Map<String, String[]> lineJourneyPatterns;
    private final List<Codespace> codespaces;
    private final ConcurrentHashMap<String, PointsOnLink> patternPolylines = new ConcurrentHashMap<>();
    private final Stats stats;

    private PlannedDataset(Map<String, Operator> operators,
                           Map<String, Line> lines,
                           Map<String, String> serviceJourneyPattern,
                           Map<String, DatedJourneyRef> datedServiceJourneys,
                           Map<String, String[]> patternLinks,
                           Map<String, int[]> linkGeometry,
                           Map<String, String> serviceJourneyLine,
                           Map<String, String[]> lineServiceJourneys,
                           Map<String, String[]> lineJourneyPatterns,
                           List<Codespace> codespaces,
                           Stats stats) {
        this.operators = operators;
        this.lines = lines;
        this.serviceJourneyPattern = serviceJourneyPattern;
        this.datedServiceJourneys = datedServiceJourneys;
        this.patternLinks = patternLinks;
        this.linkGeometry = linkGeometry;
        this.serviceJourneyLine = serviceJourneyLine;
        this.lineServiceJourneys = lineServiceJourneys;
        this.lineJourneyPatterns = lineJourneyPatterns;
        this.codespaces = codespaces;
        this.stats = stats;
    }

    public Operator operator(String id) {
        return id == null ? null : operators.get(id);
    }

    public Line line(String id) {
        return id == null ? null : lines.get(id);
    }

    public boolean hasServiceJourney(String id) {
        return id != null && serviceJourneyPattern.containsKey(id);
    }

    /** The journey pattern id of a service journey, or null if unknown or unresolved. */
    public String journeyPatternOf(String serviceJourneyId) {
        return serviceJourneyId == null ? null : serviceJourneyPattern.get(serviceJourneyId);
    }

    public DatedJourneyRef datedServiceJourney(String id) {
        return id == null ? null : datedServiceJourneys.get(id);
    }

    public int serviceJourneyCount() {
        return serviceJourneyPattern.size();
    }

    public Stats stats() {
        return stats;
    }

    /** The line id a service journey runs on, or null if unknown. */
    public String lineOf(String serviceJourneyId) {
        return serviceJourneyId == null ? null : serviceJourneyLine.get(serviceJourneyId);
    }

    /** Shared with every caller and never copied - callers must not mutate it. */
    private static final String[] NO_PATTERNS = new String[0];

    /**
     * The line's distinct journey patterns, most vertices first and ties by id, so the pattern a
     * line-level situation is drawn on is stable across reloads. Patterns without usable geometry
     * are already excluded, so a caller can slice each in turn without re-checking. Empty, never
     * null, for a line the export does not declare.
     * <p>
     * The returned array is shared; callers must treat it as read-only.
     */
    public String[] journeyPatternsOf(String lineId) {
        if (lineId == null) {
            return NO_PATTERNS;
        }
        String[] patterns = lineJourneyPatterns.get(lineId);
        return patterns != null ? patterns : NO_PATTERNS;
    }

    // ---- Catalogue: what the export declares, independent of live vehicles. Filters keep
    // ---- the regex semantics of the old vehicle-backed resolvers (ObjectRef.matches);
    // ---- results are sorted by ref.

    /** All lines, or those whose codespace matches the given codespace pattern. */
    public List<Line> lines(String codespaceId) {
        List<Line> result = new ArrayList<>();
        for (Line line : lines.values()) {
            if (codespaceId == null || codespaceOf(line.getLineRef()).matches(codespaceId)) {
                result.add(line);
            }
        }
        result.sort(Comparator.comparing(Line::getLineRef));
        return result;
    }

    /** All operators, or those whose codespace matches the given codespace pattern. */
    public List<Operator> operators(String codespaceId) {
        List<Operator> result = new ArrayList<>();
        for (Operator operator : operators.values()) {
            if (codespaceId == null || codespaceOf(operator.getOperatorRef()).matches(codespaceId)) {
                result.add(operator);
            }
        }
        result.sort(Comparator.comparing(Operator::getOperatorRef));
        return result;
    }

    /** Every codespace that declares a line or an operator, sorted. */
    public List<Codespace> codespaces() {
        return codespaces;
    }

    /**
     * Ids of the service journeys on lines matching {@code lineRef} and/or whose codespace
     * matches {@code codespaceId}; both null yields every journey on a declared line.
     * Journeys whose line the export does not declare are not part of the catalogue.
     */
    public List<String> serviceJourneyIds(String lineRef, String codespaceId) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String[]> e : lineServiceJourneys.entrySet()) {
            String lineId = e.getKey();
            if ((lineRef == null || lineId.matches(lineRef))
                    && (codespaceId == null || codespaceOf(lineId).matches(codespaceId))) {
                result.addAll(Arrays.asList(e.getValue()));
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    /**
     * A light ServiceJourney (no geometry - resolve pointsOnLink lazily) for a service journey
     * id, or for a dated service journey id (then dated to its operating day). Null if the
     * export knows neither.
     */
    public ServiceJourney serviceJourney(String id) {
        if (id == null) {
            return null;
        }
        if (serviceJourneyPattern.containsKey(id)) {
            return new ServiceJourney(id);
        }
        DatedJourneyRef dated = datedServiceJourneys.get(id);
        if (dated != null && dated.serviceJourneyId() != null) {
            return new ServiceJourney(dated.serviceJourneyId(), dated.operatingDate());
        }
        return null;
    }

    /**
     * The light ServiceJourneys for the given service journey ids or dated service journey ids
     * (see {@link #serviceJourney(String)}), in request order, duplicates collapsed and unknown
     * ids skipped. A non-null {@code lineRef} / {@code codespaceId} (regex) keeps only journeys on
     * a matching line - a journey without a known line matches neither.
     */
    public List<ServiceJourney> serviceJourneys(Collection<String> ids, String lineRef, String codespaceId) {
        List<ServiceJourney> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            ServiceJourney serviceJourney = serviceJourney(id);
            if (serviceJourney == null || !seen.add(serviceJourney.getId())) {
                continue;
            }
            if (lineRef != null || codespaceId != null) {
                String lineId = lineOf(serviceJourney.getId());
                if (lineId == null
                        || (lineRef != null && !lineId.matches(lineRef))
                        || (codespaceId != null && !codespaceOf(lineId).matches(codespaceId))) {
                    continue;
                }
            }
            result.add(serviceJourney);
        }
        return result;
    }

    /**
     * The DatedServiceJourneys for the given dated service journey ids, each with its operating
     * day and a light ServiceJourney dated to it (geometry resolves lazily). Request order is
     * kept, duplicates collapse, ids the export does not know (including plain service journey
     * ids) are skipped.
     */
    public List<DatedServiceJourney> datedServiceJourneys(Collection<String> ids) {
        List<DatedServiceJourney> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            DatedJourneyRef ref = datedServiceJourney(id);
            if (ref == null || ref.serviceJourneyId() == null || !seen.add(id)) {
                continue;
            }
            DatedServiceJourney dated = new DatedServiceJourney(id, new ServiceJourney(ref.serviceJourneyId(), ref.operatingDate()));
            dated.setOperatingDay(ref.operatingDate());
            result.add(dated);
        }
        return result;
    }

    /** The codespace prefix of a NeTEx id ("RUT:Line:1" -> "RUT"); the whole id if it has no colon. */
    static String codespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    /** Marker for "computed, and there is nothing" - ConcurrentHashMap cannot store null. */
    private static final PointsOnLink NO_GEOMETRY = new PointsOnLink();

    /**
     * The encoded route geometry of a journey pattern, stitched from its service links on
     * first request and cached for the life of this snapshot. Null when the pattern is
     * unknown or none of its links carry geometry.
     * <p>
     * The returned instance is shared by every ServiceJourney on this pattern and by the
     * cache; callers must treat it as read-only.
     */
    public PointsOnLink pointsOnLink(String journeyPatternId) {
        if (journeyPatternId == null || !patternLinks.containsKey(journeyPatternId)) {
            return null;
        }
        PointsOnLink result = patternPolylines.computeIfAbsent(journeyPatternId, this::buildPointsOnLink);
        return result == NO_GEOMETRY ? null : result;
    }

    /**
     * The route geometry of a journey pattern as interleaved lat/lon microdegrees, stitched
     * from its service links. Empty when the pattern is unknown or none of its links carry
     * geometry; never null.
     * <p>
     * Deliberately not cached: this is built per request on the situation path, which is rare
     * next to ingestion, and caching the arrays alongside the encoded strings in
     * {@code patternPolylines} would double that memory for no steady-state gain.
     */
    public int[] stitchedGeometry(String journeyPatternId) {
        if (journeyPatternId == null) {
            return new int[0];
        }
        String[] linkIds = patternLinks.get(journeyPatternId);
        if (linkIds == null) {
            return new int[0];
        }
        List<int[]> geometries = new ArrayList<>(linkIds.length);
        for (String linkId : linkIds) {
            int[] geometry = linkGeometry.get(linkId);
            if (geometry != null && geometry.length > 0) {
                geometries.add(geometry);
            }
        }
        return Polyline.stitch(geometries);
    }

    private PointsOnLink buildPointsOnLink(String journeyPatternId) {
        int[] stitched = stitchedGeometry(journeyPatternId);
        if (stitched.length == 0) {
            return NO_GEOMETRY;
        }
        PointsOnLink pointsOnLink = new PointsOnLink();
        pointsOnLink.setLength(stitched.length / 2);
        pointsOnLink.setPoints(Polyline.encode(stitched));
        return pointsOnLink;
    }

    /**
     * Counts from one load. The unresolved counters are the summary of dangling refs found
     * while building - they are logged, never thrown.
     */
    public record Stats(int operators,
                        int lines,
                        int serviceJourneys,
                        int datedServiceJourneys,
                        int journeyPatterns,
                        int serviceLinks,
                        int duplicateIds,
                        int unresolvedPatternRefs,
                        int unresolvedLinkRefs,
                        int unresolvedServiceJourneyRefs,
                        int unresolvedOperatingDayRefs,
                        int unresolvedLineRefs) {
    }

    /**
     * Collects raw refs from any number of files in any order and resolves them once in
     * {@link #build()}. Not thread-safe; one builder per load, driven by one thread.
     */
    public static final class Builder implements PlannedDataSink {

        record RawDatedServiceJourney(String serviceJourneyId, String operatingDayId) {}

        private final Map<String, Operator> operators = new HashMap<>();
        private final Map<String, Line> lines = new HashMap<>();
        private final Map<String, String> serviceJourneyPattern = new HashMap<>();
        private final Map<String, RawDatedServiceJourney> rawDatedServiceJourneys = new HashMap<>();
        private final Map<String, String[]> patternLinks = new HashMap<>();
        private final Map<String, int[]> linkGeometry = new HashMap<>();
        private final Map<String, String> operatingDays = new HashMap<>();
        private final Map<String, String> serviceJourneyLine = new HashMap<>();
        private int duplicateIds = 0;

        @Override
        public Builder addOperator(String id, String name) {
            Operator operator = new Operator(id);
            operator.setName(name);
            countDuplicate(operators.put(id, operator));
            return this;
        }

        @Override
        public Builder addLine(String id, String name, String publicCode) {
            Line line = new Line(id, name);
            line.setPublicCode(publicCode);
            countDuplicate(lines.put(id, line));
            return this;
        }

        /** @param geometry interleaved lat/lon microdegrees; null when the link has no gis:posList */
        @Override
        public Builder addServiceLink(String id, int[] geometry) {
            countDuplicate(linkGeometry.put(id, geometry == null ? new int[0] : geometry));
            return this;
        }

        @Override
        public Builder addJourneyPattern(String id, List<String> serviceLinkIds) {
            countDuplicate(patternLinks.put(id, serviceLinkIds.toArray(new String[0])));
            return this;
        }

        public Builder addServiceJourney(String id, String journeyPatternId) {
            return addServiceJourney(id, journeyPatternId, null);
        }

        /** @param lineId the journey's LineRef/FlexibleLineRef; null when the element has none */
        @Override
        public Builder addServiceJourney(String id, String journeyPatternId, String lineId) {
            // Map.copyOf in build() rejects null values; "" is never a real pattern id, so it
            // still counts as unresolved there.
            countDuplicate(serviceJourneyPattern.put(id, journeyPatternId == null ? "" : journeyPatternId));
            if (lineId != null) {
                serviceJourneyLine.put(id, lineId);
            } else {
                serviceJourneyLine.remove(id);
            }
            return this;
        }

        @Override
        public Builder addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId) {
            countDuplicate(rawDatedServiceJourneys.put(id, new RawDatedServiceJourney(serviceJourneyId, operatingDayId)));
            return this;
        }

        @Override
        public Builder addOperatingDay(String id, String calendarDate) {
            countDuplicate(operatingDays.put(id, calendarDate));
            return this;
        }

        private void countDuplicate(Object previous) {
            if (previous != null) {
                duplicateIds++;
            }
        }

        /**
         * Sets the duplicate-id count reported in {@link Stats} without recounting.
         * A snapshot replay hands the builder maps that have already collapsed duplicates,
         * so {@link Stats#duplicateIds()} must be seeded from the original parse instead of
         * being (re)derived from {@code countDuplicate}.
         */
        @Override
        public void seedDuplicateIds(int duplicateIds) {
            this.duplicateIds = duplicateIds;
        }

        // ---- Package-private, unmodifiable views of the builder's collected state, for the
        // ---- snapshot writer. Views, not copies: these maps can hold millions of entries and
        // ---- a defensive copy would double the peak heap during a snapshot write.

        Map<String, Operator> operators() {
            return Collections.unmodifiableMap(operators);
        }

        Map<String, Line> lines() {
            return Collections.unmodifiableMap(lines);
        }

        Map<String, String> operatingDays() {
            return Collections.unmodifiableMap(operatingDays);
        }

        Map<String, int[]> linkGeometry() {
            return Collections.unmodifiableMap(linkGeometry);
        }

        Map<String, String[]> patternLinks() {
            return Collections.unmodifiableMap(patternLinks);
        }

        Map<String, String> serviceJourneyPattern() {
            return Collections.unmodifiableMap(serviceJourneyPattern);
        }

        Map<String, String> serviceJourneyLine() {
            return Collections.unmodifiableMap(serviceJourneyLine);
        }

        Map<String, RawDatedServiceJourney> rawDatedServiceJourneys() {
            return Collections.unmodifiableMap(rawDatedServiceJourneys);
        }

        /** The duplicate-id count collected so far, mirroring {@link Stats#duplicateIds()} without requiring a full {@link #build()}. */
        int duplicateIds() {
            return duplicateIds;
        }

        public PlannedDataset build() {
            // Canonicalise duplicate id Strings: every ref string comes fresh from the XML
            // reader, so the same logical id declared once (e.g. a JourneyPattern) but
            // referenced many times (e.g. from every ServiceJourney on it) is otherwise a
            // distinct String instance per occurrence. Map each declared id to itself, then
            // swap every referencing value through that map so all occurrences of one id
            // share a single String instance.
            Map<String, String> canonSj = new HashMap<>(serviceJourneyPattern.size());
            for (String id : serviceJourneyPattern.keySet()) {
                canonSj.put(id, id);
            }
            Map<String, String> canonPattern = new HashMap<>(patternLinks.size());
            for (String id : patternLinks.keySet()) {
                canonPattern.put(id, id);
            }
            Map<String, String> canonLink = new HashMap<>(linkGeometry.size());
            for (String id : linkGeometry.keySet()) {
                canonLink.put(id, id);
            }

            serviceJourneyPattern.replaceAll((sj, patternId) -> canonPattern.getOrDefault(patternId, patternId));
            patternLinks.replaceAll((patternId, links) -> {
                String[] canonicalised = new String[links.length];
                for (int i = 0; i < links.length; i++) {
                    canonicalised[i] = canonLink.getOrDefault(links[i], links[i]);
                }
                return canonicalised;
            });

            Map<String, String> canonLine = new HashMap<>(lines.size());
            for (String id : lines.keySet()) {
                canonLine.put(id, id);
            }
            int unresolvedLineRefs = 0;
            Map<String, List<String>> journeysByLine = new HashMap<>();
            for (Map.Entry<String, String> e : serviceJourneyLine.entrySet()) {
                String lineId = canonLine.get(e.getValue());
                if (lineId == null) {
                    unresolvedLineRefs++;
                    continue;
                }
                e.setValue(lineId);
                journeysByLine.computeIfAbsent(lineId, k -> new ArrayList<>()).add(e.getKey());
            }
            Map<String, String[]> lineServiceJourneys = new HashMap<>(journeysByLine.size());
            for (Map.Entry<String, List<String>> e : journeysByLine.entrySet()) {
                String[] ids = e.getValue().toArray(new String[0]);
                Arrays.sort(ids);
                lineServiceJourneys.put(e.getKey(), ids);
            }

            // Vertex count per pattern, summed from its links rather than stitched: ordering only
            // needs the size of the shape, and stitching every pattern at build time would cost
            // an array copy per pattern for a number we can add up.
            Map<String, Integer> patternVertices = new HashMap<>(patternLinks.size());
            for (Map.Entry<String, String[]> e : patternLinks.entrySet()) {
                int vertices = 0;
                for (String linkId : e.getValue()) {
                    int[] geometry = linkGeometry.get(linkId);
                    if (geometry != null) {
                        vertices += geometry.length / 2;
                    }
                }
                patternVertices.put(e.getKey(), vertices);
            }

            // A line's shape is the shape of its journeys' patterns. Ordered by vertex count
            // descending so the most complete variant is the representative, ties by id so a
            // reload does not silently move the representative around.
            Comparator<String> byShapeThenId = Comparator
                    .comparingInt((String patternId) -> patternVertices.getOrDefault(patternId, 0))
                    .reversed()
                    .thenComparing(Comparator.naturalOrder());
            Map<String, String[]> lineJourneyPatterns = new HashMap<>(journeysByLine.size());
            int linesWithoutGeometry = 0;
            for (Map.Entry<String, List<String>> e : journeysByLine.entrySet()) {
                TreeSet<String> patterns = new TreeSet<>(byShapeThenId);
                for (String serviceJourneyId : e.getValue()) {
                    String patternId = serviceJourneyPattern.get(serviceJourneyId);
                    // "" is the builder's marker for a journey whose JourneyPatternRef was absent;
                    // a zero vertex count is a pattern that can never yield a span.
                    if (patternId != null && !patternId.isEmpty()
                            && patternVertices.getOrDefault(patternId, 0) > 0) {
                        patterns.add(patternId);
                    }
                }
                if (patterns.isEmpty()) {
                    linesWithoutGeometry++;
                } else {
                    lineJourneyPatterns.put(e.getKey(), patterns.toArray(new String[0]));
                }
            }
            if (linesWithoutGeometry > 0) {
                LOG.info("Planned data: {} of {} lines with journeys have no journey pattern with "
                        + "geometry - line-level situations on them resolve no polyline.",
                        linesWithoutGeometry, journeysByLine.size());
            }

            TreeSet<String> codespaceIds = new TreeSet<>();
            for (String id : lines.keySet()) {
                codespaceIds.add(codespaceOf(id));
            }
            for (String id : operators.keySet()) {
                codespaceIds.add(codespaceOf(id));
            }
            List<Codespace> codespaces = new ArrayList<>(codespaceIds.size());
            for (String id : codespaceIds) {
                codespaces.add(Codespace.getCodespace(id));
            }

            int unresolvedPatternRefs = 0;
            int unresolvedLinkRefs = 0;
            int unresolvedServiceJourneyRefs = 0;
            int unresolvedOperatingDayRefs = 0;

            // SJ -> pattern: keep the SJ (it is a known id) but count a dangling pattern ref.
            // The entry is kept as-is so journeyPatternOf still returns the (unresolvable) id;
            // pointsOnLink handles an unknown pattern by returning null.
            for (String patternId : serviceJourneyPattern.values()) {
                if (patternId == null || !patternLinks.containsKey(patternId)) {
                    unresolvedPatternRefs++;
                }
            }

            for (String[] links : patternLinks.values()) {
                for (String linkId : links) {
                    if (!linkGeometry.containsKey(linkId)) {
                        unresolvedLinkRefs++;
                    }
                }
            }

            Map<String, DatedJourneyRef> datedServiceJourneys = new HashMap<>(rawDatedServiceJourneys.size());
            for (Map.Entry<String, RawDatedServiceJourney> e : rawDatedServiceJourneys.entrySet()) {
                RawDatedServiceJourney raw = e.getValue();
                String serviceJourneyId = canonSj.getOrDefault(raw.serviceJourneyId(), raw.serviceJourneyId());
                if (serviceJourneyId == null || !serviceJourneyPattern.containsKey(serviceJourneyId)) {
                    unresolvedServiceJourneyRefs++;
                }
                String date = raw.operatingDayId() == null ? null : operatingDays.get(raw.operatingDayId());
                if (date == null) {
                    unresolvedOperatingDayRefs++;
                }
                datedServiceJourneys.put(e.getKey(), new DatedJourneyRef(serviceJourneyId, date));
            }

            Stats stats = new Stats(
                    operators.size(), lines.size(), serviceJourneyPattern.size(), datedServiceJourneys.size(),
                    patternLinks.size(), linkGeometry.size(), duplicateIds,
                    unresolvedPatternRefs, unresolvedLinkRefs, unresolvedServiceJourneyRefs, unresolvedOperatingDayRefs,
                    unresolvedLineRefs);

            if (duplicateIds + unresolvedPatternRefs + unresolvedLinkRefs
                    + unresolvedServiceJourneyRefs + unresolvedOperatingDayRefs + unresolvedLineRefs > 0) {
                LOG.info("Planned data build summary: {}", stats);
            }

            return new PlannedDataset(
                    Map.copyOf(operators),
                    Map.copyOf(lines),
                    Map.copyOf(serviceJourneyPattern),
                    Map.copyOf(datedServiceJourneys),
                    Map.copyOf(patternLinks),
                    Map.copyOf(linkGeometry),
                    Map.copyOf(serviceJourneyLine),
                    Map.copyOf(lineServiceJourneys),
                    Map.copyOf(lineJourneyPatterns),
                    List.copyOf(codespaces),
                    stats);
        }
    }
}
