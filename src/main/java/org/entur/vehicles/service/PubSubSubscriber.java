package org.entur.vehicles.service;

import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.protobuf.Duration;
import com.google.pubsub.v1.ExpirationPolicy;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubSubscriber.class.getName());
  private final int reconnectPeriodSec = 5;
  private final int parallelPullCount;
  private final int executorThreadCount;

  private final VehicleRepository vehicleRepository;

  private SubscriptionAdminClient subscriptionAdminClient;

  private final TopicName topic;
  private final SubscriptionName projectSubscriptionName;
  private final Map<String, String> appLabels = new HashMap<>();
  @Value("${entur.vehicle-positions.shutdownhook:false}")
  private boolean addManualShutdownhook;

  public PubSubSubscriber(@Autowired VehicleRepository vehicleRepository,
                          @Value("${entur.vehicle-positions.gcp.subscription.project.name}") String subscriptionProjectName,
                          @Value("${entur.vehicle-positions.gcp.subscription.name}") String subscriptionName,
                          @Value("${entur.vehicle-positions.gcp.topic.project.name}") String topicProjectName,
                          @Value("${entur.vehicle-positions.gcp.topic.name}") String topicName,
                          @Value("${entur.vehicle-positions.pubsub.parallel.pullcount:1}") int parallelPullCount,
                          @Value("${entur.vehicle-positions.pubsub.parallel.executorThreadCount:5}") int executorThreadCount,
                          @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels) {
    this.vehicleRepository = vehicleRepository;
    this.parallelPullCount = parallelPullCount;
    this.executorThreadCount = executorThreadCount;

    projectSubscriptionName = SubscriptionName.of(subscriptionProjectName, subscriptionName);
    topic = TopicName.of(topicProjectName, topicName);
    this.appLabels.putAll(appLabels);

    if (System.getenv("HOSTNAME") != null) {
      this.appLabels.put("pod", System.getenv("HOSTNAME"));
    }

    try {
      subscriptionAdminClient = SubscriptionAdminClient.create();
      if (addManualShutdownhook) {
        addShutdownHook();
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @PostConstruct
  public void startSubscriptionAsync() {
    ExecutorService service = Executors.newSingleThreadExecutor();
    service.execute(() -> {
        subscribe();
    });
  }


  private void subscribe()
  {

    if (subscriptionAdminClient == null) {
      throw new NullPointerException("Unable to initialize application");
    }

    Subscription subscription;
    try {
      LOG.info("Creating subscription {}", projectSubscriptionName);
      subscription = subscriptionAdminClient.createSubscription(Subscription
              .newBuilder()
              .setTopic(topic.toString())
              .setName(projectSubscriptionName.toString())
              .putAllLabels(appLabels)
              .setPushConfig(PushConfig.getDefaultInstance())
              .setMessageRetentionDuration(
                      // How long will an unprocessed message be kept - minimum 10 minutes
                      Duration.newBuilder().setSeconds(600).build())
              .setExpirationPolicy(ExpirationPolicy.newBuilder()
                      // How long will the subscription exist when no longer in use - minimum 1 day
                      .setTtl(Duration.newBuilder().setSeconds(86400).build()).build())
              .build());

      LOG.info("Created subscription {} on topic {}", projectSubscriptionName, topic);

    } catch (AlreadyExistsException e) {
      LOG.info("Subscription already exists - reconnect to existing {}", projectSubscriptionName);
      subscription = subscriptionAdminClient.getSubscription(projectSubscriptionName);
      LOG.info("Use already existing subscription {}", projectSubscriptionName);
    }

    final VehicleMonitoringReceiver receiver = new VehicleMonitoringReceiver();

    Subscriber subscriber = null;
    while (true) {
      try {
        LOG.info("Starting subscriber");
        subscriber = Subscriber.newBuilder(subscription.getName(), receiver)
                .setParallelPullCount(parallelPullCount)
                .setExecutorProvider(
                        InstantiatingExecutorProvider.newBuilder()
                                .setExecutorThreadCount(executorThreadCount)
                                .build()
                ).build();
        subscriber.startAsync().awaitRunning();
        LOG.info("Started subscriber");

        subscriber.awaitTerminated();
      } catch (IllegalStateException e) {
        
        LOG.info("Subscriber failed - reconnecting", e);
        
        if (subscriber != null) {
          LOG.info("Stopping subscriber");
          subscriber.stopAsync();
        }
      }
      try {
        Thread.sleep(reconnectPeriodSec * 1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  private void addShutdownHook() {
    try {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        LOG.info("Calling Runtime shutdownhook");
        teardown();
      }));
      LOG.info("Shutdown-hook to clean up Google Pubsub subscription has been added.");
    } catch (IllegalStateException e) {
      // Handling cornercase when instance is being shut down before it has been initialized
      LOG.info("Instance is already shutting down - cleaning up immediately.", e);
      teardown();
    }
  }

  @PreDestroy
  public void teardown() {
    if (subscriptionAdminClient != null) {
      LOG.info("Deleting subscription {}", projectSubscriptionName);
      subscriptionAdminClient.deleteSubscription(projectSubscriptionName);
      LOG.info("Subscription deleted {}", projectSubscriptionName);
    } else {
      LOG.info("Nothing to clean up");
    }
  }

  private class VehicleMonitoringReceiver implements MessageReceiver {

    @Override
    public void receiveMessage(
        PubsubMessage pubsubMessage, AckReplyConsumer ackReplyConsumer
    ) {

      try {
        vehicleRepository.add(
                JsonReader.readVehicleActivity(pubsubMessage.getData().toStringUtf8())
        );
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    }
  }
}
