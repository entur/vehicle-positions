package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SnapshotKeyTest {

    @Test
    public void objectNameCarriesPrefixDatasetVersionAndEtag() {
        SnapshotKey key = SnapshotKey.of("planned-data", 3, "\"abc123\"").orElseThrow();

        assertThat(key.objectName("snapshots")).isEqualTo("snapshots/planned-data/v3/abc123.bin.gz");
        assertThat(key.objectName("")).isEqualTo("planned-data/v3/abc123.bin.gz");
        assertThat(key.objectName(null)).isEqualTo("planned-data/v3/abc123.bin.gz");
    }

    @Test
    public void etagIsNormalised() {
        assertThat(SnapshotKey.normaliseEtag("\"abc\"")).isEqualTo("abc");
        assertThat(SnapshotKey.normaliseEtag("W/\"abc\"")).isEqualTo("abc");
        assertThat(SnapshotKey.normaliseEtag("  abc ")).isEqualTo("abc");
        assertThat(SnapshotKey.normaliseEtag("abc")).isEqualTo("abc");
    }

    @Test
    public void aMissingOrBlankEtagYieldsNoKey() {
        assertThat(SnapshotKey.of("nsr", 1, null)).isEmpty();
        assertThat(SnapshotKey.of("nsr", 1, "")).isEmpty();
        assertThat(SnapshotKey.of("nsr", 1, "\"\"")).isEmpty();
    }

    @Test
    public void unsafeCharactersInAnEtagAreReplaced() {
        SnapshotKey key = SnapshotKey.of("nsr", 1, "a/b c").orElseThrow();

        assertThat(key.objectName("")).isEqualTo("nsr/v1/a_b_c.bin.gz");
    }
}
