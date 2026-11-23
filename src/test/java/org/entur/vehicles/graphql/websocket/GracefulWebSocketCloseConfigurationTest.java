package org.entur.vehicles.graphql.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.LoggingWebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GracefulWebSocketCloseConfigurationTest {

    private final OpenWebSocketSessions registry =
            new OpenWebSocketSessions(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(1));

    private GracefulWebSocketCloseConfiguration.SessionTracking tracking(WebSocketHandler delegate) {
        return new GracefulWebSocketCloseConfiguration.SessionTracking(delegate, () -> registry);
    }

    private static DefaultListableBeanFactory endpointWith(WebSocketHandler handler) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("graphQlWebSocketHandler", mock(GraphQlWebSocketHandler.class));
        WebSocketHandlerMapping mapping = new WebSocketHandlerMapping();
        mapping.setUrlMap(Map.of("/subscriptions", new WebSocketHttpRequestHandler(handler, new DefaultHandshakeHandler())));
        beanFactory.registerSingleton("graphQlWebSocketMapping", mapping);
        return beanFactory;
    }

    /**
     * Tracking depends on how Spring Boot builds the endpoint. If that changes, it is reported -
     * and GracefulWebSocketCloseTest fails in the build - but the application still starts.
     */
    @Test
    void anEndpointWithoutTrackingIsReportedWithoutFailingStartup() {
        DefaultListableBeanFactory beanFactory = endpointWith(mock(WebSocketHandler.class));

        assertThat(GracefulWebSocketCloseConfiguration.isEndpointTracked(beanFactory)).isFalse();
        assertThatCode(() -> GracefulWebSocketCloseConfiguration.verifyGraphQlWebSocketsAreTracked(beanFactory)
                .afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    void anEndpointWithTrackingIsAccepted() {
        assertThat(GracefulWebSocketCloseConfiguration.isEndpointTracked(endpointWith(tracking(mock(WebSocketHandler.class)))))
                .isTrue();
    }

    @Test
    void noGraphQlWebSocketEndpointNeedsNoTracking() {
        assertThat(GracefulWebSocketCloseConfiguration.isEndpointTracked(new DefaultListableBeanFactory())).isTrue();
    }

    @Test
    void trackingIsFoundBehindOtherDecorators() {
        WebSocketHandler tracked = new LoggingWebSocketHandlerDecorator(tracking(mock(WebSocketHandler.class)));

        assertThat(GracefulWebSocketCloseConfiguration.isTracking(tracked)).isTrue();
        assertThat(GracefulWebSocketCloseConfiguration.isTracking(mock(WebSocketHandler.class))).isFalse();
    }

    @Test
    void handshakesAreRefusedWith503OnceShutdownBegins() {
        GracefulWebSocketCloseConfiguration.RefuseDuringShutdown refuse =
                new GracefulWebSocketCloseConfiguration.RefuseDuringShutdown(tracking(mock(WebSocketHandler.class)));
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, Object> attributes = new HashMap<>();

        ServerHttpResponse before = mock(ServerHttpResponse.class);
        assertThat(refuse.beforeHandshake(request, before, mock(WebSocketHandler.class), attributes)).isTrue();

        registry.stop();

        ServerHttpResponse after = mock(ServerHttpResponse.class);
        assertThat(refuse.beforeHandshake(request, after, mock(WebSocketHandler.class), attributes)).isFalse();
        verify(after).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Otherwise Spring GraphQL would never dispose the session's subscriptions */
    @Test
    void springGraphQlStillCleansUpWhenTheRegistryCannotBeReached() throws Exception {
        WebSocketHandler graphQl = mock(WebSocketHandler.class);
        GracefulWebSocketCloseConfiguration.SessionTracking tracking =
                new GracefulWebSocketCloseConfiguration.SessionTracking(graphQl, () -> {
                    throw new IllegalStateException("context is being destroyed");
                });
        WebSocketSession session = mock(WebSocketSession.class);

        tracking.afterConnectionEstablished(session);
        tracking.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(graphQl).afterConnectionEstablished(session);
        verify(graphQl).afterConnectionClosed(session, CloseStatus.NORMAL);
    }
}
