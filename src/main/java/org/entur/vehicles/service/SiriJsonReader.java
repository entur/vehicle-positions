package org.entur.vehicles.service;

import org.apache.avro.Schema;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.avro.specific.SpecificDatumReader;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;

import java.io.IOException;

/**
 * Drop-in replacement for siri-avro-model's {@code JsonReader} that reuses one Avro
 * {@link JsonDecoder} per thread and record type.
 * <p>
 * {@code JsonReader} builds a new decoder for every message, and the decoder's constructor
 * regenerates the parsing grammar for the whole schema. In prd that was 3% of CPU and 10% of
 * all allocation (~30 MB/s) on the Pub/Sub threads. {@link JsonDecoder#configure(String)} keeps the grammar
 * and resets everything else - the parser stack, the reorder buffers and the JSON parser - so a
 * decoder is safe to reuse even after a message that failed halfway through.
 * <p>
 * A decoder is not thread-safe, hence one per thread. The datum readers are shared, as in
 * {@code JsonReader}; Avro keeps their per-thread state itself.
 */
public final class SiriJsonReader {

    private static final RecordReader<VehicleActivityRecord> VM =
            new RecordReader<>(VehicleActivityRecord.getClassSchema(), VehicleActivityRecord.class);
    private static final RecordReader<EstimatedVehicleJourneyRecord> ET =
            new RecordReader<>(EstimatedVehicleJourneyRecord.getClassSchema(), EstimatedVehicleJourneyRecord.class);
    private static final RecordReader<PtSituationElementRecord> SX =
            new RecordReader<>(PtSituationElementRecord.getClassSchema(), PtSituationElementRecord.class);

    private SiriJsonReader() {
    }

    public static VehicleActivityRecord readVehicleActivity(String json) throws IOException {
        return VM.read(json);
    }

    public static EstimatedVehicleJourneyRecord readEstimatedVehicleJourney(String json) throws IOException {
        return ET.read(json);
    }

    public static PtSituationElementRecord readPtSituationElement(String json) throws IOException {
        return SX.read(json);
    }

    private static final class RecordReader<T> {

        private final Schema schema;
        private final DatumReader<T> datumReader;
        private final ThreadLocal<JsonDecoder> decoders = new ThreadLocal<>();

        RecordReader(Schema schema, Class<T> type) {
            this.schema = schema;
            this.datumReader = new SpecificDatumReader<>(type);
        }

        T read(String json) throws IOException {
            JsonDecoder decoder = decoders.get();
            if (decoder == null) {
                decoder = DecoderFactory.get().jsonDecoder(schema, json);
                decoders.set(decoder);
            } else {
                decoder.configure(json);
            }
            return datumReader.read(null, decoder);
        }
    }
}
