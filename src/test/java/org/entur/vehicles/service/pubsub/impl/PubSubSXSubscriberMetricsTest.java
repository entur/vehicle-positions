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
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingSituationMap;
import org.entur.vehicles.repository.AutoPurgingTimetableMap;
import org.entur.vehicles.repository.SituationMapper;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.repository.SituationTriggeredRepublisher;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.entur.vehicles.testutil.AvroRecords.withEmptyArrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SX receiver measures Pub/Sub delivery and decode failures, tagged type=situation */
class PubSubSXSubscriberMetricsTest {

    private PrometheusMeterRegistry registry;
    private MessageReceiver receiver;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMetricsService metrics = new PrometheusMetricsService(registry);
        NSRService nsrService = Mockito.mock(NSRService.class);
        SituationTriggeredRepublisher republisher = new SituationTriggeredRepublisher(
                metrics,
                new AutoPurgingTimetableMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new EstimatedTimetableUpdateRxPublisher(metrics),
                100,
                Duration.ofMillis(50),
                2000,
                nsrService);
        SituationRepository repository = new SituationRepository(
                metrics,
                new SituationMapper(new LineService(PlannedDataService.disabled()), nsrService,
                        new ServiceJourneyService(PlannedDataService.disabled())),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new SituationUpdateRxPublisher(metrics),
                republisher
        );
        receiver = PubSubSXSubscriber.messageReceiver(repository, metrics);
    }

    private static String situation() throws Exception {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber("TST:SituationNumber:1");
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().toString());
        record.setReportType("general");
        record.setVersion(1);
        record.setProgress("PUBLISHED");
        return JsonWriter.write(withEmptyArrays(record));
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
    void pubsubLatencyIsTaggedAsSituation() throws Exception {
        RecordingAck ack = new RecordingAck();
        receiver.receiveMessage(message(situation(), Instant.now().minusSeconds(2)), ack);

        assertTrue(ack.acked.get());
        Timer pubsub = registry.find("app.vehicles.latency.pubsub").tag("dataType", "situation").timer();
        assertNotNull(pubsub, "pubsub latency timer not registered");
        assertEquals(1, pubsub.count());
        double millis = pubsub.totalTime(TimeUnit.MILLISECONDS);
        assertTrue(millis >= 2_000 && millis < 10_000, "pubsub latency was " + millis + " ms");
    }

    @Test
    void undecodableMessageIsCountedAndNotAcked() {
        RecordingAck ack = new RecordingAck();

        assertThrows(RuntimeException.class,
                () -> receiver.receiveMessage(message("{not json", Instant.now()), ack));

        assertFalse(ack.acked.get());
        Counter dropped = registry.find("app.vehicles.dropped")
                .tags("dataType", "situation", "reason", "decode_error").counter();
        assertNotNull(dropped, "decode_error counter not registered");
        assertEquals(1, dropped.count());
    }

    @Test
    void ingestTimeIsTaggedAsSituation() throws Exception {
        receiver.receiveMessage(message(situation(), Instant.now()), new RecordingAck());

        Timer ingest = registry.find("app.vehicles.ingest.duration").tag("dataType", "situation").timer();
        assertNotNull(ingest, "ingest timer not tagged dataType=situation");
        assertEquals(1, ingest.count());
    }
}
