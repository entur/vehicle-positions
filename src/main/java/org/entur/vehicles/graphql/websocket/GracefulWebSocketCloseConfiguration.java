package org.entur.vehicles.graphql.websocket;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.time.Duration;

/**
 * Registers every GraphQL WebSocket session in {@link OpenWebSocketSessions}.
 *
 * <p>Spring GraphQL offers no supported hook for open sessions: {@code WebSocketGraphQlInterceptor}
 * sees only session info, which cannot be closed. Spring Boot builds the endpoint by calling
 * {@link GraphQlWebSocketHandler#initWebSocketHttpRequestHandler} on its handler bean. Rather than
 * re-creating that bean (and Boot's JSON converter, CORS and timeout set-up), this proxies it and
 * puts a session-tracking decorator in front of it in the request handler, keeping the handshake
 * handler and interceptors Spring GraphQL configured.
 *
 * <p>That relies on how Boot wires the endpoint, so {@link #verifyGraphQlWebSocketsAreTracked}
 * fails startup if the endpoint ends up without the decorator - for instance after an upgrade -
 * rather than letting shutdown silently go back to dropping every subscription with 1006.
 */
@Configuration(proxyBeanMethods = false)
class GracefulWebSocketCloseConfiguration {

    @Bean
    OpenWebSocketSessions openWebSocketSessions(
            @Value("${entur.vehicle-positions.websocket.shutdown.close-spread:5s}") Duration closeSpread,
            @Value("${entur.vehicle-positions.websocket.shutdown.close-timeout:5s}") Duration closeTimeout) {
        return new OpenWebSocketSessions(closeSpread, closeTimeout);
    }

    /** Takes a provider, not the registry, so the registry is not created during BeanPostProcessor set-up. */
    @Bean
    static BeanPostProcessor trackGraphQlWebSocketSessions(ObjectProvider<OpenWebSocketSessions> sessions) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof GraphQlWebSocketHandler handler) {
                    return tracking(handler, sessions);
                }
                return bean;
            }
        };
    }

    @Bean
    static SmartInitializingSingleton verifyGraphQlWebSocketsAreTracked(ListableBeanFactory beanFactory) {
        return () -> {
            if (beanFactory.getBeanNamesForType(GraphQlWebSocketHandler.class, true, false).length == 0) {
                return; // No GraphQL WebSocket endpoint configured
            }
            boolean tracked = beanFactory.getBeansOfType(WebSocketHandlerMapping.class, true, false).values().stream()
                    .flatMap(mapping -> mapping.getUrlMap().values().stream())
                    .filter(WebSocketHttpRequestHandler.class::isInstance)
                    .map(handler -> ((WebSocketHttpRequestHandler) handler).getWebSocketHandler())
                    .anyMatch(GracefulWebSocketCloseConfiguration::isTracking);
            if (!tracked) {
                throw new IllegalStateException("The GraphQL WebSocket endpoint does not track its sessions, so "
                        + "shutdown would drop every subscription without a close frame. Spring Boot or Spring "
                        + "GraphQL has probably changed how it builds the endpoint; see "
                        + GracefulWebSocketCloseConfiguration.class.getName());
            }
        };
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

    static GraphQlWebSocketHandler tracking(GraphQlWebSocketHandler handler, ObjectProvider<OpenWebSocketSessions> sessions) {
        ProxyFactory proxyFactory = new ProxyFactory(handler);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (result instanceof WebSocketHttpRequestHandler original
                    && "initWebSocketHttpRequestHandler".equals(invocation.getMethod().getName())) {
                WebSocketHttpRequestHandler tracked = new WebSocketHttpRequestHandler(
                        new SessionTracking(handler, sessions), original.getHandshakeHandler());
                tracked.setHandshakeInterceptors(original.getHandshakeInterceptors());
                return tracked;
            }
            return result;
        });
        return (GraphQlWebSocketHandler) proxyFactory.getProxy(handler.getClass().getClassLoader());
    }

    /** Spring's handshake still finds the GraphQL sub-protocols: it unwraps decorators. */
    static class SessionTracking extends WebSocketHandlerDecorator {

        private final ObjectProvider<OpenWebSocketSessions> sessions;

        SessionTracking(WebSocketHandler delegate, ObjectProvider<OpenWebSocketSessions> sessions) {
            super(delegate);
            this.sessions = sessions;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            super.afterConnectionEstablished(session);
            sessions.getObject().opened(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            sessions.getObject().closed(session);
            super.afterConnectionClosed(session, closeStatus);
        }
    }
}
