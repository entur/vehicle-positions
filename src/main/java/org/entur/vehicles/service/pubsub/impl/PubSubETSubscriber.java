package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.vehicles.repository.TimetableRepository;
import org.entur.vehicles.service.pubsub.PubSubSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class PubSubETSubscriber extends PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubETSubscriber.class.getName());

  public PubSubETSubscriber(@Autowired TimetableRepository timetableRepository,
                            @Value("${entur.vehicle-positions.gcp.subscription.project.name}") String subscriptionProjectName,
                            @Value("${entur.vehicle-positions.gcp.subscription.name.et}") String subscriptionName,
                            @Value("${entur.vehicle-positions.gcp.topic.project.name}") String topicProjectName,
                            @Value("${entur.vehicle-positions.gcp.topic.name.et}") String topicName,
                            @Value("${entur.vehicle-positions.pubsub.parallel.pullcount:1}") int parallelPullCount,
                            @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:5}") int executorThreadCount,
                            @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels,
                            @Value("${entur.vehicle-positions.et.enabled:false}") boolean enabled) {
    super(subscriptionProjectName,
            subscriptionName,
            topicProjectName,
            topicName,
            parallelPullCount,
            executorThreadCount,
            appLabels,
            getMessageReceiver(timetableRepository),
            enabled
    );
  }

  private static MessageReceiver getMessageReceiver(TimetableRepository timetableRepository) {
    return (pubsubMessage, ackReplyConsumer) -> {
      try {
        timetableRepository.add(
                JsonReader.readEstimatedVehicleJourney(pubsubMessage.getData().toStringUtf8())
        );
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    };
  }
}
