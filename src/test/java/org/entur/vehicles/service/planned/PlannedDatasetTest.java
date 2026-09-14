package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.VehicleModeEnumeration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PlannedDatasetTest {

    @Test
    public void lookupsResolveWhatTheBuilderWasGiven() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addOperator("GOA:Operator:GOA", "Go-Ahead Nordic AS")
                .addLine("GOA:Line:59", "Jærbanen", "L5")
                .addServiceLink("GOA:ServiceLink:1", new int[]{58000000, 5000000, 58001000, 5001000})
                .addJourneyPattern("GOA:JourneyPattern:1", List.of("GOA:ServiceLink:1"))
                .addServiceJourney("GOA:ServiceJourney:1", "GOA:JourneyPattern:1")
                .addOperatingDay("GOA:OperatingDay:2024-01-20", "2024-01-20")
                .addDatedServiceJourney("GOA:DatedServiceJourney:1", "GOA:ServiceJourney:1", "GOA:OperatingDay:2024-01-20")
                .build();

        assertThat(dataset.operator("GOA:Operator:GOA").getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(dataset.line("GOA:Line:59").getLineName()).isEqualTo("Jærbanen");
        assertThat(dataset.line("GOA:Line:59").getPublicCode()).isEqualTo("L5");
        assertThat(dataset.hasServiceJourney("GOA:ServiceJourney:1")).isTrue();
        assertThat(dataset.journeyPatternOf("GOA:ServiceJourney:1")).isEqualTo("GOA:JourneyPattern:1");
        assertThat(dataset.datedServiceJourney("GOA:DatedServiceJourney:1"))
                .isEqualTo(new DatedJourneyRef("GOA:ServiceJourney:1", "2024-01-20"));
        assertThat(dataset.serviceJourneyCount()).isEqualTo(1);
    }

    @Test
    public void transportModeResolvesJourneyOverrideThenGivenLineThenTheJourneysOwnLine() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addLine("TST:Line:ferry", "Ferry", "F", null, null, "water")
                .addLine("TST:Line:rail", "Rail", "R", null, null, "rail")
                .addLine("TST:Line:cable", "Cable car", "C", null, null, "cableway")
                .addLine("TST:Line:trolley", "Trolley", "T", null, null, "trolleyBus")
                .addServiceJourney("TST:ServiceJourney:onFerry", "JP", "TST:Line:ferry", null)
                .addServiceJourney("TST:ServiceJourney:replacementBus", "JP", "TST:Line:rail", "bus")
                .build();

        assertThat(dataset.transportModeOf(null, "TST:Line:ferry")).isEqualTo(VehicleModeEnumeration.FERRY);
        assertThat(dataset.transportModeOf(null, "TST:Line:trolley")).isEqualTo(VehicleModeEnumeration.BUS);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:replacementBus", "TST:Line:rail"))
                .withFailMessage("a journey's own mode overrides its line's")
                .isEqualTo(VehicleModeEnumeration.BUS);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:onFerry", "TST:Line:rail"))
                .withFailMessage("the line the vehicle reports wins over the journey's own line")
                .isEqualTo(VehicleModeEnumeration.RAIL);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:onFerry", "SKA:Line:notInNetex"))
                .withFailMessage("an unknown reported line falls back to the journey's own line")
                .isEqualTo(VehicleModeEnumeration.FERRY);
        assertThat(dataset.transportModeOf(null, "TST:Line:cable"))
                .withFailMessage("a NeTEx mode with no VehicleModeEnumeration counterpart resolves nothing")
                .isNull();
        assertThat(dataset.transportModeOf("X:ServiceJourney:unknown", "X:Line:unknown")).isNull();
        assertThat(dataset.transportModeOf(null, null)).isNull();
    }

    @Test
    public void missesReturnNull() {
        PlannedDataset dataset = new PlannedDataset.Builder().build();

        assertThat(dataset.operator("X:Operator:1")).isNull();
        assertThat(dataset.line("X:Line:1")).isNull();
        assertThat(dataset.hasServiceJourney("X:ServiceJourney:1")).isFalse();
        assertThat(dataset.journeyPatternOf("X:ServiceJourney:1")).isNull();
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1")).isNull();
        assertThat(dataset.operator(null)).isNull();
    }

    @Test
    public void emptyDatasetHasNothing() {
        assertThat(PlannedDataset.EMPTY.serviceJourneyCount()).isZero();
        assertThat(PlannedDataset.EMPTY.line("X:Line:1")).isNull();
    }

    @Test
    public void datedServiceJourneyWithUnknownOperatingDayKeepsTheServiceJourneyAndCountsIt() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceJourney("X:ServiceJourney:1", "X:JourneyPattern:1")
                .addJourneyPattern("X:JourneyPattern:1", List.of())
                .addDatedServiceJourney("X:DatedServiceJourney:1", "X:ServiceJourney:1", "X:OperatingDay:missing")
                .build();

        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1"))
                .isEqualTo(new DatedJourneyRef("X:ServiceJourney:1", null));
        assertThat(dataset.stats().unresolvedOperatingDayRefs()).isEqualTo(1);
    }

    @Test
    public void unresolvedRefsAreCountedNotThrown() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceJourney("X:ServiceJourney:1", "X:JourneyPattern:missing")
                .addJourneyPattern("X:JourneyPattern:1", List.of("X:ServiceLink:missing"))
                .addDatedServiceJourney("X:DatedServiceJourney:1", "X:ServiceJourney:missing", "X:OperatingDay:missing")
                .build();

        PlannedDataset.Stats stats = dataset.stats();
        assertThat(stats.unresolvedPatternRefs()).isEqualTo(1);
        assertThat(stats.unresolvedLinkRefs()).isEqualTo(1);
        assertThat(stats.unresolvedServiceJourneyRefs()).isEqualTo(1);
        assertThat(stats.unresolvedOperatingDayRefs()).isEqualTo(1);
        // The SJ is still known, even though its pattern is not
        assertThat(dataset.hasServiceJourney("X:ServiceJourney:1")).isTrue();
        // A DSJ whose SJ is unknown is still resolvable to that SJ id
        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1").serviceJourneyId())
                .isEqualTo("X:ServiceJourney:missing");
    }

    @Test
    public void datedServiceJourneyServiceJourneyIdIsCanonicalisedToTheDeclaredInstance() {
        String declaredId = new String("X:ServiceJourney:1");
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceJourney(declaredId, "X:JourneyPattern:1")
                .addJourneyPattern("X:JourneyPattern:1", List.of())
                .addDatedServiceJourney("X:DatedServiceJourney:1", new String("X:ServiceJourney:1"), null)
                .build();

        assertThat(dataset.datedServiceJourney("X:DatedServiceJourney:1").serviceJourneyId())
                .isSameAs(declaredId);
    }

    @Test
    public void duplicateIdsLastOneWinsAndAreCounted() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addLine("X:Line:1", "first", "1")
                .addLine("X:Line:1", "second", "1")
                .build();

        assertThat(dataset.line("X:Line:1").getLineName()).isEqualTo("second");
        assertThat(dataset.stats().duplicateIds()).isEqualTo(1);
        assertThat(dataset.stats().lines()).isEqualTo(1);
    }
}
