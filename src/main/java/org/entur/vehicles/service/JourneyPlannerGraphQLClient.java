package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.entur.vehicles.data.model.Line;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class JourneyPlannerGraphQLClient {

    private final String graphQlUrl;

    @Value("${vehicle.journeyplanner.EtClientName}")
    private String etClientNameHeader;

    public JourneyPlannerGraphQLClient(@Value("${vehicle.journeyplanner.url}") String graphQlUrl) {
        this.graphQlUrl = graphQlUrl;
    }


    @Nullable
    Data executeQuery(String query) throws IOException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(graphQlUrl);

        final StringEntity stringEntity = new StringEntity(query);
        stringEntity.setContentType("application/json");
        httppost.setEntity(stringEntity);

        httppost.setHeader("ET-Client-Name", etClientNameHeader);

        final CloseableHttpResponse response = httpclient.execute(httppost);

        if (response != null) {
            final HttpEntity entity = response.getEntity();
            final InputStream content = entity.getContent();
            final Response graphqlResponse = new ObjectMapper().readValue(content, Response.class);
            if (graphqlResponse != null) {
                return graphqlResponse.data;
            }
        }
        return null;
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
    Data() {}
    public void setLine(Line line) {
        this.line = line;
    }

    public void setLines(List<Line> lines) {
        this.lines = lines;
    }
}