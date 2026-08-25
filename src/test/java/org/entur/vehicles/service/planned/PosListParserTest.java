package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PosListParserTest {

    @Test
    public void parsesLatLonPairsToMicrodegrees() {
        assertThat(PosListParser.parse("59.72215 10.512689 59.722111 10.512651"))
                .containsExactly(59_722_150, 10_512_689, 59_722_111, 10_512_651);
    }

    @Test
    public void handlesIntegersNegativesAndExtraWhitespace() {
        assertThat(PosListParser.parse("  60 -5.5\n\t-0.000001 0 "))
                .containsExactly(60_000_000, -5_500_000, -1, 0);
    }

    @Test
    public void roundsBeyondSixDecimals() {
        assertThat(PosListParser.parse("1.0000004 1.0000005 -1.0000005"))
                .containsExactly(1_000_000, 1_000_001, -1_000_001);
    }

    @Test
    public void emptyOrBlankYieldsEmpty() {
        assertThat(PosListParser.parse("")).isEmpty();
        assertThat(PosListParser.parse("   ")).isEmpty();
    }
}
