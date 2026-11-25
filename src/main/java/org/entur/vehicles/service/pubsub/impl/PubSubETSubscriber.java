package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.TimetableRepository;
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
public class PubSubETSubscriber extends PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubETSubscriber.class.getName());

  public PubSubETSubscriber(@Autowired TimetableRepository timetableRepository,
                            @Autowired PrometheusMetricsService metricsService,
                            @Value("${entur.vehicle-positions.gcp.subscription.project.name}") String subscriptionProjectName,
                            @Value("${entur.vehicle-positions.gcp.subscription.name.et}") String subscriptionName,
                            @Value("${entur.vehicle-positions.gcp.topic.project.name}") String topicProjectName,
                            @Value("${entur.vehicle-positions.gcp.topic.name.et}") String topicName,
                            @Value("${entur.vehicle-positions.pubsub.parallel.pullcount:1}") int parallelPullCount,
                            @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:8}") int executorThreadCount,
                            @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels,
                            @Value("${entur.vehicle-positions.et.enabled:false}") boolean enabled) {
    super(subscriptionProjectName,
            subscriptionName,
            topicProjectName,
            topicName,
            parallelPullCount,
            executorThreadCount,
            appLabels,
            messageReceiver(timetableRepository, metricsService),
            enabled
    );
    if (enabled) {
      metricsService.registerIngestThreads(PrometheusMetricsService.TYPE_TIMETABLE, executorThreadCount);
    }
  }

  static MessageReceiver messageReceiver(TimetableRepository timetableRepository, PrometheusMetricsService metricsService) {
    return ReceivedMessages.timed(metricsService.ingestTimer(PrometheusMetricsService.TYPE_TIMETABLE), (pubsubMessage, ackReplyConsumer) -> {
      Instant receivedAt = Instant.now();
      Instant publishedAt = ReceivedMessages.publishedAt(pubsubMessage);
      if (publishedAt != null) {
        metricsService.recordPubsubLatency(PrometheusMetricsService.TYPE_TIMETABLE, Duration.between(publishedAt, receivedAt));
      }

      EstimatedVehicleJourneyRecord journey;
      try {
        journey = SiriJsonReader.readEstimatedVehicleJourney(pubsubMessage.getData().toStringUtf8());
      } catch (IOException | RuntimeException e) {
        // Not acked, so Pub/Sub redelivers it; each attempt is counted
        metricsService.markDropped(PrometheusMetricsService.TYPE_TIMETABLE, "decode_error", 1);
        throw e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
      }

      Instant reportedAt = ReceivedMessages.parseReportedTime(journey.getRecordedAtTime());

      EstimatedTimetableUpdate stored = timetableRepository.add(journey);
      if (stored != null && reportedAt == null) {
        metricsService.markSourceTimeMissing(PrometheusMetricsService.TYPE_TIMETABLE, stored.getCodespace());
      } else if (stored != null && publishedAt != null) {
        metricsService.recordSourceLatency(PrometheusMetricsService.TYPE_TIMETABLE, stored.getCodespace(), Duration.between(reportedAt, publishedAt));
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    });
  }
}
