package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line -> journey pattern index exists so a situation tagged on a line can be given one
 * representative shape. Ordering is by vertex count descending (the most complete shape of the
 * line first), ties by id, so the representative a client sees is stable across a nightly reload
 * and changes only when the export's patterns change.
 */
class PlannedDatasetLinePatternsTest {

    /**
     * Two links: a six-point one and a four-point one, in different places. Two patterns on one
     * line, plus a pattern with no geometry at all - which can never yield a span and is therefore
     * excluded rather than wasting a slice attempt at query time. A third, declared line runs only
     * on that geometry-less pattern, so it is the case where every one of a line's patterns is
     * filtered out rather than merely some of them.
     */
    private static PlannedDataset dataset() {
        int[] sixPoints = new int[12];
        for (int i = 0; i < 6; i++) {
            sixPoints[i * 2] = 59_000_000 + i * 1_000;
            sixPoints[i * 2 + 1] = 10_000_000;
        }
        int[] fourPoints = new int[8];
        for (int i = 0; i < 4; i++) {
            fourPoints[i * 2] = 59_000_000 + i * 1_000;
            fourPoints[i * 2 + 1] = 11_000_000;
        }
        return new PlannedDataset.Builder()
                .addServiceLink("TST:ServiceLink:long", sixPoints)
                .addServiceLink("TST:ServiceLink:short", fourPoints)
                .addServiceLink("TST:ServiceLink:nogeom", null)
                .addJourneyPattern("TST:JourneyPattern:long", List.of("TST:ServiceLink:long"))
                .addJourneyPattern("TST:JourneyPattern:short", List.of("TST:ServiceLink:short"))
                .addJourneyPattern("TST:JourneyPattern:none", List.of("TST:ServiceLink:nogeom"))
                .addLine("TST:Line:1", "One", "1")
                .addLine("TST:Line:2", "Two", "2")
                .addLine("TST:Line:3", "Three", "3")
                .addServiceJourney("TST:ServiceJourney:1a", "TST:JourneyPattern:short", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:1b", "TST:JourneyPattern:long", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:1c", "TST:JourneyPattern:long", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:1d", "TST:JourneyPattern:none", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:2a", "TST:JourneyPattern:long", "TST:Line:2")
                .addServiceJourney("TST:ServiceJourney:3a", "TST:JourneyPattern:none", "TST:Line:3")
                .build();
    }

    @Test
    void ordersALinesDistinctPatternsByVertexCountDescending() {
        assertThat(dataset().journeyPatternsOf("TST:Line:1"))
                .containsExactly("TST:JourneyPattern:long", "TST:JourneyPattern:short");
    }

    @Test
    void aPatternServedByManyJourneysAppearsOnce() {
        // 1b and 1c share the long pattern; 2a puts it on a second line too.
        assertThat(dataset().journeyPatternsOf("TST:Line:2"))
                .containsExactly("TST:JourneyPattern:long");
    }

    @Test
    void patternsWithoutGeometryAreExcluded() {
        assertThat(dataset().journeyPatternsOf("TST:Line:1"))
                .doesNotContain("TST:JourneyPattern:none");
    }

    /** Ties must not depend on HashMap iteration order, or the representative would drift on reload. */
    @Test
    void equalVertexCountsAreOrderedById() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceLink("TST:ServiceLink:x", new int[]{59_000_000, 10_000_000, 59_001_000, 10_000_000})
                .addJourneyPattern("TST:JourneyPattern:b", List.of("TST:ServiceLink:x"))
                .addJourneyPattern("TST:JourneyPattern:a", List.of("TST:ServiceLink:x"))
                .addLine("TST:Line:ties", "Ties", "T")
                .addServiceJourney("TST:ServiceJourney:tb", "TST:JourneyPattern:b", "TST:Line:ties")
                .addServiceJourney("TST:ServiceJourney:ta", "TST:JourneyPattern:a", "TST:Line:ties")
                .build();

        assertThat(dataset.journeyPatternsOf("TST:Line:ties"))
                .containsExactly("TST:JourneyPattern:a", "TST:JourneyPattern:b");
    }

    @Test
    void anUnknownOrNullLineYieldsAnEmptyArrayRatherThanNull() {
        assertThat(dataset().journeyPatternsOf("TST:Line:unknown")).isEmpty();
        assertThat(dataset().journeyPatternsOf(null)).isEmpty();
    }

    /**
     * A declared line whose only journey runs on the geometry-less pattern still yields an empty
     * array, not null - the same answer as a line the planned data has never heard of, even though
     * this one is declared and has journeys.
     */
    @Test
    void aDeclaredLineWhoseJourneysAllLackPatternGeometryYieldsAnEmptyArray() {
        assertThat(dataset().journeyPatternsOf("TST:Line:3")).isEmpty();
    }

    /**
     * A journey whose LineRef the export never declares as a Line is already absent from
     * lineServiceJourneys; the pattern index is built from the same resolved pairing, so the two
     * cannot disagree about what a line contains.
     */
    @Test
    void journeysOnAnUndeclaredLineContributeNoPatterns() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceLink("TST:ServiceLink:y", new int[]{59_000_000, 10_000_000, 59_001_000, 10_000_000})
                .addJourneyPattern("TST:JourneyPattern:y", List.of("TST:ServiceLink:y"))
                .addServiceJourney("TST:ServiceJourney:y", "TST:JourneyPattern:y", "TST:Line:undeclared")
                .build();

        assertThat(dataset.journeyPatternsOf("TST:Line:undeclared")).isEmpty();
    }
}
