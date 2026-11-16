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
                            @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:5}") int executorThreadCount,
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
  }

  static MessageReceiver messageReceiver(VehicleRepository vehicleRepository, PrometheusMetricsService metricsService) {
    return (pubsubMessage, ackReplyConsumer) -> {
      Instant receivedAt = Instant.now();
      Instant publishedAt = null;
      if (pubsubMessage.hasPublishTime()) {
        publishedAt = Instant.ofEpochSecond(pubsubMessage.getPublishTime().getSeconds(),
                pubsubMessage.getPublishTime().getNanos());
        metricsService.recordPubsubLatency(Duration.between(publishedAt, receivedAt));
      }

      VehicleActivityRecord vehicleActivity;
      try {
        vehicleActivity = SiriJsonReader.readVehicleActivity(pubsubMessage.getData().toStringUtf8());
      } catch (IOException | RuntimeException e) {
        // Not acked, so Pub/Sub redelivers it; each attempt is counted
        metricsService.markVehicleDropped("decode_error", 1);
        throw e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
      }

      VehicleUpdate stored = vehicleRepository.add(vehicleActivity);
      if (stored != null && publishedAt != null) {
        metricsService.recordSourceLatency(stored.getCodespace(),
                Duration.between(stored.getLastUpdated().toInstant(), publishedAt));
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    };
  }
}
