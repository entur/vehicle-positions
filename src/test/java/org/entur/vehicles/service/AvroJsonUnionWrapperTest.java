package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AvroJsonUnionWrapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Schema SITUATION_SCHEMA = PtSituationElementRecord.getClassSchema();

    private JsonNode fixtureSituations() throws IOException {
        try (var in = getClass().getResourceAsStream("/sx/sx-snapshot-response.json")) {
            assertNotNull(in, "fixture must be on the test classpath");
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .get("serviceDelivery")
                    .get("situationExchangeDeliveries").get(0)
                    .get("situations");
        }
    }

    @Test
    public void testEveryFixtureSituationBecomesReadableByJsonReader() throws IOException {
        JsonNode situations = fixtureSituations();
        assertEquals(4, situations.size(), "fixture is expected to hold four situations");

        for (JsonNode situation : situations) {
            JsonNode wrapped = AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA);
            PtSituationElementRecord record =
                    JsonReader.readPtSituationElement(MAPPER.writeValueAsString(wrapped));

            assertNotNull(record.getSituationNumber());
            assertNotNull(record.getParticipantRef());
        }
    }

    @Test
    public void testValuesSurviveTheRewrite() throws IOException {
        JsonNode closed = null;
        for (JsonNode situation : fixtureSituations()) {
            if ("RUT:SituationNumber:2026-64179-1".equals(situation.get("situationNumber").asText())) {
                closed = situation;
            }
        }
        assertNotNull(closed, "fixture must contain the CLOSED situation");

        PtSituationElementRecord record = JsonReader.readPtSituationElement(
                MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(closed, SITUATION_SCHEMA)));

        assertEquals("RUT:SituationNumber:2026-64179-1", record.getSituationNumber().toString());
        assertEquals("CLOSED", record.getProgress().toString());
        assertEquals("RUT", record.getParticipantRef().toString());
        assertEquals(1, record.getValidityPeriods().size());
        assertNotNull(record.getAffects(), "the nested affects record must survive");
    }

    @Test
    public void testMultipleValidityPeriodsSurvive() throws IOException {
        for (JsonNode situation : fixtureSituations()) {
            if ("RUT:SituationNumber:823246".equals(situation.get("situationNumber").asText())) {
                PtSituationElementRecord record = JsonReader.readPtSituationElement(
                        MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA)));
                assertEquals(3, record.getValidityPeriods().size());
                return;
            }
        }
        throw new AssertionError("fixture must contain RUT:SituationNumber:823246");
    }

    @Test
    public void testNullUnionValueStaysNull() throws IOException {
        for (JsonNode situation : fixtureSituations()) {
            PtSituationElementRecord record = JsonReader.readPtSituationElement(
                    MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA)));
            // `version` is null on virtually every real situation - it must round-trip as null,
            // not as a wrapper object or a zero.
            if (situation.get("version") == null || situation.get("version").isNull()) {
                assertEquals(null, record.getVersion());
                return;
            }
        }
        throw new AssertionError("fixture must contain a situation with a null version");
    }

    /**
     * A field whose shape does not match the schema must fail loudly. Quietly treating a
     * non-array as an empty array would blank out every situation's validity periods on an
     * upstream format change, and since a situation with no periods reports
     * openEnded = true, the whole feed would silently look open-ended - corrupting exactly
     * the signal the quality tooling produces.
     */
    @Test
    public void testATypeMismatchThrowsRatherThanSilentlyYieldingEmpty() throws IOException {
        JsonNode situation = fixtureSituations().get(0);
        ObjectNode corrupted = ((ObjectNode) situation.deepCopy())
                .put("validityPeriods", "not-an-array");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AvroJsonUnionWrapper.wrap(corrupted, SITUATION_SCHEMA));
        assertTrue(thrown.getMessage().contains("array"),
                "the failure should name the expected type, was: " + thrown.getMessage());
    }

    @Test
    public void testAnAbsentRequiredFieldThrowsNamingTheField() throws IOException {
        ObjectNode corrupted = (ObjectNode) fixtureSituations().get(0).deepCopy();
        corrupted.remove("reportType");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AvroJsonUnionWrapper.wrap(corrupted, SITUATION_SCHEMA));
        assertTrue(thrown.getMessage().contains("reportType"),
                "the failure should name the missing field, was: " + thrown.getMessage());
    }

    /**
     * Every array field on these records declares a default of [], so an absent array
     * legitimately means "empty" and must NOT throw. Pinned because an earlier review
     * argued the opposite on the mistaken belief that validityPeriods was required.
     */
    @Test
    public void testAnAbsentArrayFieldIsEmptyNotAnError() throws IOException {
        ObjectNode situation = (ObjectNode) fixtureSituations().get(0).deepCopy();
        situation.remove("validityPeriods");

        JsonNode wrapped = AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA);
        PtSituationElementRecord record =
                JsonReader.readPtSituationElement(MAPPER.writeValueAsString(wrapped));

        assertNotNull(record.getValidityPeriods());
        assertTrue(record.getValidityPeriods().isEmpty());
    }

    /**
     * The wrapper picks the first non-null branch of every union. That is only correct
     * while every union has exactly one. If a siri-avro-model upgrade introduces a union
     * with two non-null branches, this test fails here rather than silently mis-parsing a
     * field in production.
     */
    @Test
    public void testEveryUnionInTheSchemaHasExactlyOneNonNullBranch() {
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.List<String> offenders = new java.util.ArrayList<>();
        assertUnionsAreNullable(SITUATION_SCHEMA, "PtSituationElement", visited, offenders);
        assertTrue(offenders.isEmpty(), "unions with more than one non-null branch: " + offenders);
    }

    private void assertUnionsAreNullable(Schema schema, String path,
                                         java.util.Set<String> visited,
                                         java.util.List<String> offenders) {
        switch (schema.getType()) {
            case UNION -> {
                long nonNull = schema.getTypes().stream()
                        .filter(t -> t.getType() != Schema.Type.NULL)
                        .count();
                if (nonNull > 1) {
                    offenders.add(path);
                }
                schema.getTypes().forEach(t -> assertUnionsAreNullable(t, path, visited, offenders));
            }
            case RECORD -> {
                if (visited.add(schema.getFullName())) {
                    schema.getFields().forEach(f ->
                            assertUnionsAreNullable(f.schema(), path + "." + f.name(), visited, offenders));
                }
            }
            case ARRAY -> assertUnionsAreNullable(schema.getElementType(), path + "[]", visited, offenders);
            case MAP -> assertUnionsAreNullable(schema.getValueType(), path + "{}", visited, offenders);
            default -> { }
        }
    }
}
