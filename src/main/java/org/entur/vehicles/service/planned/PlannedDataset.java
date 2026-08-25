package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.PointsOnLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ConcurrentHashMap<String, PointsOnLink> patternPolylines = new ConcurrentHashMap<>();
    private final Stats stats;

    private PlannedDataset(Map<String, Operator> operators,
                           Map<String, Line> lines,
                           Map<String, String> serviceJourneyPattern,
                           Map<String, DatedJourneyRef> datedServiceJourneys,
                           Map<String, String[]> patternLinks,
                           Map<String, int[]> linkGeometry,
                           Stats stats) {
        this.operators = operators;
        this.lines = lines;
        this.serviceJourneyPattern = serviceJourneyPattern;
        this.datedServiceJourneys = datedServiceJourneys;
        this.patternLinks = patternLinks;
        this.linkGeometry = linkGeometry;
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

    /** Marker for "computed, and there is nothing" - ConcurrentHashMap cannot store null. */
    private static final PointsOnLink NO_GEOMETRY = new PointsOnLink();

    /**
     * The encoded route geometry of a journey pattern, stitched from its service links on
     * first request and cached for the life of this snapshot. Null when the pattern is
     * unknown or none of its links carry geometry.
     */
    public PointsOnLink pointsOnLink(String journeyPatternId) {
        if (journeyPatternId == null || !patternLinks.containsKey(journeyPatternId)) {
            return null;
        }
        PointsOnLink result = patternPolylines.computeIfAbsent(journeyPatternId, this::buildPointsOnLink);
        return result == NO_GEOMETRY ? null : result;
    }

    private PointsOnLink buildPointsOnLink(String journeyPatternId) {
        String[] linkIds = patternLinks.get(journeyPatternId);
        List<int[]> geometries = new ArrayList<>(linkIds.length);
        for (String linkId : linkIds) {
            int[] geometry = linkGeometry.get(linkId);
            if (geometry != null && geometry.length > 0) {
                geometries.add(geometry);
            }
        }
        int[] stitched = Polyline.stitch(geometries);
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
                        int unresolvedOperatingDayRefs) {
    }

    /**
     * Collects raw refs from any number of files in any order and resolves them once in
     * {@link #build()}. Not thread-safe; one builder per load, driven by one thread.
     */
    public static final class Builder {

        private record RawDatedServiceJourney(String serviceJourneyId, String operatingDayId) {}

        private final Map<String, Operator> operators = new HashMap<>();
        private final Map<String, Line> lines = new HashMap<>();
        private final Map<String, String> serviceJourneyPattern = new HashMap<>();
        private final Map<String, RawDatedServiceJourney> rawDatedServiceJourneys = new HashMap<>();
        private final Map<String, String[]> patternLinks = new HashMap<>();
        private final Map<String, int[]> linkGeometry = new HashMap<>();
        private final Map<String, String> operatingDays = new HashMap<>();
        private int duplicateIds = 0;

        public Builder addOperator(String id, String name) {
            Operator operator = new Operator(id);
            operator.setName(name);
            countDuplicate(operators.put(id, operator));
            return this;
        }

        public Builder addLine(String id, String name, String publicCode) {
            Line line = new Line(id, name);
            line.setPublicCode(publicCode);
            countDuplicate(lines.put(id, line));
            return this;
        }

        /** @param geometry interleaved lat/lon microdegrees; null when the link has no gis:posList */
        public Builder addServiceLink(String id, int[] geometry) {
            countDuplicate(linkGeometry.put(id, geometry == null ? new int[0] : geometry));
            return this;
        }

        public Builder addJourneyPattern(String id, List<String> serviceLinkIds) {
            countDuplicate(patternLinks.put(id, serviceLinkIds.toArray(new String[0])));
            return this;
        }

        public Builder addServiceJourney(String id, String journeyPatternId) {
            // Map.copyOf in build() rejects null values; "" is never a real pattern id, so it
            // still counts as unresolved there.
            countDuplicate(serviceJourneyPattern.put(id, journeyPatternId == null ? "" : journeyPatternId));
            return this;
        }

        public Builder addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId) {
            countDuplicate(rawDatedServiceJourneys.put(id, new RawDatedServiceJourney(serviceJourneyId, operatingDayId)));
            return this;
        }

        public Builder addOperatingDay(String id, String calendarDate) {
            countDuplicate(operatingDays.put(id, calendarDate));
            return this;
        }

        private void countDuplicate(Object previous) {
            if (previous != null) {
                duplicateIds++;
            }
        }

        public PlannedDataset build() {
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
                if (raw.serviceJourneyId() == null || !serviceJourneyPattern.containsKey(raw.serviceJourneyId())) {
                    unresolvedServiceJourneyRefs++;
                }
                String date = raw.operatingDayId() == null ? null : operatingDays.get(raw.operatingDayId());
                if (date == null) {
                    unresolvedOperatingDayRefs++;
                }
                datedServiceJourneys.put(e.getKey(), new DatedJourneyRef(raw.serviceJourneyId(), date));
            }

            Stats stats = new Stats(
                    operators.size(), lines.size(), serviceJourneyPattern.size(), datedServiceJourneys.size(),
                    patternLinks.size(), linkGeometry.size(), duplicateIds,
                    unresolvedPatternRefs, unresolvedLinkRefs, unresolvedServiceJourneyRefs, unresolvedOperatingDayRefs);

            if (duplicateIds + unresolvedPatternRefs + unresolvedLinkRefs
                    + unresolvedServiceJourneyRefs + unresolvedOperatingDayRefs > 0) {
                LOG.info("Planned data build summary: {}", stats);
            }

            return new PlannedDataset(
                    Map.copyOf(operators),
                    Map.copyOf(lines),
                    Map.copyOf(serviceJourneyPattern),
                    Map.copyOf(datedServiceJourneys),
                    Map.copyOf(patternLinks),
                    Map.copyOf(linkGeometry),
                    stats);
        }
    }
}
