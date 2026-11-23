package org.entur.vehicles.graphql.websocket;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.time.Duration;

/**
 * Registers every GraphQL WebSocket session in {@link OpenWebSocketSessions}.
 *
 * <p>Spring Boot builds the WebSocket endpoint by calling
 * {@link GraphQlWebSocketHandler#initWebSocketHttpRequestHandler} on its handler bean, and that
 * request handler is handed the GraphQL handler itself, which exposes no hook for open sessions.
 * Rather than re-creating Boot's handler bean (and its JSON converter, CORS and timeout set-up),
 * this proxies the bean and puts a session-tracking decorator in front of it in the request
 * handler, keeping the handshake handler and interceptors Spring GraphQL configured.
 */
@Configuration(proxyBeanMethods = false)
class GracefulWebSocketCloseConfiguration {

    @Bean
    OpenWebSocketSessions openWebSocketSessions(
            @Value("${entur.vehicle-positions.websocket.shutdown.close-spread:5s}") Duration closeSpread) {
        return new OpenWebSocketSessions(closeSpread);
    }

    @Bean
    static BeanPostProcessor trackGraphQlWebSocketSessions(ObjectProvider<OpenWebSocketSessions> sessions) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof GraphQlWebSocketHandler handler) {
                    return tracking(handler, sessions.getObject());
                }
                return bean;
            }
        };
    }

    static GraphQlWebSocketHandler tracking(GraphQlWebSocketHandler handler, OpenWebSocketSessions sessions) {
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

        private final OpenWebSocketSessions sessions;

        SessionTracking(WebSocketHandler delegate, OpenWebSocketSessions sessions) {
            super(delegate);
            this.sessions = sessions;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            sessions.opened(session);
            super.afterConnectionEstablished(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            sessions.closed(session);
            super.afterConnectionClosed(session, closeStatus);
        }
    }
}
