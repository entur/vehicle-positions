package org.entur.vehicles.service;

import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.pubsub.v1.ExpirationPolicy;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.Subscription;
import org.entur.vehicles.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import uk.org.siri.www.siri.SiriType;
import uk.org.siri.www.siri.VehicleMonitoringDeliveryStructure;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@Service
public class PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubSubscriber.class.getName());
  private final int reconnectPeriodSec = 5;

  private VehicleRepository vehicleRepository;

  private SubscriptionAdminClient subscriptionAdminClient;

  private ProjectTopicName topic;
  private ProjectSubscriptionName projectSubscriptionName;
  private Map<String, String> appLabels = new HashMap<>();
  @Value("${entur.vehicle-positions.shutdownhook:false}")
  private boolean addManualShutdownhook;
  @Value("${entur.default.gcp.credentials.enabled:false}")
  private boolean defaultGcpCredentialsEnabled;

  public PubSubSubscriber(@Autowired VehicleRepository vehicleRepository,
                          @Value("${entur.vehicle-positions.gcp.project.name}") String projectName,
                          @Value("${entur.vehicle-positions.gcp.subscription.name}") String subscriptionName,
                          @Value("${entur.vehicle-positions.gcp.topic.name}") String topicName,
                          @Value("${entur.vehicle-positions.gcp.credentials.path}") String credentialsPath,
                          @Value("#{${entur.vehicle-positions.gcp.labels}}") Map<String, String> appLabels) {
    this.vehicleRepository = vehicleRepository;

    projectSubscriptionName = ProjectSubscriptionName.of(projectName, subscriptionName);
    topic = ProjectTopicName.of(projectName, topicName);
    this.appLabels.putAll(appLabels);

    if (System.getenv("HOSTNAME") != null) {
      this.appLabels.put("pod", System.getenv("HOSTNAME"));
    }

    try {
      // todo: refactor this code to used profiles
      if (defaultGcpCredentialsEnabled) {
        // used gcp default credentials such as work load identity
        LOG.info("Use default gcp credentials. ");
        subscriptionAdminClient = SubscriptionAdminClient.create();
      } else {

        if (System.getenv("GOOGLE_APPLICATION_CREDENTIALS") != null &&
                !System.getenv("GOOGLE_APPLICATION_CREDENTIALS").isEmpty()) {
          LOG.info("Credentials to be read from ENV-variable: {}", System.getenv("GOOGLE_APPLICATION_CREDENTIALS"));
          subscriptionAdminClient = SubscriptionAdminClient.create();
        } else {
          File credentialsFile = new File(credentialsPath);
          LOG.info(
                  "Credentials to be read from {}, exists: {}, can read: {}",
                  credentialsFile.getAbsolutePath(),
                  credentialsFile.exists(),
                  credentialsFile.canRead()
          );

          CredentialsProvider credentialsProvider = () -> GoogleCredentials
                  .fromStream(new FileInputStream(credentialsFile))
                  .createScoped(Lists.newArrayList(
                          "https://www.googleapis.com/auth/cloud-platform",
                          "https://www.googleapis.com/auth/pubsub"
                  ));

          subscriptionAdminClient = SubscriptionAdminClient.create(SubscriptionAdminSettings
                  .newBuilder()
                  .setCredentialsProvider(credentialsProvider)
                  .build());

          LOG.info("Credentials read from path: {}", credentialsPath);
        }
      }
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

      LOG.info("Created subscription {}", projectSubscriptionName);

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
        subscriber = Subscriber.newBuilder(subscription.getName(), receiver).build();
        LOG.info("Started subscriber");
        subscriber.startAsync().awaitRunning();

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

      SiriType siriType;
      try {
        final ByteString data = pubsubMessage.getData();

        siriType = SiriType.parseFrom(data);

      }
      catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }

      if (siriType.getServiceDelivery() != null) {
        // Handle trip updates via graph writer runnable
        if (siriType.getServiceDelivery().getVehicleMonitoringDeliveryCount() > 0) {
          final List<VehicleMonitoringDeliveryStructure> updateList = siriType
              .getServiceDelivery()
              .getVehicleMonitoringDeliveryList();

          for (VehicleMonitoringDeliveryStructure monitoringDeliveryStructure : updateList) {
            vehicleRepository.addAll(monitoringDeliveryStructure.getVehicleActivityList());
          }

        }

      }

      // Ack only after all work for the message is complete.
      ackReplyConsumer.ack();
    }
  }
}
