package org.entur.vehicles.service.planned;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NetexPlannedDataExtractorTest {

    private static PlannedDataset extract(String... resources) throws IOException, XMLStreamException {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        NetexPlannedDataExtractor extractor = new NetexPlannedDataExtractor();
        for (String resource : resources) {
            try (InputStream in = NetexPlannedDataExtractorTest.class.getResourceAsStream("/netex/" + resource)) {
                assertThat(in).withFailMessage("missing test resource " + resource).isNotNull();
                extractor.extract(in, builder);
            }
        }
        return builder.build();
    }

    @Test
    public void extractsOperatorsOperatingDaysAndServiceLinksFromSharedData() throws Exception {
        PlannedDataset dataset = extract("fragment-shared-data.xml");

        assertThat(dataset.operator("TST:Operator:1").getName()).isEqualTo("Test Operator AS");
        assertThat(dataset.stats().serviceLinks()).isEqualTo(2);
        assertThat(dataset.stats().operators()).isEqualTo(1);
    }

    @Test
    public void extractsLinesPatternsJourneysAndDatedJourneysFromLineFile() throws Exception {
        PlannedDataset dataset = extract("fragment-line-file.xml");

        assertThat(dataset.line("TST:Line:204").getLineName()).isEqualTo("Rykkinn - Kolsås - Sandvika");
        assertThat(dataset.line("TST:Line:204").getPublicCode()).isEqualTo("204");
        assertThat(dataset.line("TST:FlexibleLine:8202").getLineName()).isEqualTo("Bestillingsrute");
        assertThat(dataset.line("TST:FlexibleLine:8202").getPublicCode()).isEqualTo("8202");
        assertThat(dataset.journeyPatternOf("TST:ServiceJourney:1")).isEqualTo("TST:JourneyPattern:1");
        assertThat(dataset.stats().journeyPatterns()).isEqualTo(1);
        assertThat(dataset.stats().datedServiceJourneys()).isEqualTo(1);
    }

    @Test
    public void crossFileRefsResolveRegardlessOfFileOrder() throws Exception {
        PlannedDataset dataset = extract("fragment-line-file.xml", "fragment-shared-data.xml");

        assertThat(dataset.datedServiceJourney("TST:DatedServiceJourney:1"))
                .withFailMessage("the nested DatedServiceJourneyRef must not be mistaken for the ServiceJourneyRef")
                .isEqualTo(new DatedJourneyRef("TST:ServiceJourney:1", "2024-01-20"));
        assertThat(dataset.stats().unresolvedLinkRefs()).isZero();
        assertThat(dataset.stats().unresolvedPatternRefs()).isZero();
        assertThat(dataset.stats().unresolvedOperatingDayRefs()).isZero();

        // Pattern links are in linksInSequence order; the second link has no geometry
        assertThat(dataset.pointsOnLink("TST:JourneyPattern:1").getLength()).isEqualTo(2);
        assertThat(dataset.pointsOnLink("TST:JourneyPattern:1").getPoints())
                .isEqualTo(Polyline.encode(new int[]{59_722_150, 10_512_689, 59_722_111, 10_512_651}));
    }

    @Test
    public void nestedNameElementsDoNotLeakIntoTheParent() throws Exception {
        // ServiceJourney has a <Name> that must not be mistaken for anything; JourneyPattern's
        // <Name> is not extracted at all. Both would show up as bogus lines/operators if the
        // extractor matched on element name without regard to nesting.
        PlannedDataset dataset = extract("fragment-line-file.xml");

        assertThat(dataset.stats().lines()).isEqualTo(2);
        assertThat(dataset.stats().operators()).isZero();
    }

    @Test
    public void malformedXmlThrowsAfterKeepingWhatParsed() throws Exception {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        try (InputStream in = getClass().getResourceAsStream("/netex/fragment-malformed.xml")) {
            assertThatThrownBy(() -> new NetexPlannedDataExtractor().extract(in, builder))
                    .isInstanceOf(XMLStreamException.class);
        }

        assertThat(builder.build().line("TST:Line:before")).isNotNull();
    }

    @Test
    public void serviceJourneyLineRefIsTakenFromTheJourneyNotFromRoutes() throws Exception {
        PlannedDataset dataset = extract("fragment-line-file.xml");

        assertThat(dataset.lineOf("TST:ServiceJourney:1")).isEqualTo("TST:Line:204");
        assertThat(dataset.lineOf("TST:ServiceJourney:2"))
                .withFailMessage("FlexibleLineRef counts as the journey's line")
                .isEqualTo("TST:FlexibleLine:8202");
        assertThat(dataset.serviceJourneyIds("TST:Line:204", null)).containsExactly("TST:ServiceJourney:1");
        assertThat(dataset.stats().unresolvedLineRefs())
                .withFailMessage("the Route's LineRef (TST:Line:decoy) must not be attributed to any journey")
                .isZero();
    }
}
