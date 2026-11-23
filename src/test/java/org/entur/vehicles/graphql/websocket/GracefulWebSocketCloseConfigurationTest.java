package org.entur.vehicles.graphql.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.LoggingWebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tracking depends on how Spring Boot builds the GraphQL WebSocket endpoint. If that changes, the
 * application must refuse to start rather than quietly go back to dropping subscriptions with 1006.
 */
class GracefulWebSocketCloseConfigurationTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OpenWebSocketSessions> sessions = mock(ObjectProvider.class);

    private static DefaultListableBeanFactory endpointWith(WebSocketHandler handler) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("graphQlWebSocketHandler", mock(GraphQlWebSocketHandler.class));
        WebSocketHandlerMapping mapping = new WebSocketHandlerMapping();
        mapping.setUrlMap(Map.of("/subscriptions", new WebSocketHttpRequestHandler(handler, new DefaultHandshakeHandler())));
        beanFactory.registerSingleton("graphQlWebSocketMapping", mapping);
        return beanFactory;
    }

    @Test
    void startupFailsIfTheEndpointDoesNotTrackSessions() {
        DefaultListableBeanFactory beanFactory = endpointWith(mock(WebSocketHandler.class));

        assertThatThrownBy(() -> GracefulWebSocketCloseConfiguration.verifyGraphQlWebSocketsAreTracked(beanFactory)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not track its sessions");
    }

    @Test
    void startupSucceedsWhenTheEndpointTracksSessions() {
        DefaultListableBeanFactory beanFactory = endpointWith(
                new GracefulWebSocketCloseConfiguration.SessionTracking(mock(WebSocketHandler.class), sessions));

        GracefulWebSocketCloseConfiguration.verifyGraphQlWebSocketsAreTracked(beanFactory).afterSingletonsInstantiated();
    }

    @Test
    void noGraphQlWebSocketEndpointNeedsNoTracking() {
        GracefulWebSocketCloseConfiguration.verifyGraphQlWebSocketsAreTracked(new DefaultListableBeanFactory())
                .afterSingletonsInstantiated();
    }

    @Test
    void trackingIsFoundBehindOtherDecorators() {
        WebSocketHandler tracked = new LoggingWebSocketHandlerDecorator(
                new GracefulWebSocketCloseConfiguration.SessionTracking(mock(WebSocketHandler.class), sessions));

        assertThat(GracefulWebSocketCloseConfiguration.isTracking(tracked)).isTrue();
        assertThat(GracefulWebSocketCloseConfiguration.isTracking(mock(WebSocketHandler.class))).isFalse();
    }
}
