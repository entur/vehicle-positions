package org.entur.vehicles.graphql;

import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.entur.vehicles.graphql.Constants.CLIENT_HEADER_KEY;
import static org.entur.vehicles.graphql.Constants.TRACING_HEADER_NAME;

@Component
class HeaderWebsocketInterceptor implements WebSocketGraphQlInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(HeaderWebsocketInterceptor.class);


    @Value("${entur.vehicle-positions.client.name.header.name:Et-Client-Name}")
    private String CLIENT_HEADER_NAME;

    @Autowired
    PrometheusMetricsService metricsService;

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        MDC.put(TRACING_HEADER_NAME, UUID.randomUUID().toString());
        String header = request.getHeaders().getFirst(CLIENT_HEADER_NAME);
        MDC.put(CLIENT_HEADER_KEY, header != null ? header : "");

        return chain.next(request).doOnNext(response -> {
            MDC.remove(CLIENT_HEADER_KEY);
            MDC.remove(TRACING_HEADER_NAME);
        });
    }

    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo sessionInfo, Map<String, Object> connectionInitPayload) {
        LOG.info("Subscription started");
        try {
            String header = sessionInfo.getHeaders().getFirst(CLIENT_HEADER_NAME);
            MDC.put(CLIENT_HEADER_KEY, header != null ? header : "");
            metricsService.markSubscriptionStarted();
        } finally {
            MDC.remove(CLIENT_HEADER_KEY);
        }

        return WebSocketGraphQlInterceptor.super.handleConnectionInitialization(sessionInfo, connectionInitPayload);
    }

    @Override
    public Mono<Void> handleCancelledSubscription(WebSocketSessionInfo sessionInfo, String subscriptionId) {
        LOG.info("Subscription cancelled properly.");

        return WebSocketGraphQlInterceptor.super.handleCancelledSubscription(sessionInfo, subscriptionId);
    }

    @Override
    public void handleConnectionClosed(WebSocketSessionInfo sessionInfo, int statusCode, Map<String, Object> connectionInitPayload) {
        LOG.info("Subscription closed.");
        metricsService.markSubscriptionEnded();
        WebSocketGraphQlInterceptor.super.handleConnectionClosed(sessionInfo, statusCode, connectionInitPayload);
    }
}
