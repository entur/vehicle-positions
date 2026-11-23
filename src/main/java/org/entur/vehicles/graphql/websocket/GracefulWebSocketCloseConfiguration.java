package org.entur.vehicles.graphql.websocket;

import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registers every GraphQL WebSocket session in {@link OpenWebSocketSessions}, and refuses new
 * handshakes with 503 once shutdown has begun.
 *
 * <p>Spring GraphQL offers no supported hook for open sessions: {@code WebSocketGraphQlInterceptor}
 * sees only session info, which cannot be closed. Spring Boot builds the endpoint by calling
 * {@link GraphQlWebSocketHandler#initWebSocketHttpRequestHandler} on its handler bean, and does
 * not back off if the application defines its own mapping. Rather than re-creating Boot's handler
 * (and its JSON converter, CORS and timeout set-up), this proxies the handler bean and puts a
 * session-tracking decorator in front of it in the request handler, keeping the handshake handler
 * and interceptors Spring GraphQL configured.
 *
 * <p>That relies on how Boot wires the endpoint. {@link #verifyGraphQlWebSocketsAreTracked} logs
 * an ERROR at startup if the endpoint ends up without the decorator - for instance after an
 * upgrade - and GracefulWebSocketCloseTest fails in the build. The application still starts:
 * shutdown then only goes back to dropping subscriptions without a close frame.
 */
@Configuration(proxyBeanMethods = false)
class GracefulWebSocketCloseConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(GracefulWebSocketCloseConfiguration.class);

    @Bean
    OpenWebSocketSessions openWebSocketSessions(
            @Value("${entur.vehicle-positions.websocket.shutdown.close-spacing:20ms}") Duration closeSpacing,
            @Value("${entur.vehicle-positions.websocket.shutdown.close-spread:5s}") Duration maxCloseSpread,
            @Value("${entur.vehicle-positions.websocket.shutdown.close-timeout:3s}") Duration closeTimeout) {
        return new OpenWebSocketSessions(closeSpacing, maxCloseSpread, closeTimeout);
    }

    /** Takes a provider, not the registry, so the registry is not created during BeanPostProcessor set-up. */
    @Bean
    static BeanPostProcessor trackGraphQlWebSocketSessions(ObjectProvider<OpenWebSocketSessions> sessions) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof GraphQlWebSocketHandler handler) {
                    return tracking(handler, sessions::getObject);
                }
                return bean;
            }
        };
    }

    @Bean
    static SmartInitializingSingleton verifyGraphQlWebSocketsAreTracked(ListableBeanFactory beanFactory) {
        return () -> {
            if (!isEndpointTracked(beanFactory)) {
                LOG.error("The GraphQL WebSocket endpoint does not track its sessions, so shutdown will drop every "
                        + "subscription without a close frame (1006) instead of closing it with 1001. Spring Boot or "
                        + "Spring GraphQL has probably changed how it builds the endpoint; see {}",
                        GracefulWebSocketCloseConfiguration.class.getName());
            }
        };
    }

    /** True if there is no GraphQL WebSocket endpoint, or it tracks its sessions */
    static boolean isEndpointTracked(ListableBeanFactory beanFactory) {
        if (beanFactory.getBeanNamesForType(GraphQlWebSocketHandler.class, true, false).length == 0) {
            return true;
        }
        return beanFactory.getBeansOfType(WebSocketHandlerMapping.class, true, false).values().stream()
                .flatMap(mapping -> mapping.getUrlMap().values().stream())
                .filter(WebSocketHttpRequestHandler.class::isInstance)
                .map(handler -> ((WebSocketHttpRequestHandler) handler).getWebSocketHandler())
                .anyMatch(GracefulWebSocketCloseConfiguration::isTracking);
    }

    static boolean isTracking(WebSocketHandler handler) {
        WebSocketHandler current = handler;
        while (current instanceof WebSocketHandlerDecorator decorator) {
            if (decorator instanceof SessionTracking) {
                return true;
            }
            current = decorator.getDelegate();
        }
        return false;
    }

    static GraphQlWebSocketHandler tracking(GraphQlWebSocketHandler handler, Supplier<OpenWebSocketSessions> sessions) {
        ProxyFactory proxyFactory = new ProxyFactory(handler);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (result instanceof WebSocketHttpRequestHandler original
                    && "initWebSocketHttpRequestHandler".equals(invocation.getMethod().getName())) {
                SessionTracking tracking = new SessionTracking(handler, sessions);
                WebSocketHttpRequestHandler tracked = new WebSocketHttpRequestHandler(tracking, original.getHandshakeHandler());
                List<HandshakeInterceptor> interceptors = new ArrayList<>();
                interceptors.add(new RefuseDuringShutdown(tracking));
                interceptors.addAll(original.getHandshakeInterceptors());
                tracked.setHandshakeInterceptors(interceptors);
                return tracked;
            }
            return result;
        });
        return (GraphQlWebSocketHandler) proxyFactory.getProxy(handler.getClass().getClassLoader());
    }

    /**
     * Refuses new handshakes with 503 once shutdown has begun. A client that was just closed with
     * 1001 and reconnects to this pod (e.g. through a load balancer that still routes here) then
     * backs off and retries elsewhere, instead of being accepted and closed again in a loop.
     */
    record RefuseDuringShutdown(SessionTracking tracking) implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (tracking.sessions().isAcceptingConnections()) {
                return true;
            }
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }

    /** Spring's handshake still finds the GraphQL sub-protocols: it unwraps decorators. */
    static class SessionTracking extends WebSocketHandlerDecorator {

        private final Supplier<OpenWebSocketSessions> lookup;
        private volatile OpenWebSocketSessions sessions;

        SessionTracking(WebSocketHandler delegate, Supplier<OpenWebSocketSessions> lookup) {
            super(delegate);
            this.lookup = lookup;
        }

        /** Resolved once, on first use; never during BeanPostProcessor set-up */
        OpenWebSocketSessions sessions() {
            OpenWebSocketSessions resolved = sessions;
            if (resolved == null) {
                resolved = lookup.get();
                sessions = resolved;
            }
            return resolved;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            super.afterConnectionEstablished(session);
            try {
                sessions().opened(session);
            } catch (RuntimeException e) {
                // Tracking is best effort; the subscription itself must not fail because of it
                LOG.warn("Could not register WebSocket session {} for closing on shutdown", session.getId(), e);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            try {
                sessions().closed(session);
            } catch (RuntimeException e) {
                LOG.debug("Could not unregister WebSocket session {}: {}", session.getId(), e.toString());
            } finally {
                // Spring GraphQL must always dispose the session's subscriptions
                super.afterConnectionClosed(session, closeStatus);
            }
        }
    }
}
