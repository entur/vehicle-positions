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
    public void transportModeResolvesTheJourneysOwnModeThenItsLineThenTheReportedLine() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addLine(new LineRecord("TST:Line:ferry", "Ferry", "F", null, null, "water"))
                .addLine(new LineRecord("TST:Line:rail", "Rail", "R", null, null, "rail"))
                .addLine(new LineRecord("TST:Line:replacement", "Replacement", "RB", null, null, "bus"))
                .addLine(new LineRecord("TST:Line:cable", "Cable car", "C", null, null, "cableway"))
                .addLine(new LineRecord("TST:Line:trolley", "Trolley", "T", null, null, "trolleyBus"))
                .addServiceJourney(new ServiceJourneyRecord("TST:ServiceJourney:onFerry", "JP", "TST:Line:ferry", null))
                .addServiceJourney(new ServiceJourneyRecord("TST:ServiceJourney:ferryRepeatsLine", "JP", "TST:Line:ferry", "water"))
                .addServiceJourney(new ServiceJourneyRecord("TST:ServiceJourney:replacementBus", "JP", "TST:Line:rail", "bus"))
                .addServiceJourney(new ServiceJourneyRecord("TST:ServiceJourney:onReplacementLine", "JP", "TST:Line:replacement", null))
                .build();

        assertThat(dataset.transportModeOf(null, "TST:Line:ferry")).isEqualTo(VehicleModeEnumeration.FERRY);
        assertThat(dataset.transportModeOf(null, "TST:Line:trolley")).isEqualTo(VehicleModeEnumeration.BUS);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:replacementBus", "TST:Line:rail"))
                .withFailMessage("a journey's own mode overrides its line's")
                .isEqualTo(VehicleModeEnumeration.BUS);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:ferryRepeatsLine", "TST:Line:rail"))
                .withFailMessage("a journey's own mode wins over a different reported line, even when it repeats its own line's")
                .isEqualTo(VehicleModeEnumeration.FERRY);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:onFerry", "TST:Line:rail"))
                .withFailMessage("a known journey's own line wins over the line the vehicle reports")
                .isEqualTo(VehicleModeEnumeration.FERRY);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:onReplacementLine", "TST:Line:rail"))
                .withFailMessage("a replacement journey reported on the rail line it replaces is still a bus")
                .isEqualTo(VehicleModeEnumeration.BUS);
        assertThat(dataset.transportModeOf("X:ServiceJourney:unknown", "TST:Line:rail"))
                .withFailMessage("an unknown journey falls back to the reported line")
                .isEqualTo(VehicleModeEnumeration.RAIL);
        assertThat(dataset.transportModeOf("TST:ServiceJourney:onFerry", "SKA:Line:notInNetex"))
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

    /**
     * A load keeps the builder until it returns; records still holding the parser's own copy
     * of every ref would keep those alive next to the dataset's shared ones.
     */
    @Test
    public void afterBuildTheBuildersRecordsShareTheDeclaredIdInstances() {
        String linkId = new String("X:ServiceLink:1");
        String patternId = new String("X:JourneyPattern:1");
        String lineId = new String("X:Line:1");
        PlannedDataset.Builder builder = new PlannedDataset.Builder()
                .addServiceLink(linkId, new int[]{1, 2})
                .addJourneyPattern(patternId, List.of(new String("X:ServiceLink:1")))
                .addLine(lineId, "One", "1")
                .addServiceJourney("X:ServiceJourney:1", new String("X:JourneyPattern:1"), new String("X:Line:1"));

        builder.build();

        assertThat(builder.journeyPatterns().get("X:JourneyPattern:1").serviceLinkIds()[0]).isSameAs(linkId);
        ServiceJourneyRecord journey = builder.serviceJourneys().get("X:ServiceJourney:1");
        assertThat(journey.journeyPatternId()).isSameAs(patternId);
        assertThat(journey.lineId()).isSameAs(lineId);
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

    @Test
    public void aRedeclaredIdIsCountedEvenWhenItsFirstDeclarationHadNoText() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addDestinationDisplay("X:DestinationDisplay:1", null)
                .addDestinationDisplay("X:DestinationDisplay:1", "Sentrum")
                .addOperatingDay("X:OperatingDay:1", null)
                .addOperatingDay("X:OperatingDay:1", "2026-10-02")
                .build();

        assertThat(dataset.stats().duplicateIds()).isEqualTo(2);
    }

    private static PlannedDataSink.StopDestinationDisplay at(int order, String destinationDisplayId) {
        return new PlannedDataSink.StopDestinationDisplay(order, destinationDisplayId);
    }

    @Test
    public void destinationDisplayFollowsTheStopOrder() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addDestinationDisplay("RUT:DestinationDisplay:A", "Majorstuen")
                .addDestinationDisplay("RUT:DestinationDisplay:B", "Jernbanetorget")
                .addDestinationDisplay("RUT:DestinationDisplay:B2", "Jernbanetorget")
                // Order 4 repeats B's text under another id: no change there.
                .addJourneyPattern("RUT:JourneyPattern:1", List.of(),
                        List.of(at(2, "RUT:DestinationDisplay:A"), at(3, "RUT:DestinationDisplay:B"), at(4, "RUT:DestinationDisplay:B2")))
                .addServiceJourney("RUT:ServiceJourney:1", "RUT:JourneyPattern:1")
                .build();

        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:1", null)).isEqualTo("Majorstuen");
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:1", 1))
                .withFailMessage("a stop before the first ref shows the first destination")
                .isEqualTo("Majorstuen");
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:1", 2)).isEqualTo("Majorstuen");
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:1", 3)).isEqualTo("Jernbanetorget");
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:1", 99)).isEqualTo("Jernbanetorget");
    }

    @Test
    public void destinationDisplayIsNullWhenNothingResolves() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addDestinationDisplay("RUT:DestinationDisplay:empty", null)
                .addJourneyPattern("RUT:JourneyPattern:none", List.of())
                .addJourneyPattern("RUT:JourneyPattern:dangling", List.of(), List.of(at(1, "RUT:DestinationDisplay:missing")))
                .addJourneyPattern("RUT:JourneyPattern:noText", List.of(), List.of(at(1, "RUT:DestinationDisplay:empty")))
                .addServiceJourney("RUT:ServiceJourney:none", "RUT:JourneyPattern:none")
                .addServiceJourney("RUT:ServiceJourney:dangling", "RUT:JourneyPattern:dangling")
                .addServiceJourney("RUT:ServiceJourney:noText", "RUT:JourneyPattern:noText")
                .addServiceJourney("RUT:ServiceJourney:noPattern", null)
                .build();

        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:none", 1)).isNull();
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:dangling", 1)).isNull();
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:noText", 1)).isNull();
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:noPattern", 1)).isNull();
        assertThat(dataset.destinationDisplayOf("RUT:ServiceJourney:unknown", 1)).isNull();
        assertThat(dataset.destinationDisplayOf(null, 1)).isNull();
    }
}
