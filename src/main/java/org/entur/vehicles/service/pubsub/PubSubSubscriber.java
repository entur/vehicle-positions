package org.entur.vehicles.service.pubsub;

import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.protobuf.Duration;
import com.google.pubsub.v1.DeleteSubscriptionRequest;
import com.google.pubsub.v1.ExpirationPolicy;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class PubSubSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(PubSubSubscriber.class.getName());
  private final int reconnectPeriodSec = 5;
  private final int parallelPullCount;
  private final int executorThreadCount;

  /*
   * Teardown budget. The three subscribers (VM, ET, SX) tear down one after another in
   * @PreDestroy, after the preStop sleep, within terminationGracePeriodSeconds. Every step is
   * bounded so that a slow or hanging call can neither stop the remaining subscriptions from
   * being deleted nor run the pod into SIGKILL: an undeleted subscription keeps collecting the
   * full feed for 24 hours (its expiration TTL).
   */
  long subscriberStopTimeoutMillis = 3_000;
  long deleteTimeoutMillis = 5_000;

  private final SubscriptionAdminClient subscriptionAdminClient;

  private final TopicName topic;
  private final SubscriptionName projectSubscriptionName;
  private final Map<String, String> appLabels = new HashMap<>();

  private final boolean enabled;
  private final MessageReceiver receiver;

  private final AtomicBoolean tornDown = new AtomicBoolean();
  private volatile boolean stopping;
  private volatile Subscriber subscriber;
  private ExecutorService subscribeExecutor;

  public PubSubSubscriber(String subscriptionProjectName,
                          String subscriptionName,
                          String topicProjectName,
                          String topicName,
                          int parallelPullCount,
                          int executorThreadCount,
                          Map<String, String> appLabels,
                          MessageReceiver receiver,
                          boolean enabled) {
    this(subscriptionProjectName, subscriptionName, topicProjectName, topicName, parallelPullCount,
            executorThreadCount, appLabels, receiver, enabled, createAdminClient());
  }

  PubSubSubscriber(String subscriptionProjectName,
                   String subscriptionName,
                   String topicProjectName,
                   String topicName,
                   int parallelPullCount,
                   int executorThreadCount,
                   Map<String, String> appLabels,
                   MessageReceiver receiver,
                   boolean enabled,
                   SubscriptionAdminClient subscriptionAdminClient) {
    this.parallelPullCount = parallelPullCount;
    this.executorThreadCount = executorThreadCount;

    projectSubscriptionName = SubscriptionName.of(subscriptionProjectName, subscriptionName);
    topic = TopicName.of(topicProjectName, topicName);
    this.appLabels.putAll(appLabels);

    if (System.getenv("HOSTNAME") != null) {
      this.appLabels.put("pod", System.getenv("HOSTNAME"));
    }

    this.subscriptionAdminClient = subscriptionAdminClient;
    this.enabled = enabled;
    this.receiver = receiver;
  }

  private static SubscriptionAdminClient createAdminClient() {
    try {
      return SubscriptionAdminClient.create();
    } catch (IOException e) {
      LOG.error("Failed to create Pub/Sub subscription admin client", e);
      return null;
    }
  }

  @PostConstruct
  public void startSubscriptionAsync() {
    if (!enabled) {
      LOG.info("Pubsub subscription {} is disabled.", projectSubscriptionName);
      return;
    }
    subscribeExecutor = Executors.newSingleThreadExecutor();
    subscribeExecutor.execute(this::subscribe);
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

    while (!stopping) {
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
        if (stopping) {
          break;
        }
        LOG.info("Subscriber failed - reconnecting", e);

        if (subscriber != null) {
          LOG.info("Stopping subscriber");
          subscriber.stopAsync();
        }
      }
      if (stopping) {
        break;
      }
      try {
        Thread.sleep(reconnectPeriodSec * 1000L);
      } catch (InterruptedException e) {
        if (stopping) {
          break;
        }
        LOG.error("Pub/Sub subscriber failed, attempting reconnection in {} seconds", reconnectPeriodSec, e);
      }
    }
    LOG.info("Subscriber loop for {} stopped", projectSubscriptionName);
  }

  /**
   * Stops pulling and deletes this pod's subscription. Runs once, never throws, and bounds every
   * step - see {@link #deleteTimeoutMillis}.
   */
  @PreDestroy
  public void teardown() {
    if (!tornDown.compareAndSet(false, true)) {
      return;
    }
    stopping = true;
    if (subscribeExecutor != null) {
      // Interrupts the reconnect sleep, so the loop cannot start a new subscriber after this
      subscribeExecutor.shutdownNow();
    }

    stopSubscriber();

    if (subscriptionAdminClient == null) {
      LOG.info("Nothing to clean up");
      return;
    }
    deleteSubscription();
    subscriptionAdminClient.shutdown();
  }

  private void stopSubscriber() {
    Subscriber current = subscriber;
    if (current == null) {
      return;
    }
    try {
      current.stopAsync().awaitTerminated(subscriberStopTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      LOG.warn("Subscriber for {} did not stop within {} ms - deleting its subscription anyway",
              projectSubscriptionName, subscriberStopTimeoutMillis);
    } catch (RuntimeException e) {
      // A failed subscriber is already stopped; awaitTerminated reports that as IllegalStateException
      LOG.info("Subscriber for {} had already failed: {}", projectSubscriptionName, e.toString());
    }
  }

  private void deleteSubscription() {
    LOG.info("Deleting subscription {}", projectSubscriptionName);
    try {
      subscriptionAdminClient.deleteSubscriptionCallable()
              .futureCall(DeleteSubscriptionRequest.newBuilder()
                      .setSubscription(projectSubscriptionName.toString())
                      .build())
              .get(deleteTimeoutMillis, TimeUnit.MILLISECONDS);
      LOG.info("Subscription deleted {}", projectSubscriptionName);
    } catch (TimeoutException e) {
      LOG.warn("Deleting subscription {} did not complete within {} ms; it expires after 24 hours unused",
              projectSubscriptionName, deleteTimeoutMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while deleting subscription {}", projectSubscriptionName);
    } catch (Exception e) {
      LOG.warn("Failed to delete subscription {}; it expires after 24 hours unused", projectSubscriptionName, e);
    }
  }
}
