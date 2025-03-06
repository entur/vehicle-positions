package org.entur.vehicles.service;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class JourneyPlannerGraphQLClient {

    private static final int HTTP_TIMEOUT_MILLISECONDS = 10000;

    private final WebClient webClient;

    public JourneyPlannerGraphQLClient(@Value("${vehicle.journeyplanner.url}") String graphQlUrl, @Value("${vehicle.journeyplanner.EtClientName}") String etClientNameHeader) {
        webClient = WebClient.builder()
                .baseUrl(graphQlUrl)
                .defaultHeader("ET-Client-Name", etClientNameHeader)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, HTTP_TIMEOUT_MILLISECONDS).doOnConnected(connection -> {
                    connection.addHandlerLast(new ReadTimeoutHandler(HTTP_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS));
                    connection.addHandlerLast(new WriteTimeoutHandler(HTTP_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS));
                })))
                .codecs(codecs -> codecs
                        .defaultCodecs()
                        .maxInMemorySize(500 * 1024))
                .build();
    }

    Data executeQuery(String query) {
        Response graphqlResponse = webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(query)
                .retrieve()
                .bodyToMono(Response.class)
                .block();

        return graphqlResponse == null ? null : graphqlResponse.data;
    }
}
/*
 * Internal wrapper-classes for GraphQL-response
 */

class Response {
    Data data;
    Response() {}
    public void setData(Data data) {
        this.data = data;
    }
}
class Data {
    Line line;
    List<Line> lines;
    ServiceJourney serviceJourney;
    DatedServiceJourney datedServiceJourney;

    Operator operator;

    List<Operator> operators;
    Data() {}
    public void setLine(Line line) {
        this.line = line;
    }

    public void setLines(List<Line> lines) {
        this.lines = lines;
    }

    public void setServiceJourney(ServiceJourney serviceJourney) {
        this.serviceJourney = serviceJourney;
    }

    public void setDatedServiceJourney(DatedServiceJourney datedServiceJourney) {
        this.datedServiceJourney = datedServiceJourney;
    }

    public void setOperator(Operator operator) {
        this.operator = operator;
    }

    public void setOperators(List<Operator> operators) {
        this.operators = operators;
    }
}