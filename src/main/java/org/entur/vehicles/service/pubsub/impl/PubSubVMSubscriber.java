package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.pubsub.PubSubSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class PubSubVMSubscriber extends PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubVMSubscriber.class.getName());

  public PubSubVMSubscriber(@Autowired VehicleRepository vehicleRepository,
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
            getMessageReceiver(vehicleRepository),
            enabled
    );
  }

  private static MessageReceiver getMessageReceiver(VehicleRepository vehicleRepository) {
    return (pubsubMessage, ackReplyConsumer) -> {
      try {
        vehicleRepository.add(
                JsonReader.readVehicleActivity(pubsubMessage.getData().toStringUtf8())
        );
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    };
  }
}
