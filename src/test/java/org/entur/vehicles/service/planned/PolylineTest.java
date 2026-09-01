package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PolylineTest {

    /**
     * The worked example from Google's encoded polyline algorithm documentation:
     * (38.5, -120.2), (40.7, -120.95), (43.252, -126.453).
     */
    @Test
    public void encodesGoogleReferenceExample() {
        int[] points = {38_500_000, -120_200_000, 40_700_000, -120_950_000, 43_252_000, -126_453_000};

        assertThat(Polyline.encode(points)).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
    }

    @Test
    public void encodesEmptyAsEmptyString() {
        assertThat(Polyline.encode(new int[0])).isEmpty();
    }

    @Test
    public void roundsMicrodegreesToFiveDecimals() {
        // 59.722150 -> 59.72215 ; 59.722154 -> 59.72215 ; 59.722155 -> 59.72216
        assertThat(Polyline.encode(new int[]{59_722_150, 10_512_689}))
                .isEqualTo(Polyline.encode(new int[]{59_722_154, 10_512_689}));
        assertThat(Polyline.encode(new int[]{59_722_150, 10_512_689}))
                .isNotEqualTo(Polyline.encode(new int[]{59_722_155, 10_512_689}));
    }

    @Test
    public void stitchDropsTheSharedJoinPoint() {
        int[] a = {1, 1, 2, 2};
        int[] b = {2, 2, 3, 3};

        assertThat(Polyline.stitch(List.of(a, b))).containsExactly(1, 1, 2, 2, 3, 3);
    }

    @Test
    public void stitchKeepsBothPointsWhenLinksDoNotTouch() {
        int[] a = {1, 1, 2, 2};
        int[] b = {5, 5, 6, 6};

        assertThat(Polyline.stitch(List.of(a, b))).containsExactly(1, 1, 2, 2, 5, 5, 6, 6);
    }

    @Test
    public void stitchSkipsEmptyLinks() {
        int[] a = {1, 1, 2, 2};
        int[] gap = {};
        int[] b = {2, 2, 3, 3};

        assertThat(Polyline.stitch(List.of(a, gap, b))).containsExactly(1, 1, 2, 2, 3, 3);
        assertThat(Polyline.stitch(List.of(gap))).isEmpty();
        assertThat(Polyline.stitch(List.of())).isEmpty();
    }
}
