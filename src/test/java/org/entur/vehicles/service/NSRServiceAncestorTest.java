package org.entur.vehicles.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flattening is a pure static function so it can be tested without a NeTEx file -
 * NSRService's real warm-up downloads and parses a multi-megabyte zip, which no test may do.
 */
public class NSRServiceAncestorTest {

    @Test
    public void testResolvesAQuayToItsStopPlace() {
        Map<String, String> childToParent = Map.of("NSR:Quay:749", "NSR:StopPlace:451");

        assertThat(NSRService.flattenAncestors(childToParent))
                .containsExactly(Map.entry("NSR:Quay:749", Set.of("NSR:StopPlace:451")));
    }

    @Test
    public void testClimbsThroughAMultimodalParent() {
        Map<String, String> childToParent = new LinkedHashMap<>();
        childToParent.put("NSR:Quay:749", "NSR:StopPlace:451");
        childToParent.put("NSR:StopPlace:451", "NSR:StopPlace:999");

        Map<String, Set<String>> flattened = NSRService.flattenAncestors(childToParent);

        assertThat(flattened.get("NSR:Quay:749"))
                .withFailMessage("a situation on the multimodal parent must still reach the quay")
                .containsExactlyInAnyOrder("NSR:StopPlace:451", "NSR:StopPlace:999");
        assertThat(flattened.get("NSR:StopPlace:451")).containsExactly("NSR:StopPlace:999");
    }

    @Test
    public void testACircularChainDoesNotHang() {
        Map<String, String> childToParent = new LinkedHashMap<>();
        childToParent.put("NSR:StopPlace:1", "NSR:StopPlace:2");
        childToParent.put("NSR:StopPlace:2", "NSR:StopPlace:1");

        Map<String, Set<String>> flattened = NSRService.flattenAncestors(childToParent);

        assertThat(flattened.get("NSR:StopPlace:1"))
                .withFailMessage("the climb must stop when it revisits a ref, keeping what it found")
                .containsExactly("NSR:StopPlace:2");
        assertThat(flattened.get("NSR:StopPlace:2")).containsExactly("NSR:StopPlace:1");
    }

    @Test
    public void testASelfReferencingParentIsNotItsOwnAncestor() {
        Map<String, String> childToParent = Map.of("NSR:StopPlace:1", "NSR:StopPlace:1");

        assertThat(NSRService.flattenAncestors(childToParent))
                .withFailMessage("a self-loop yields no ancestors, so the ref must be absent entirely")
                .isEmpty();
    }

    @Test
    public void testAChainDeeperThanTheCapIsTruncatedNotDropped() {
        Map<String, String> childToParent = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            childToParent.put("NSR:StopPlace:" + i, "NSR:StopPlace:" + (i + 1));
        }

        assertThat(NSRService.flattenAncestors(childToParent).get("NSR:StopPlace:0"))
                .withFailMessage("the depth cap must truncate the climb, not discard the ref")
                .hasSize(10);
    }

    @Test
    public void testARefWithNoParentIsAbsent() {
        assertThat(NSRService.flattenAncestors(Map.of())).isEmpty();
    }

    @Test
    public void testAncestorsOfIsEmptyWhenLookupIsDisabled() {
        NSRService service = new NSRService(false, "");

        assertThat(service.ancestorsOf("NSR:Quay:749")).isEmpty();
        assertThat(service.ancestorsOf(null)).isEmpty();
    }

    @Test
    public void testExpandWithAncestorsFallsBackToTheRefItself() {
        NSRService service = new NSRService(false, "");

        assertThat(service.expandWithAncestors("NSR:Quay:749"))
                .withFailMessage("with no ancestor data the caller must still get a usable ref back, "
                        + "so behaviour is unchanged when NSR lookup is disabled")
                .containsExactly("NSR:Quay:749");
    }

    @Test
    public void testExpandWithAncestorsOfNullIsEmpty() {
        NSRService service = new NSRService(false, "");

        assertThat(service.expandWithAncestors(null)).isEmpty();
    }

    /**
     * Exercises the populated map via the package-private constructor seam, rather than only
     * the empty (NSR lookup disabled) path every other test in this class uses. {@code enabled}
     * is passed as {@code false} because it is irrelevant here: {@code ancestorsOf} and
     * {@code expandWithAncestors} read {@code ancestorsByRef} unconditionally, and this seam
     * populates that map directly without going through {@code warmUpCache} (which is what
     * {@code enabled} actually gates).
     */
    private NSRService quayThroughMultimodalParent() {
        Map<String, String> childToParent = new LinkedHashMap<>();
        childToParent.put("NSR:Quay:749", "NSR:StopPlace:451");
        childToParent.put("NSR:StopPlace:451", "NSR:StopPlace:MULTIMODAL");

        return new NSRService(false, "", childToParent);
    }

    @Test
    public void testExpandWithAncestorsUnionsTheRefWithEveryAncestor() {
        NSRService service = quayThroughMultimodalParent();

        assertThat(service.expandWithAncestors("NSR:Quay:749"))
                .containsExactlyInAnyOrder("NSR:Quay:749", "NSR:StopPlace:451", "NSR:StopPlace:MULTIMODAL");
    }

    @Test
    public void testAncestorsOfDoesNotIncludeTheRefItself() {
        NSRService service = quayThroughMultimodalParent();

        assertThat(service.ancestorsOf("NSR:Quay:749"))
                .containsExactlyInAnyOrder("NSR:StopPlace:451", "NSR:StopPlace:MULTIMODAL");
    }

    @Test
    public void testExpandWithAncestorsOfARefAbsentFromThePopulatedMapIsJustTheRef() {
        NSRService service = quayThroughMultimodalParent();

        assertThat(service.expandWithAncestors("NSR:Quay:unknown"))
                .containsExactly("NSR:Quay:unknown");
    }
}
