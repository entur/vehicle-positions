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

    @Test
    void aHandshakeIsAcceptedWhenTheRegistryCannotBeReached() {
        GracefulWebSocketCloseConfiguration.RefuseDuringShutdown refuse =
                new GracefulWebSocketCloseConfiguration.RefuseDuringShutdown(
                        new GracefulWebSocketCloseConfiguration.SessionTracking(mock(WebSocketHandler.class), () -> {
                            throw new IllegalStateException("context is being destroyed");
                        }));

        assertThat(refuse.beforeHandshake(mock(ServerHttpRequest.class), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), new HashMap<>())).isTrue();
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
        org.mockito.Mockito.when(session.getId()).thenReturn("s1");

        tracking.afterConnectionEstablished(session);
        tracking.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(graphQl).afterConnectionEstablished(org.mockito.ArgumentMatchers.any(WebSocketSession.class));
        verify(graphQl).afterConnectionClosed(org.mockito.ArgumentMatchers.any(WebSocketSession.class),
                org.mockito.ArgumentMatchers.eq(CloseStatus.NORMAL));
    }

    @Test
    void aCloseWaitsForTheSendInProgress() throws Exception {
        WebSocketSession raw = mock(WebSocketSession.class);
        java.util.concurrent.CountDownLatch sending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishSend = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(i -> {
            sending.countDown();
            finishSend.await();
            order.add("send");
            return null;
        }).when(raw).sendMessage(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(i -> { order.add("close"); return null; })
                .when(raw).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
        GracefulWebSocketCloseConfiguration.SerializedCloseSession session =
                new GracefulWebSocketCloseConfiguration.SerializedCloseSession(raw);

        Thread sender = Thread.ofPlatform().start(() -> {
            try {
                session.sendMessage(new org.springframework.web.socket.TextMessage("update"));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        sending.await();
        Thread closer = Thread.ofPlatform().start(() -> {
            try {
                session.close(OpenWebSocketSessions.SHUTTING_DOWN);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(100);
        assertThat(order).as("the close must not overtake the send in progress").isEmpty();

        finishSend.countDown();
        sender.join(2_000);
        closer.join(2_000);
        assertThat(order).containsExactly("send", "close");
    }

    @Test
    void sendsAfterTheCloseAreDroppedAndTheSessionIsClosedOnce() throws Exception {
        WebSocketSession raw = mock(WebSocketSession.class);
        GracefulWebSocketCloseConfiguration.SerializedCloseSession session =
                new GracefulWebSocketCloseConfiguration.SerializedCloseSession(raw);

        session.close(OpenWebSocketSessions.SHUTTING_DOWN);
        session.sendMessage(new org.springframework.web.socket.TextMessage("late update"));
        session.close(new CloseStatus(1011));

        verify(raw, org.mockito.Mockito.never()).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(raw).close(OpenWebSocketSessions.SHUTTING_DOWN);
        org.mockito.Mockito.verifyNoMoreInteractions(raw);
    }

    @Test
    void springGraphQlAndTheRegistrySeeTheSameSerializedSession() throws Exception {
        WebSocketHandler graphQl = mock(WebSocketHandler.class);
        GracefulWebSocketCloseConfiguration.SessionTracking tracking = tracking(graphQl);
        WebSocketSession raw = mock(WebSocketSession.class);
        org.mockito.Mockito.when(raw.getId()).thenReturn("s1");
        org.mockito.Mockito.when(raw.isOpen()).thenReturn(true);
        org.springframework.web.socket.TextMessage message = new org.springframework.web.socket.TextMessage("{}");

        tracking.afterConnectionEstablished(raw);
        tracking.handleMessage(raw, message);
        registry.stop(); // closes what the registry holds

        org.mockito.ArgumentCaptor<WebSocketSession> established = org.mockito.ArgumentCaptor.forClass(WebSocketSession.class);
        verify(graphQl).afterConnectionEstablished(established.capture());
        assertThat(established.getValue()).isInstanceOf(GracefulWebSocketCloseConfiguration.SerializedCloseSession.class);
        verify(graphQl).handleMessage(established.getValue(), message);
        verify(raw).close(OpenWebSocketSessions.SHUTTING_DOWN);

        tracking.afterConnectionClosed(raw, OpenWebSocketSessions.SHUTTING_DOWN);
        verify(graphQl).afterConnectionClosed(established.getValue(), OpenWebSocketSessions.SHUTTING_DOWN);
    }
}
