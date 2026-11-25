package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.SiriJsonReader;
import org.entur.vehicles.service.pubsub.PubSubSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class PubSubVMSubscriber extends PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubVMSubscriber.class.getName());

  public PubSubVMSubscriber(@Autowired VehicleRepository vehicleRepository,
                            @Autowired PrometheusMetricsService metricsService,
                            @Value("${entur.vehicle-positions.gcp.subscription.project.name}") String subscriptionProjectName,
                            @Value("${entur.vehicle-positions.gcp.subscription.name.vm}") String subscriptionName,
                            @Value("${entur.vehicle-positions.gcp.topic.project.name}") String topicProjectName,
                            @Value("${entur.vehicle-positions.gcp.topic.name.vm}") String topicName,
                            @Value("${entur.vehicle-positions.pubsub.parallel.pullcount:1}") int parallelPullCount,
                            @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:8}") int executorThreadCount,
                            @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels,
                            @Value("${entur.vehicle-positions.vm.enabled:true}") boolean enabled) {
    super(subscriptionProjectName,
            subscriptionName,
            topicProjectName,
            topicName,
            parallelPullCount,
            executorThreadCount,
            appLabels,
            messageReceiver(vehicleRepository, metricsService),
            enabled
    );
    if (enabled) {
      metricsService.registerIngestThreads(PrometheusMetricsService.TYPE_VEHICLE, executorThreadCount);
    }
  }

  static MessageReceiver messageReceiver(VehicleRepository vehicleRepository, PrometheusMetricsService metricsService) {
    return ReceivedMessages.timed(metricsService.ingestTimer(PrometheusMetricsService.TYPE_VEHICLE), (pubsubMessage, ackReplyConsumer) -> {
      Instant receivedAt = Instant.now();
      Instant publishedAt = ReceivedMessages.publishedAt(pubsubMessage);
      if (publishedAt != null) {
        metricsService.recordPubsubLatency(PrometheusMetricsService.TYPE_VEHICLE, Duration.between(publishedAt, receivedAt));
      }

      VehicleActivityRecord vehicleActivity;
      try {
        vehicleActivity = SiriJsonReader.readVehicleActivity(pubsubMessage.getData().toStringUtf8());
      } catch (IOException | RuntimeException e) {
        // Not acked, so Pub/Sub redelivers it; each attempt is counted
        metricsService.markDropped(PrometheusMetricsService.TYPE_VEHICLE, "decode_error", 1);
        throw e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
      }

      // Read from this message, not the stored VehicleUpdate: the repository falls back to
      // ingest time when no time is reported, and other threads may overwrite the stored entry
      Instant reportedAt = reportedAt(vehicleActivity);

      VehicleUpdate stored = vehicleRepository.add(vehicleActivity);
      if (stored != null && reportedAt == null) {
        metricsService.markSourceTimeMissing(PrometheusMetricsService.TYPE_VEHICLE, stored.getCodespace());
      } else if (stored != null && publishedAt != null) {
        metricsService.recordSourceLatency(PrometheusMetricsService.TYPE_VEHICLE, stored.getCodespace(), Duration.between(reportedAt, publishedAt));
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    });
  }

  /** Same precedence as VehicleRepository's lastUpdated, minus the ingest-time fallback */
  private static Instant reportedAt(VehicleActivityRecord vehicleActivity) {
    CharSequence reported = vehicleActivity.getMonitoredVehicleJourney() != null
            && vehicleActivity.getMonitoredVehicleJourney().getLocationRecordedAtTime() != null
            ? vehicleActivity.getMonitoredVehicleJourney().getLocationRecordedAtTime()
            : vehicleActivity.getRecordedAtTime();
    return ReceivedMessages.parseReportedTime(reported);
  }
}
