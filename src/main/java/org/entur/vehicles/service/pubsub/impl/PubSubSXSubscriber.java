package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.service.pubsub.PubSubSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class PubSubSXSubscriber extends PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubSXSubscriber.class.getName());

  public PubSubSXSubscriber(@Autowired SituationRepository situationRepository,
                            @Value("${entur.vehicle-positions.gcp.subscription.project.name}") String subscriptionProjectName,
                            @Value("${entur.vehicle-positions.gcp.subscription.name.sx}") String subscriptionName,
                            @Value("${entur.vehicle-positions.gcp.topic.project.name}") String topicProjectName,
                            @Value("${entur.vehicle-positions.gcp.topic.name.sx}") String topicName,
                            @Value("${entur.vehicle-positions.pubsub.parallel.pullcount:1}") int parallelPullCount,
                            @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:5}") int executorThreadCount,
                            @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels,
                            @Value("${entur.vehicle-positions.sx.enabled:false}") boolean enabled) {
    super(subscriptionProjectName,
            subscriptionName,
            topicProjectName,
            topicName,
            parallelPullCount,
            executorThreadCount,
            appLabels,
            getMessageReceiver(situationRepository),
            enabled
    );
  }

  private static MessageReceiver getMessageReceiver(SituationRepository situationRepository) {
    return (pubsubMessage, ackReplyConsumer) -> {
      try {
        situationRepository.add(
                JsonReader.readPtSituationElement(pubsubMessage.getData().toStringUtf8())
        );
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    };
  }
}
