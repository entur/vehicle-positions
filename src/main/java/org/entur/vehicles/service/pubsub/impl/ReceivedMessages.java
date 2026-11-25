package org.entur.vehicles.service.pubsub.impl;

import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.pubsub.v1.PubsubMessage;
import io.micrometer.core.instrument.Timer;
import org.entur.vehicles.repository.helpers.Util;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;

/** Timestamps and timings the Pub/Sub receivers use for their metrics */
final class ReceivedMessages {

  private ReceivedMessages() {
  }

  /** When Anshar published the message, or null if Pub/Sub did not set it */
  static Instant publishedAt(PubsubMessage message) {
    if (!message.hasPublishTime()) {
      return null;
    }
    return Instant.ofEpochSecond(message.getPublishTime().getSeconds(), message.getPublishTime().getNanos());
  }

  /** A SIRI timestamp as an instant, or null when missing or unparseable */
  static Instant parseReportedTime(CharSequence reported) {
    if (reported == null) {
      return null;
    }
    try {
      return Util.convert(reported).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** Records how long {@code receiver} spends on each message, including when it throws */
  static MessageReceiver timed(Timer timer, MessageReceiver receiver) {
    return (message, consumer) -> {
      long start = System.nanoTime();
      try {
        receiver.receiveMessage(message, consumer);
      } finally {
        timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
      }
    };
  }
}
