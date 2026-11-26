package org.entur.vehicles.graphql.websocket;

import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.OperationDefinition;
import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * graphql-java 25 kept a data-loader call stack for every event of a subscription until the
 * subscription ended: about 1 KB per event. On 2026-11-26 those were 3.5 GB of a prd pod's
 * 4.3 GB live heap after 18 hours, and at the morning peak the pod spent 12 minutes in
 * back-to-back full GCs, ten minutes behind on Pub/Sub. An event's state must be released once
 * the event has been sent.
 *
 * <p>The state is internal to graphql-java, so this reads it by reflection. If a graphql-java
 * upgrade moves it, this test fails on the reflection, not silently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.graphql.websocket.path=/subscriptions")
class SubscriptionEventRetentionTest {

    private static final int EVENTS = 500;

    private static final String VEHICLES =
            "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":"
                    + "\"subscription { vehicles(bufferSize: 1, bufferTime: 10) { vehicleId } }\"}}";

    /** The ExecutionContext of the last subscription operation started */
    static final AtomicReference<ExecutionContext> SUBSCRIPTION = new AtomicReference<>();

    @TestConfiguration
    static class CaptureSubscriptionContext {
        @Bean
        SimplePerformantInstrumentation captureSubscriptionContext() {
            return new SimplePerformantInstrumentation() {
                @Override
                public InstrumentationContext<graphql.ExecutionResult> beginExecuteOperation(
                        InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
                    ExecutionContext context = parameters.getExecutionContext();
                    if (context.getOperationDefinition().getOperation() == OperationDefinition.Operation.SUBSCRIPTION) {
                        SUBSCRIPTION.set(context);
                    }
                    return super.beginExecuteOperation(parameters, state);
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    VehicleUpdateRxPublisher vehiclePublisher;

    @Test
    void eventsAreNotRetainedForTheLifetimeOfTheSubscription() throws Exception {
        AtomicInteger events = new AtomicInteger();
        TextWebSocketHandler client = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                if (message.getPayload().contains("connection_ack")) {
                    session.sendMessage(new TextMessage(VEHICLES));
                } else if (message.getPayload().contains("\"next\"")) {
                    events.incrementAndGet();
                }
            }
        };
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of("graphql-transport-ws"));
        WebSocketSession session = new StandardWebSocketClient()
                .execute(client, headers, URI.create("ws://localhost:" + port + "/subscriptions"))
                .get(10, TimeUnit.SECONDS);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int published = 0;
            // One update per buffer window, so each becomes its own subscription event
            while (events.get() < EVENTS && System.nanoTime() < deadline) {
                VehicleUpdate update = new VehicleUpdate();
                update.setCodespace(Codespace.getCodespace("TST"));
                update.setVehicleId("vehicle-" + published++);
                vehiclePublisher.publishUpdate(update);
                Thread.sleep(15);
            }
            assertThat(events.get()).as("subscription events received").isGreaterThanOrEqualTo(EVENTS);

            Object strategy = SUBSCRIPTION.get().getDataLoaderDispatcherStrategy();
            Field field = strategy.getClass().getDeclaredField("alternativeCallContextMap");
            field.setAccessible(true);
            Map<?, ?> perEventState = (Map<?, ?>) field.get(strategy);

            // At most the few events still in flight; with the leak, one per event sent
            assertThat(perEventState.size())
                    .as("per-event state held by %s after %d events", strategy.getClass().getSimpleName(), events.get())
                    .isLessThan(20);
        } finally {
            session.close();
        }
    }
}
