package org.entur.vehicles.data.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ObjectRef.matches} runs per stored vehicle on every query and per update on every
 * subscription. It used to be {@code getRef().matches(other.getRef())}, which compiled the
 * vehicle's ref as a regex on each call: a fifth of all allocation in prd, and a ref holding a
 * regex metacharacter would match other refs or fail to match itself.
 */
class ObjectRefMatchesTest {

    @Test
    void anEqualRefMatches() {
        assertThat(new DatedServiceJourney("TST:DatedServiceJourney:1")
                .matches(new DatedServiceJourney("TST:DatedServiceJourney:1"))).isTrue();
    }

    @Test
    void aDifferentRefDoesNotMatch() {
        assertThat(new DatedServiceJourney("TST:DatedServiceJourney:1")
                .matches(new DatedServiceJourney("TST:DatedServiceJourney:2"))).isFalse();
    }

    @Test
    void aRefWithRegexMetacharactersMatchesItself() {
        assertThat(new DatedServiceJourney("TST:DatedServiceJourney:1+2")
                .matches(new DatedServiceJourney("TST:DatedServiceJourney:1+2"))).isTrue();
    }

    @Test
    void aDotInTheOtherRefIsNotAWildcard() {
        assertThat(new DatedServiceJourney("TST:DatedServiceJourney:1x2")
                .matches(new DatedServiceJourney("TST:DatedServiceJourney:1.2"))).isFalse();
    }

    @Test
    void aRefOfAnotherTypeMatchesOnTheRefAlone() {
        // QueryFilter matches a DatedServiceJourney filter against a vehicle's ServiceJourney
        assertThat(new DatedServiceJourney("TST:ServiceJourney:1")
                .matches(new ServiceJourney("TST:ServiceJourney:1"))).isTrue();
    }

    @Test
    void nullDoesNotMatch() {
        assertThat(new DatedServiceJourney("TST:DatedServiceJourney:1").matches(null)).isFalse();
    }
}
