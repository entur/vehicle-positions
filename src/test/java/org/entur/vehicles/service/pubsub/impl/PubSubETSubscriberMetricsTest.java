package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.avro.realtime.siri.helper.JsonWriter;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingTimetableMap;
import org.entur.vehicles.repository.TimetableRepository;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.entur.vehicles.testutil.AvroRecords.withEmptyArrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ET receiver measures the same pipeline stages as the VM receiver, tagged type=timetable */
class PubSubETSubscriberMetricsTest {

    private PrometheusMeterRegistry registry;
    private MessageReceiver receiver;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMetricsService metrics = new PrometheusMetricsService(registry);
        NSRService nsrService = Mockito.mock(NSRService.class);
        TimetableRepository repository = new TimetableRepository(
                metrics,
                new LineService(PlannedDataService.disabled()),
                Mockito.mock(ServiceJourneyService.class),
                nsrService,
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                180,
                new EstimatedTimetableUpdateRxPublisher(metrics)
        );
        receiver = PubSubETSubscriber.messageReceiver(repository, metrics);
    }

    private static String journey(ZonedDateTime recordedAt) throws Exception {
        EstimatedCallRecord call = new EstimatedCallRecord();
        call.setStopPointRef("NSR:Quay:1");
        call.setOrder(1);
        call.setAimedArrivalTime(ZonedDateTime.now().plusMinutes(5).toString());
        call.setExpectedArrivalTime(ZonedDateTime.now().plusMinutes(6).toString());

        EstimatedVehicleJourneyRecord journey = new EstimatedVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef("TST:Line:1");
        journey.setVehicleRef("TST:Vehicle:1");
        journey.setRecordedAtTime(recordedAt != null ? recordedAt.toString() : null);
        journey.setMonitored(true);
        journey.setEstimatedCalls(List.of(call));
        return JsonWriter.write(withEmptyArrays(journey));
    }

    private static PubsubMessage message(String data, Instant publishTime) {
        return PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(data))
                .setPublishTime(Timestamp.newBuilder()
                        .setSeconds(publishTime.getEpochSecond())
                        .setNanos(publishTime.getNano()))
                .build();
    }

    private static final class RecordingAck implements AckReplyConsumer {
        final AtomicBoolean acked = new AtomicBoolean();
        @Override public void ack() { acked.set(true); }
        @Override public void nack() { }
    }

    @Test
    void pubsubLatencyIsTaggedAsTimetable() throws Exception {
        receiver.receiveMessage(message(journey(ZonedDateTime.now()), Instant.now().minusSeconds(2)), new RecordingAck());

        Timer pubsub = registry.find("app.vehicles.latency.pubsub").tag("dataType", "timetable").timer();
        assertNotNull(pubsub, "pubsub latency timer not registered");
        assertEquals(1, pubsub.count());
        double millis = pubsub.totalTime(TimeUnit.MILLISECONDS);
        assertTrue(millis >= 2_000 && millis < 10_000, "pubsub latency was " + millis + " ms");
    }

    @Test
    void sourceLatencyIsTimeFromRecordedAtToPublishPerCodespace() throws Exception {
        Instant published = Instant.now();
        ZonedDateTime recordedAt = ZonedDateTime.ofInstant(published.minusSeconds(5), ZoneId.of("Europe/Oslo"));

        receiver.receiveMessage(message(journey(recordedAt), published), new RecordingAck());

        Timer source = registry.find("app.vehicles.latency.source")
                .tags("dataType", "timetable", "codespaceId", "TST").timer();
        assertNotNull(source, "source latency timer not registered");
        assertEquals(1, source.count());
        assertEquals(5_000, source.totalTime(TimeUnit.MILLISECONDS), 1);
    }

    @Test
    void journeyWithoutRecordedAtIsCountedAsMissing() throws Exception {
        receiver.receiveMessage(message(journey(null), Instant.now()), new RecordingAck());

        assertNull(registry.find("app.vehicles.latency.source").tag("dataType", "timetable").timer());
        Counter missing = registry.find("app.vehicles.latency.source.missing")
                .tags("dataType", "timetable", "codespaceId", "TST").counter();
        assertNotNull(missing, "missing counter not registered");
        assertEquals(1, missing.count());
    }

    @Test
    void undecodableMessageIsCountedAndNotAcked() {
        RecordingAck ack = new RecordingAck();

        assertThrows(RuntimeException.class,
                () -> receiver.receiveMessage(message("{not json", Instant.now()), ack));

        assertFalse(ack.acked.get());
        Counter dropped = registry.find("app.vehicles.dropped")
                .tags("dataType", "timetable", "reason", "decode_error").counter();
        assertNotNull(dropped, "decode_error counter not registered");
        assertEquals(1, dropped.count());
    }

    @Test
    void ingestTimeIsTaggedAsTimetable() throws Exception {
        receiver.receiveMessage(message(journey(ZonedDateTime.now()), Instant.now()), new RecordingAck());

        Timer ingest = registry.find("app.vehicles.ingest.duration").tag("dataType", "timetable").timer();
        assertNotNull(ingest, "ingest timer not tagged dataType=timetable");
        assertEquals(1, ingest.count());
    }
}
