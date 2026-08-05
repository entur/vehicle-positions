package org.entur.vehicles.graphql;

import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.repository.SituationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test anywhere in this suite previously booted a Spring context, so nothing verified
 * that {@code vehicle-updates.graphqls} actually wires to the resolvers - e.g. that every
 * {@code Situation} field resolves against a {@code SituationUpdate} getter, that
 * {@code Affects.vehicleModes} (a {@code Set}) coerces to {@code [VehicleModeEnumeration]},
 * that {@code age: Duration} serializes on the output side, and that
 * {@code lastUpdatedEpochSecond: Float} accepts a boxed {@code Long}. A mismatch there is a
 * silent runtime null, not a compile error.
 * <p>
 * All three Pub/Sub subscribers and every external lookup (line, operator, NSR,
 * service journey) are disabled via {@code src/test/resources/application.properties},
 * so this stays hermetic - no GCP credentials and no network access required.
 * <p>
 * Uses {@link ExecutionGraphQlService} - the transport-agnostic core that Spring Boot's
 * GraphQL autoconfiguration always registers - directly, rather than the
 * {@code spring-graphql-test} module's {@code GraphQlTester}/{@code @GraphQlTest}, which
 * is not on this project's classpath (spring-boot-starter-graphql only, no added
 * dependency) and would need to be added to the build to use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationGraphQlSchemaTests {

    @Autowired
    private ExecutionGraphQlService graphQlService;

    @Autowired
    private SituationRepository situationRepository;

    @Test
    void contextLoads() {
        // If the schema fails to parse, or a field can't be wired to any resolver/getter,
        // context startup itself fails - this alone is a meaningful assertion.
    }

    @Test
    void situationsQueryResolvesEveryFieldIncludingSetAndDurationCoercions() {
        situationRepository.add(situationRecord());

        String document = """
                query {
                  situations(includeClosed: true) {
                    situationNumber
                    codespace { codespaceId }
                    version
                    progress
                    severity
                    reportType
                    lastUpdatedEpochSecond
                    age
                    openEnded
                    affects {
                      vehicleModes
                      lines { lineRef }
                    }
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-request", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();
        assertThat(response.isValid()).isTrue();

        String situationNumber = response.field("situations[0].situationNumber").getValue();
        assertThat(situationNumber).isEqualTo("TST:SituationNumber:schema-wiring");

        String codespaceId = response.field("situations[0].codespace.codespaceId").getValue();
        assertThat(codespaceId).isEqualTo("TST");

        Integer version = response.field("situations[0].version").getValue();
        assertThat(version).isEqualTo(1);

        String progress = response.field("situations[0].progress").getValue();
        assertThat(progress).isEqualTo("published");

        String severity = response.field("situations[0].severity").getValue();
        assertThat(severity).isEqualTo("severe");

        // Boxed Long (SituationUpdate.getLastUpdatedEpochSecond()) coerced to GraphQL Float.
        Number lastUpdatedEpochSecond = response.field("situations[0].lastUpdatedEpochSecond").getValue();
        assertThat(lastUpdatedEpochSecond.doubleValue()).isPositive();

        // Duration serialized on the output side by DurationScalarConfiguration.
        String age = response.field("situations[0].age").getValue();
        assertThat(age).startsWith("PT");

        // Set<VehicleModeEnumeration> coerced to a GraphQL list.
        List<String> vehicleModes = response.field("situations[0].affects.vehicleModes").getValue();
        assertThat(vehicleModes).containsExactly("BUS");

        String lineRef = response.field("situations[0].affects.lines[0].lineRef").getValue();
        assertThat(lineRef).isEqualTo("TST:Line:1");
    }

    private PtSituationElementRecord situationRecord() {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber("TST:SituationNumber:schema-wiring");
        record.setParticipantRef("TST");
        record.setVersion(1);
        record.setProgress("PUBLISHED");
        record.setSeverity("SEVERE");
        record.setReportType("general");
        record.setCreationTime(ZonedDateTime.now().minusMinutes(1).toString());
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());

        AffectedLineRecord line = new AffectedLineRecord();
        line.setLineRef("TST:Line:1");

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setVehicleMode("BUS");
        network.setAffectedLines(List.of(line));

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        record.setAffects(affects);

        return record;
    }
}
