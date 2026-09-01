package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.PointsOnLink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PlannedDatasetGeometryTest {

    private static PlannedDataset dataset() {
        return new PlannedDataset.Builder()
                .addServiceLink("L:1", new int[]{38_500_000, -120_200_000, 40_700_000, -120_950_000})
                .addServiceLink("L:2", new int[]{40_700_000, -120_950_000, 43_252_000, -126_453_000})
                .addServiceLink("L:nogeom", null)
                .addJourneyPattern("JP:full", List.of("L:1", "L:2"))
                .addJourneyPattern("JP:gap", List.of("L:1", "L:nogeom", "L:2"))
                .addJourneyPattern("JP:dangling", List.of("L:1", "L:missing"))
                .addJourneyPattern("JP:none", List.of("L:nogeom"))
                .build();
    }

    @Test
    public void stitchesLinksInPatternOrderAndEncodes() {
        PointsOnLink points = dataset().pointsOnLink("JP:full");

        assertThat(points.getPoints()).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        assertThat(points.getLength()).isEqualTo(3);
    }

    @Test
    public void linksWithoutGeometryLeaveAGapButDoNotBreakThePattern() {
        assertThat(dataset().pointsOnLink("JP:gap").getLength()).isEqualTo(3);
    }

    @Test
    public void danglingLinkRefsAreSkipped() {
        assertThat(dataset().pointsOnLink("JP:dangling").getLength()).isEqualTo(2);
    }

    @Test
    public void patternWithNoGeometryAtAllYieldsNull() {
        assertThat(dataset().pointsOnLink("JP:none")).isNull();
        assertThat(dataset().pointsOnLink("JP:unknown")).isNull();
        assertThat(dataset().pointsOnLink(null)).isNull();
    }

    @Test
    public void resultIsCachedPerPattern() {
        PlannedDataset dataset = dataset();

        assertThat(dataset.pointsOnLink("JP:full")).isSameAs(dataset.pointsOnLink("JP:full"));
    }
}
