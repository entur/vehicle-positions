package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlannedDataset.Builder#applyFutureWindow(LocalDate, Integer)} drops dated service
 * journeys whose resolved operating date is too far in the future. Anything it cannot date
 * with confidence - unknown or unparseable operating day - is kept.
 */
public class PlannedDatasetWindowTest {

    private static final LocalDate ASOF = LocalDate.of(2026, 9, 2);

    private static PlannedDataset.Builder builderWithDatedJourney(String operatingDate) {
        return new PlannedDataset.Builder()
                .addServiceJourney("X:ServiceJourney:1", "X:JourneyPattern:1")
                .addOperatingDay("X:OperatingDay:1", operatingDate)
                .addDatedServiceJourney("X:DatedServiceJourney:1", "X:ServiceJourney:1", "X:OperatingDay:1");
    }

    @Test
    public void journeyOnTheLastDayOfTheWindowIsKept() {
        PlannedDataset.Builder builder = builderWithDatedJourney(ASOF.plusDays(7).toString());

        int dropped = builder.applyFutureWindow(ASOF, 7);
        PlannedDataset dataset = builder.build();

        assertThat(dropped).isZero();
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNotNull();
        assertThat(dataset.stats().datedServiceJourneysDropped()).isZero();
    }

    @Test
    public void journeyPastTheWindowIsDropped() {
        PlannedDataset.Builder builder = builderWithDatedJourney(ASOF.plusDays(8).toString());

        int dropped = builder.applyFutureWindow(ASOF, 7);
        PlannedDataset dataset = builder.build();

        assertThat(dropped).isEqualTo(1);
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNull();
        assertThat(dataset.stats().datedServiceJourneysDropped()).isEqualTo(1);
    }

    @Test
    public void pastDatedJourneyIsKept() {
        PlannedDataset.Builder builder = builderWithDatedJourney(ASOF.minusDays(30).toString());

        int dropped = builder.applyFutureWindow(ASOF, 7);
        PlannedDataset dataset = builder.build();

        assertThat(dropped).isZero();
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNotNull();
    }

    @Test
    public void journeyWithUnknownOperatingDayIsKept() {
        PlannedDataset.Builder builder = new PlannedDataset.Builder()
                .addServiceJourney("X:ServiceJourney:1", "X:JourneyPattern:1")
                .addDatedServiceJourney("X:DatedServiceJourney:1", "X:ServiceJourney:1", "X:OperatingDay:missing");

        int dropped = builder.applyFutureWindow(ASOF, 7);
        PlannedDataset dataset = builder.build();

        assertThat(dropped).isZero();
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNotNull();
    }

    @Test
    public void journeyWithUnparseableDateIsKept() {
        PlannedDataset.Builder builder = builderWithDatedJourney("not-a-date");

        int dropped = builder.applyFutureWindow(ASOF, 7);
        PlannedDataset dataset = builder.build();

        assertThat(dropped).isZero();
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNotNull();
    }

    @Test
    public void nullFutureDaysDropsNothing() {
        PlannedDataset.Builder builder = builderWithDatedJourney(ASOF.plusYears(3).toString());

        int dropped = builder.applyFutureWindow(ASOF, null);
        PlannedDataset dataset = builder.build();

        assertThat(dropped).isZero();
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNotNull();
        assertThat(dataset.stats().datedServiceJourneysDropped()).isZero();
    }
}
