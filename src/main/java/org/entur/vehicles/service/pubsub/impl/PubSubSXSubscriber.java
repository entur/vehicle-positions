package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.service.SiriJsonReader;
import org.entur.vehicles.service.pubsub.PubSubSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

// The startup snapshot must finish loading before the stream starts: `version` is null on
// most real situations, so the repository's version guard cannot stop a late snapshot
// record from overwriting fresher streamed data.
@Service
@DependsOn("situationSnapshotService")
public class PubSubSXSubscriber extends PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubSXSubscriber.class.getName());

  public PubSubSXSubscriber(@Autowired SituationRepository situationRepository,
                            @Autowired PrometheusMetricsService metricsService,
                            @Value("${entur.vehicle-positions.gcp.subscription.project.name}") String subscriptionProjectName,
                            @Value("${entur.vehicle-positions.gcp.subscription.name.sx}") String subscriptionName,
                            @Value("${entur.vehicle-positions.gcp.topic.project.name}") String topicProjectName,
                            @Value("${entur.vehicle-positions.gcp.topic.name.sx}") String topicName,
                            @Value("${entur.vehicle-positions.pubsub.parallel.pullcount:1}") int parallelPullCount,
                            @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:8}") int executorThreadCount,
                            @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels,
                            @Value("${entur.vehicle-positions.sx.enabled:false}") boolean enabled) {
    super(subscriptionProjectName,
            subscriptionName,
            topicProjectName,
            topicName,
            parallelPullCount,
            executorThreadCount,
            appLabels,
            messageReceiver(situationRepository, metricsService),
            enabled
    );
  }

  static MessageReceiver messageReceiver(SituationRepository situationRepository, PrometheusMetricsService metricsService) {
    return (pubsubMessage, ackReplyConsumer) -> {
      Instant publishedAt = ReceivedMessages.publishedAt(pubsubMessage);
      if (publishedAt != null) {
        metricsService.recordPubsubLatency(PrometheusMetricsService.TYPE_SITUATION, Duration.between(publishedAt, Instant.now()));
      }

      PtSituationElementRecord situation;
      try {
        situation = SiriJsonReader.readPtSituationElement(pubsubMessage.getData().toStringUtf8());
      } catch (IOException | RuntimeException e) {
        // Not acked, so Pub/Sub redelivers it; each attempt is counted
        metricsService.markDropped(PrometheusMetricsService.TYPE_SITUATION, "decode_error", 1);
        throw e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
      }

      // No source latency: a situation's creation or version time says little about pipeline delay
      situationRepository.add(situation);

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    };
  }
}
