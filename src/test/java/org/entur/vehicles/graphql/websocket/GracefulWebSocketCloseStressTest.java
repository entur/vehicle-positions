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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes race Spring GraphQL's own sends on the same session: it sends each subscription's
 * updates from its own threads, on the session the registry closes. Tomcat should make the close
 * frame wait for a message in progress; if it did not, clients would see 1006 (close failed) or
 * 1011 (the sender's error path) instead of 1001. This shuts down with many clients receiving a
 * steady stream of updates, every one sent on its own, and requires 1001 for all of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.graphql.websocket.path=/subscriptions",
                "entur.vehicle-positions.websocket.shutdown.close-spacing=5ms",
                "entur.vehicle-positions.websocket.shutdown.close-spread=5s",
                "entur.vehicle-positions.websocket.shutdown.close-timeout=3s"})
@DirtiesContext // the test stops the context
class GracefulWebSocketCloseStressTest {

    private static final int CLIENTS = 50;
    private static final int PUBLISHERS = 4;
    /** Each publisher sends this many updates per millisecond: ~40,000 updates/s in all */
    private static final int UPDATES_PER_TICK = 10;

    private static final String VEHICLES =
            "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":"
                    + "\"subscription { vehicles(bufferSize: 1, bufferTime: 5) { vehicleId } }\"}}";

    @LocalServerPort
    int port;

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    VehicleUpdateRxPublisher vehiclePublisher;

    private class Client extends TextWebSocketHandler {
        final CompletableFuture<Void> receiving = new CompletableFuture<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        Client connect() throws Exception {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.setSecWebSocketProtocol(List.of("graphql-transport-ws"));
            new StandardWebSocketClient()
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
                session.sendMessage(new TextMessage(VEHICLES));
            } else if (payload.contains("\"next\"")) {
                receiving.complete(null);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }
    }

    @Test
    void everyActiveSubscriptionIsClosedWithGoingAwayUnderLoad() throws Exception {
        AtomicBoolean publishing = new AtomicBoolean(true);
        List<Thread> publishers = new ArrayList<>();
        for (int p = 0; p < PUBLISHERS; p++) {
            int offset = p * 1_000;
            publishers.add(Thread.ofPlatform().daemon().start(() -> {
                int i = 0;
                while (publishing.get()) {
                    for (int n = 0; n < UPDATES_PER_TICK; n++) {
                        VehicleUpdate update = new VehicleUpdate();
                        update.setCodespace(Codespace.getCodespace("TST"));
                        update.setVehicleId("vehicle-" + (offset + i++ % 500));
                        vehiclePublisher.publishUpdate(update);
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }));
        }
        try {
            List<Client> clients = new ArrayList<>();
            for (int i = 0; i < CLIENTS; i++) {
                clients.add(new Client().connect());
            }
            for (Client client : clients) {
                client.receiving.get(20, TimeUnit.SECONDS);
            }

            context.stop();

            Map<Integer, Integer> codes = new TreeMap<>();
            for (Client client : clients) {
                codes.merge(client.closed.get(15, TimeUnit.SECONDS).getCode(), 1, Integer::sum);
            }
            assertThat(codes).as("close codes received by %d clients under load", CLIENTS)
                    .containsExactly(Map.entry(CloseStatus.GOING_AWAY.getCode(), CLIENTS));
        } finally {
            publishing.set(false);
            for (Thread publisher : publishers) {
                publisher.join(5_000);
            }
        }
    }
}
