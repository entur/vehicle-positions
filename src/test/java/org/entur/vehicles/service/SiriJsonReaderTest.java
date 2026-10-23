package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.avro.realtime.siri.helper.JsonWriter;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SiriJsonReader} reuses one decoder per thread, so every message after the first on a
 * thread goes through {@code JsonDecoder.configure}. These tests pin that reuse to the
 * behaviour of a fresh decoder per message, which is what siri-avro-model's
 * {@code JsonReader} does.
 */
class SiriJsonReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String vm(String recordedAt, String validUntil) throws IOException {
        VehicleActivityRecord record = new VehicleActivityRecord();
        record.setRecordedAtTime(recordedAt);
        record.setValidUntilTime(validUntil);
        return JsonWriter.write(record);
    }

    private static String et(String lineRef, String recordedAt) throws IOException {
        EstimatedVehicleJourneyRecord record = new EstimatedVehicleJourneyRecord();
        // The no-arg constructor leaves the non-nullable arrays null, which the writer rejects
        for (Schema.Field field : record.getSchema().getFields()) {
            if (field.schema().getType() == Schema.Type.ARRAY) {
                record.put(field.pos(), new ArrayList<>());
            }
        }
        record.setLineRef(lineRef);
        record.setRecordedAtTime(recordedAt);
        return JsonWriter.write(record);
    }

    @Test
    void aVehicleActivityReadsAsTheLibraryReadsIt() throws IOException {
        String json = vm("2026-10-23T12:00:00+02:00", "2026-10-23T12:05:00+02:00");

        assertEquals(JsonReader.readVehicleActivity(json), SiriJsonReader.readVehicleActivity(json));
    }

    @Test
    void anEstimatedVehicleJourneyReadsAsTheLibraryReadsIt() throws IOException {
        String json = et("TST:Line:1", "2026-10-23T12:00:00+02:00");

        assertEquals(JsonReader.readEstimatedVehicleJourney(json), SiriJsonReader.readEstimatedVehicleJourney(json));
    }

    @Test
    void everyFixtureSituationReadsAsTheLibraryReadsIt() throws IOException {
        JsonNode situations;
        try (var in = getClass().getResourceAsStream("/sx/sx-snapshot-response.json")) {
            assertNotNull(in, "fixture must be on the test classpath");
            situations = MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .get("serviceDelivery")
                    .get("situationExchangeDeliveries").get(0)
                    .get("situations");
        }

        for (JsonNode situation : situations) {
            String json = MAPPER.writeValueAsString(
                    AvroJsonUnionWrapper.wrap(situation, PtSituationElementRecord.getClassSchema()));

            assertEquals(JsonReader.readPtSituationElement(json), SiriJsonReader.readPtSituationElement(json));
        }
    }

    @Test
    void successiveMessagesOnOneThreadAreReadIndependently() throws IOException {
        String first = et("TST:Line:1", "2026-10-23T12:00:00+02:00");
        String second = et("TST:Line:2", "2026-10-23T12:01:00+02:00");

        assertEquals("TST:Line:1", SiriJsonReader.readEstimatedVehicleJourney(first).getLineRef().toString());
        assertEquals("TST:Line:2", SiriJsonReader.readEstimatedVehicleJourney(second).getLineRef().toString());
        assertEquals("TST:Line:1", SiriJsonReader.readEstimatedVehicleJourney(first).getLineRef().toString());
    }

    @Test
    void aMessageThatFailsHalfwayDoesNotAffectTheNextOne() throws IOException {
        String valid = et("TST:Line:1", "2026-10-23T12:00:00+02:00");
        String truncated = valid.substring(0, valid.length() / 2);

        SiriJsonReader.readEstimatedVehicleJourney(valid);
        assertThrows(Exception.class, () -> SiriJsonReader.readEstimatedVehicleJourney(truncated));

        assertEquals(JsonReader.readEstimatedVehicleJourney(valid), SiriJsonReader.readEstimatedVehicleJourney(valid));
    }

    @Test
    void threadsReadConcurrentlyWithoutMixingMessages() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String lineRef = "TST:Line:" + t;
                String json = et(lineRef, "2026-10-23T12:00:00+02:00");
                tasks.add(() -> {
                    for (int i = 0; i < 500; i++) {
                        assertEquals(lineRef, SiriJsonReader.readEstimatedVehicleJourney(json).getLineRef().toString());
                    }
                    return null;
                });
            }
            for (Future<Void> result : executor.invokeAll(tasks)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
