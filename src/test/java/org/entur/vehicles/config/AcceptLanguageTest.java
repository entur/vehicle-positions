package org.entur.vehicles.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tomcat refuses to parse more than 10 Accept-Language entries by default, and Spring's
 * RequestContextFilter reads the locale on every request, so such a request failed with a 500
 * before reaching the application. Nothing here uses the locale, so the limit is raised to 50.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptLanguageTest {

    @LocalServerPort
    int port;

    @Test
    void requestWithManyAcceptLanguageEntriesIsServed() throws Exception {
        String acceptLanguage = IntStream.range(0, 30)
                .mapToObj(i -> "x" + (char) ('a' + i % 26) + (char) ('a' + i / 26) + ";q=0.5")
                .collect(Collectors.joining(","));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .header("Accept-Language", acceptLanguage)
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ __typename }\"}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("__typename");
    }
}
