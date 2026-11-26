package org.entur.vehicles.graphql.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Apigee drops a WebSocket connection that carries no traffic for 55 s, without a close frame.
 * In prd a third of all connections ended at exactly 55 s: clients whose subscription matched
 * nothing for that long reconnected about once a minute, and were sent the whole initial
 * snapshot again each time. The server must ping idle connections well within 55 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // src/test/resources/application.properties replaces the main one, which sets these
                "spring.graphql.websocket.path=/subscriptions",
                "spring.graphql.websocket.keep-alive=300ms"})
class WebSocketKeepAliveTest {

    /** Apigee's idle timeout for the connection to this service */
    private static final Duration GATEWAY_IDLE_TIMEOUT = Duration.ofSeconds(55);

    private static final Pattern KEEP_ALIVE =
            Pattern.compile("^\\s*spring\\.graphql\\.websocket\\.keep-alive\\s*=\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    @LocalServerPort
    int port;

    @Test
    void anIdleConnectionIsPingedAndStaysOpen() throws Exception {
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        CompletableFuture<String> pinged = new CompletableFuture<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        TextWebSocketHandler client = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("{\"type\":\"connection_init\",\"payload\":{}}"));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                if (payload.contains("\"connection_ack\"")) {
                    acknowledged.complete(null);
                } else if (payload.contains("\"ping\"")) {
                    pinged.complete(payload);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closed.complete(status);
            }
        };
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of("graphql-transport-ws"));
        WebSocketSession session = new StandardWebSocketClient()
                .execute(client, headers, URI.create("ws://localhost:" + port + "/subscriptions"))
                .get(10, TimeUnit.SECONDS);
        try {
            acknowledged.get(10, TimeUnit.SECONDS);
            // No subscription, nothing sent: only the keep-alive can produce a message
            assertThat(pinged.get(5, TimeUnit.SECONDS)).contains("\"type\":\"ping\"");
            assertThat(closed).isNotDone();
            assertThat(session.isOpen()).isTrue();
        } finally {
            session.close();
        }
    }

    @Test
    void deployedAndLocalConfigurationPingWithinTheGatewayIdleTimeout() throws IOException {
        // Deployed pods read only the ConfigMap, local runs only application.properties
        for (Path file : List.of(Path.of("helm/vehicle-positions-2/templates/configmap.yaml"),
                Path.of("src/main/resources/application.properties"))) {
            Matcher matcher = KEEP_ALIVE.matcher(Files.readString(file));
            assertThat(matcher.find()).as("%s sets spring.graphql.websocket.keep-alive", file).isTrue();
            Duration keepAlive = DurationStyle.detectAndParse(matcher.group(1));
            assertThat(keepAlive).as(file.toString())
                    .isPositive()
                    .isLessThanOrEqualTo(GATEWAY_IDLE_TIMEOUT.dividedBy(2));
        }
    }
}
