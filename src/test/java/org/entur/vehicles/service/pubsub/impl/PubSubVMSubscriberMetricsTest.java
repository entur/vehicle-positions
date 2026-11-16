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
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingVehicleMap;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.InvalidLocationRegistry;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The VM receiver measures the two halves of the pipeline: from the vehicle's reported time to
 * Anshar's Pub/Sub publish (operator and Anshar), and from that publish until this pod received
 * the message (Pub/Sub delivery and local queueing).
 */
class PubSubVMSubscriberMetricsTest {

    private PrometheusMeterRegistry registry;
    private MessageReceiver receiver;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PrometheusMetricsService metrics = new PrometheusMetricsService(registry);
        VehicleRepository repository = new VehicleRepository(
                metrics,
                new LineService(PlannedDataService.disabled()),
                Mockito.mock(ServiceJourneyService.class),
                new AutoPurgingVehicleMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                1440,
                false,
                new VehicleUpdateRxPublisher(metrics),
                new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0")
        );
        receiver = PubSubVMSubscriber.messageReceiver(repository, metrics);
    }

    private static String vehicle(ZonedDateTime locationRecordedAt) throws Exception {
        LocationRecord location = new LocationRecord();
        location.setLatitude(59.91);
        location.setLongitude(10.75);

        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setVehicleRef("TST:Vehicle:1");
        journey.setVehicleLocation(location);
        journey.setLocationRecordedAtTime(locationRecordedAt == null ? null : locationRecordedAt.toString());
        // Avro arrays are non-nullable
        journey.setVehicleModes(List.of());
        journey.setPublishedLineNames(List.of());
        journey.setOriginNames(List.of());
        journey.setDestinationNames(List.of());

        VehicleActivityRecord activity = new VehicleActivityRecord();
        activity.setMonitoredVehicleJourney(journey);
        activity.setRecordedAtTime(locationRecordedAt == null ? null : locationRecordedAt.toString());
        activity.setValidUntilTime(ZonedDateTime.now().plusMinutes(10).toString());
        return JsonWriter.write(activity);
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
    void sourceLatencyIsTimeFromReportedPositionToPublishPerCodespace() throws Exception {
        Instant published = Instant.now();
        ZonedDateTime reported = ZonedDateTime.ofInstant(published.minusSeconds(5), java.time.ZoneId.of("Europe/Oslo"));

        receiver.receiveMessage(message(vehicle(reported), published), new RecordingAck());

        Timer source = registry.find("app.vehicles.latency.source").tag("codespaceId", "TST").timer();
        assertNotNull(source, "source latency timer not registered");
        assertEquals(1, source.count());
        assertEquals(5_000, source.totalTime(TimeUnit.MILLISECONDS), 1);
    }

    @Test
    void sourceLatencyIsNotRecordedWithoutReportedTime() throws Exception {
        RecordingAck ack = new RecordingAck();

        // Publish time after ingest, so a sample taken from the ingest-time fallback would be positive and kept
        receiver.receiveMessage(message(vehicle(null), Instant.now().plusSeconds(5)), ack);

        assertTrue(ack.acked.get());
        Timer source = registry.find("app.vehicles.latency.source").timer();
        assertTrue(source == null || source.count() == 0,
                "source latency recorded from ingest time instead of a reported time");
    }

    @Test
    void pubsubLatencyIsTimeFromPublishToReceipt() throws Exception {
        Instant published = Instant.now().minusSeconds(2);
        ZonedDateTime reported = ZonedDateTime.now();

        receiver.receiveMessage(message(vehicle(reported), published), new RecordingAck());

        Timer pubsub = registry.find("app.vehicles.latency.pubsub").timer();
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
                .tags("type", "vehicle", "reason", "decode_error").counter();
        assertNotNull(dropped, "decode_error counter not registered");
        assertEquals(1, dropped.count());
    }
}
