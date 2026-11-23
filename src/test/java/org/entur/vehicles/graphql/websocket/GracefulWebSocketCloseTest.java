package org.entur.vehicles.graphql.websocket;

import org.entur.vehicles.data.VehicleUpdate;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.graphql.publishers.VehicleUpdateRxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pod that shuts down used to drop its WebSocket subscriptions without a close frame: clients
 * saw an abnormal close (1006) or an I/O error and had to work out on their own that the stream
 * was gone. Shutdown must close each GraphQL WebSocket session with 1001 (going away), which
 * graphql-ws and similar clients treat as "reconnect now".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // src/test/resources/application.properties replaces the main one, which sets this
                "spring.graphql.websocket.path=/subscriptions",
                "entur.vehicle-positions.websocket.shutdown.close-spread=200ms",
                "entur.vehicle-positions.websocket.shutdown.close-timeout=5s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GracefulWebSocketCloseTest {

    private static final String VEHICLES =
            "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":"
                    + "\"subscription { vehicles(bufferSize: 1, bufferTime: 20) { vehicleId } }\"}}";

    @LocalServerPort
    int port;

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    VehicleUpdateRxPublisher vehiclePublisher;

    /** A graphql-transport-ws client that optionally subscribes to vehicles once acknowledged */
    private class Client extends TextWebSocketHandler {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private final boolean subscribe;
        WebSocketSession session;

        Client(boolean subscribe) {
            this.subscribe = subscribe;
        }

        Client connect() throws Exception {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.setSecWebSocketProtocol(List.of("graphql-transport-ws"));
            session = new StandardWebSocketClient()
                    .execute(this, headers, URI.create("ws://localhost:" + port + "/subscriptions"))
                    .get(10, TimeUnit.SECONDS);
            return this;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String payload = message.getPayload();
            if (payload.contains("connection_ack")) {
                if (subscribe) {
                    session.sendMessage(new TextMessage(VEHICLES));
                } else {
                    ready.complete(null);
                }
            } else if (payload.contains("\"next\"")) {
                ready.complete(null); // receiving vehicle updates
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }
    }

    @Test
    void shutdownClosesAnIdleGraphQlWebSocketWithGoingAway() throws Exception {
        Client client = new Client(false).connect();
        client.ready.get(10, TimeUnit.SECONDS);

        // What SIGTERM triggers: every SmartLifecycle bean stops, the web server last
        context.stop();

        assertThat(client.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
        assertThat(client.session.isOpen()).isFalse();
    }

    @Test
    void shutdownClosesActiveSubscriptionsWithGoingAwayWhileUpdatesFlow() throws Exception {
        AtomicBoolean publishing = new AtomicBoolean(true);
        Thread publisher = Thread.ofPlatform().daemon().start(() -> {
            int i = 0;
            while (publishing.get()) {
                VehicleUpdate update = new VehicleUpdate();
                update.setCodespace(Codespace.getCodespace("TST"));
                update.setVehicleId("vehicle-" + (i++ % 50));
                vehiclePublisher.publishUpdate(update);
                Thread.onSpinWait();
            }
        });
        try {
            List<Client> clients = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                clients.add(new Client(true).connect());
            }
            for (Client client : clients) {
                client.ready.get(10, TimeUnit.SECONDS);
            }

            context.stop();

            for (Client client : clients) {
                assertThat(client.closed.get(10, TimeUnit.SECONDS).getCode())
                        .as("close code while vehicle updates were still being sent")
                        .isEqualTo(CloseStatus.GOING_AWAY.getCode());
            }
        } finally {
            publishing.set(false);
            publisher.join(5_000);
        }
    }
}
