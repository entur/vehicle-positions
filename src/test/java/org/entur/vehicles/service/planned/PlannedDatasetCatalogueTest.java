package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue queries (lines, operators, codespaces, serviceJourneys, serviceJourney) are
 * served from the planned dataset. Filters keep the regex semantics the old vehicle-backed
 * resolvers had (ObjectRef.matches), results are sorted by ref.
 */
public class PlannedDatasetCatalogueTest {

    private static PlannedDataset dataset() {
        return new PlannedDataset.Builder()
                .addOperator("SKY:Operator:2", "Skyss")
                .addOperator("RUT:Operator:1", "Ruter")
                .addLine("SKY:Line:9", "Nine", "9")
                .addLine("RUT:Line:2", "Two", "2")
                .addLine("RUT:Line:1", "One", "1")
                .addJourneyPattern("JP", List.of())
                .addServiceJourney("RUT:ServiceJourney:2", "JP", "RUT:Line:1")
                .addServiceJourney("RUT:ServiceJourney:1", "JP", "RUT:Line:1")
                .addServiceJourney("RUT:ServiceJourney:3", "JP", "RUT:Line:2")
                .addServiceJourney("SKY:ServiceJourney:1", "JP", "SKY:Line:9")
                .addServiceJourney("X:ServiceJourney:orphan", "JP", "X:Line:missing")
                .addOperatingDay("RUT:OperatingDay:2026-08-25", "2026-08-25")
                .addDatedServiceJourney("RUT:DatedServiceJourney:1", "RUT:ServiceJourney:1", "RUT:OperatingDay:2026-08-25")
                .build();
    }

    @Test
    public void linesAreSortedAndFilteredByCodespace() {
        PlannedDataset dataset = dataset();

        assertThat(dataset.lines(null)).extracting(Line::getLineRef)
                .containsExactly("RUT:Line:1", "RUT:Line:2", "SKY:Line:9");
        assertThat(dataset.lines("RUT")).extracting(Line::getLineRef)
                .containsExactly("RUT:Line:1", "RUT:Line:2");
        assertThat(dataset.lines("R.*")).withFailMessage("codespace filters keep regex semantics")
                .hasSize(2);
        assertThat(dataset.lines("BAH")).isEmpty();
    }

    @Test
    public void operatorsAreSortedAndFilteredByCodespace() {
        PlannedDataset dataset = dataset();

        assertThat(dataset.operators(null)).extracting(Operator::getOperatorRef)
                .containsExactly("RUT:Operator:1", "SKY:Operator:2");
        assertThat(dataset.operators("SKY")).extracting(Operator::getName).containsExactly("Skyss");
    }

    @Test
    public void codespacesAreDerivedFromLineAndOperatorIds() {
        assertThat(dataset().codespaces()).extracting(Codespace::getCodespaceId)
                .containsExactly("RUT", "SKY");
    }

    @Test
    public void serviceJourneyIdsByLineAndCodespace() {
        PlannedDataset dataset = dataset();

        assertThat(dataset.serviceJourneyIds("RUT:Line:1", null))
                .containsExactly("RUT:ServiceJourney:1", "RUT:ServiceJourney:2");
        assertThat(dataset.serviceJourneyIds("RUT:Line:.*", null)).hasSize(3);
        assertThat(dataset.serviceJourneyIds(null, "RUT")).hasSize(3);
        assertThat(dataset.serviceJourneyIds("RUT:Line:1", "SKY")).isEmpty();
        assertThat(dataset.serviceJourneyIds(null, null))
                .withFailMessage("journeys on an unknown line are not part of the catalogue")
                .hasSize(4)
                .doesNotContain("X:ServiceJourney:orphan");
    }

    @Test
    public void serviceJourneysByIdsKeepRequestOrderDropUnknownsAndHonourFilters() {
        PlannedDataset dataset = dataset();

        assertThat(dataset.serviceJourneys(List.of(
                "RUT:ServiceJourney:3", "RUT:DatedServiceJourney:1", "RUT:ServiceJourney:nope", "RUT:ServiceJourney:3"),
                null, null))
                .withFailMessage("request order is kept, unknown ids are dropped, duplicates collapse")
                .extracting(ServiceJourney::getId)
                .containsExactly("RUT:ServiceJourney:3", "RUT:ServiceJourney:1");
        assertThat(dataset.serviceJourneys(List.of("RUT:DatedServiceJourney:1"), null, null).get(0).getDate())
                .isEqualTo("2026-08-25");

        assertThat(dataset.serviceJourneys(List.of("RUT:ServiceJourney:3", "SKY:ServiceJourney:1"), null, "SKY"))
                .extracting(ServiceJourney::getId).containsExactly("SKY:ServiceJourney:1");
        assertThat(dataset.serviceJourneys(List.of("RUT:ServiceJourney:3", "RUT:DatedServiceJourney:1"), "RUT:Line:1", null))
                .extracting(ServiceJourney::getId).containsExactly("RUT:ServiceJourney:1");
        assertThat(dataset.serviceJourneys(List.of("RUT:ServiceJourney:3"), "RUT:Line:.*", "RUT"))
                .extracting(ServiceJourney::getId).containsExactly("RUT:ServiceJourney:3");

        assertThat(dataset.serviceJourneys(List.of("X:ServiceJourney:orphan"), null, null))
                .withFailMessage("a journey on an undeclared line is still found by id")
                .hasSize(1);
        assertThat(dataset.serviceJourneys(List.of("X:ServiceJourney:orphan"), "X:Line:.*", null))
                .withFailMessage("line filters match the line ref the journey carries, declared or not")
                .hasSize(1);
        assertThat(dataset.serviceJourneys(List.of("X:ServiceJourney:orphan"), "RUT:Line:.*", null)).isEmpty();
        assertThat(dataset.serviceJourneys(List.of(), null, null)).isEmpty();
    }

    @Test
    public void serviceJourneyResolvesByServiceJourneyIdOrDatedServiceJourneyId() {
        PlannedDataset dataset = dataset();

        ServiceJourney byId = dataset.serviceJourney("RUT:ServiceJourney:1");
        assertThat(byId.getId()).isEqualTo("RUT:ServiceJourney:1");
        assertThat(byId.getDate()).isNull();

        ServiceJourney byDatedId = dataset.serviceJourney("RUT:DatedServiceJourney:1");
        assertThat(byDatedId.getId()).isEqualTo("RUT:ServiceJourney:1");
        assertThat(byDatedId.getDate()).isEqualTo("2026-08-25");

        assertThat(dataset.serviceJourney("RUT:ServiceJourney:nope")).isNull();
        assertThat(dataset.serviceJourney(null)).isNull();
    }

    @Test
    public void lineOfAServiceJourneyAndUnresolvedLineRefsAreCounted() {
        PlannedDataset dataset = dataset();

        assertThat(dataset.lineOf("RUT:ServiceJourney:3")).isEqualTo("RUT:Line:2");
        assertThat(dataset.lineOf("X:ServiceJourney:orphan")).isEqualTo("X:Line:missing");
        assertThat(dataset.lineOf("nope")).isNull();
        assertThat(dataset.stats().unresolvedLineRefs()).isEqualTo(1);
    }

    @Test
    public void twoArgAddServiceJourneyStillWorksWithoutALine() {
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addJourneyPattern("JP", List.of())
                .addServiceJourney("A:ServiceJourney:1", "JP")
                .build();

        assertThat(dataset.hasServiceJourney("A:ServiceJourney:1")).isTrue();
        assertThat(dataset.lineOf("A:ServiceJourney:1")).isNull();
        assertThat(dataset.stats().unresolvedLineRefs()).isZero();
    }
}
