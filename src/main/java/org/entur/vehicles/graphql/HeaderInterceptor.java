package org.entur.vehicles.graphql;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static org.entur.vehicles.graphql.Constants.CLIENT_HEADER_KEY;

@Component
class HeaderInterceptor implements WebGraphQlInterceptor {

    @Value("${entur.vehicle-positions.client.name.header.name:Et-Client-Name}")
    private String CLIENT_HEADER_NAME;

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {

        String header = request.getHeaders().getFirst(CLIENT_HEADER_NAME);
        MDC.put(CLIENT_HEADER_KEY, header != null ? header : "");

        return chain.next(request).doOnNext(response -> {
            MDC.remove(CLIENT_HEADER_KEY);
        });
    }
}
