package org.entur.vehicles.graphql.websocket;

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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
                "entur.vehicle-positions.websocket.shutdown.close-spread=0ms"})
@DirtiesContext
class GracefulWebSocketCloseTest {

    @LocalServerPort
    int port;

    @Autowired
    ConfigurableApplicationContext context;

    @Test
    void shutdownClosesGraphQlWebSocketsWithGoingAway() throws Exception {
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of("graphql-transport-ws"));
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                if (message.getPayload().contains("connection_ack")) {
                    acknowledged.complete(null);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closed.complete(status);
            }
        }, headers, URI.create("ws://localhost:" + port + "/subscriptions")).get(10, TimeUnit.SECONDS);

        acknowledged.get(10, TimeUnit.SECONDS);

        // What SIGTERM triggers: every SmartLifecycle bean stops, the web server last
        context.stop();

        assertThat(closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
        assertThat(session.isOpen()).isFalse();
    }
}
