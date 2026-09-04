package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stop-ref set an entry is scoped by is derived once, at ingest, and never again.
 * <p>
 * {@code SituationMatcher} is rebuilt for every GraphQL batch - including every republished ET
 * event during a situation-triggered fan-out - and used to build this set per entry on every one
 * of those rebuilds. A situation naming thousands of dated journeys made that megabytes of
 * garbage per build, thousands of times per fan-out. The set is immutable and the entries are
 * immutable, so computing it in the constructor is safe and the matcher can simply reference it.
 */
class AffectedEntryStopRefsTest {

    @Test
    void aJourneyEntryDerivesItsStopRefsFromItsStops() {
        AffectedVehicleJourney journey = new AffectedVehicleJourney(
                new ServiceJourney("TST:ServiceJourney:1"), null, null, null,
                List.of(stop("NSR:StopPlace:1"), stop("NSR:StopPlace:2")));

        assertThat(journey.stopRefs()).containsExactlyInAnyOrder("NSR:StopPlace:1", "NSR:StopPlace:2");
    }

    @Test
    void aLineEntryDerivesItsStopRefsFromItsStops() {
        AffectedLine line = new AffectedLine(new Line("TST:Line:1"),
                List.of(stop("NSR:StopPlace:1"), stop("NSR:StopPlace:2")));

        assertThat(line.stopRefs()).containsExactlyInAnyOrder("NSR:StopPlace:1", "NSR:StopPlace:2");
    }

    /** The whole point of the change: derived once and handed out, not rebuilt per read. */
    @Test
    void theStopRefSetIsTheSameInstanceOnEveryRead() {
        AffectedVehicleJourney journey = new AffectedVehicleJourney(null, null, null, null,
                List.of(stop("NSR:StopPlace:1")));
        AffectedLine line = new AffectedLine(new Line("TST:Line:1"), List.of(stop("NSR:StopPlace:1")));

        assertThat(journey.stopRefs())
                .withFailMessage("the set must be derived in the constructor, not per read - the "
                        + "matcher reads it on every per-batch rebuild")
                .isSameAs(journey.stopRefs());
        assertThat(line.stopRefs()).isSameAs(line.stopRefs());
    }

    @Test
    void anEntryWithoutStopsHasAnEmptyStopRefSet() {
        assertThat(new AffectedVehicleJourney(null, null, null, null, null).stopRefs()).isEmpty();
        assertThat(new AffectedLine(new Line("TST:Line:1"), List.of()).stopRefs()).isEmpty();
    }

    /** A stop the mapper could not identify contributes nothing, exactly as before. */
    @Test
    void aStopWithoutAnIdIsLeftOutOfTheSet() {
        AffectedVehicleJourney journey = new AffectedVehicleJourney(null, null, null, null,
                List.of(new AffectedStop(null, List.of()), stop("NSR:StopPlace:1")));

        assertThat(journey.stopRefs()).containsExactly("NSR:StopPlace:1");
    }

    /** The same stop named twice - two sections of one journey sharing a boundary - is one ref. */
    @Test
    void aRepeatedStopIsOneRef() {
        AffectedVehicleJourney journey = new AffectedVehicleJourney(null, null, null, null,
                List.of(stop("NSR:StopPlace:1"), stop("NSR:StopPlace:1")));

        assertThat(journey.stopRefs()).containsExactly("NSR:StopPlace:1");
    }

    /** Handed out directly to the matcher, so it must not be modifiable by anything it reaches. */
    @Test
    void theStopRefSetIsImmutable() {
        Set<String> refs = new AffectedVehicleJourney(null, null, null, null,
                List.of(stop("NSR:StopPlace:1"))).stopRefs();

        assertThatThrownBy(() -> refs.add("NSR:StopPlace:2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AffectedStop stop(String stopRef) {
        return new AffectedStop(new StopPoint(stopRef), List.of());
    }
}
