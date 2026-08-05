package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import org.apache.avro.Schema;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.repository.SituationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bootstraps the situation store from a complete REST snapshot at startup.
 * <p>
 * The Pub/Sub topic carries updates, not state: a situation published before this
 * service started is never re-sent. Situations are long-lived - many carry no validity
 * end time at all - so a stream-only service has a permanently incomplete picture.
 * <p>
 * This runs before {@code PubSubSXSubscriber} is created (see its {@code @DependsOn}).
 * The ordering matters: {@code version} is null on the large majority of real
 * situations, so {@code SituationRepository}'s version guard cannot be relied on to
 * stop a late snapshot record from overwriting fresher streamed data.
 */
@Service
public class SituationSnapshotService {

    private static final Logger LOG = LoggerFactory.getLogger(SituationSnapshotService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Schema SITUATION_SCHEMA = PtSituationElementRecord.getClassSchema();

    /** The dev snapshot is ~10 MB; the shared Journey Planner client caps at 500 KB. */
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final SituationRepository situationRepository;
    private final String url;
    private final String etClientName;
    private final Duration timeout;
    private final boolean enabled;

    public SituationSnapshotService(
            @Autowired SituationRepository situationRepository,
            @Value("${vehicle.sx.snapshot.url}") String url,
            @Value("${vehicle.journeyplanner.EtClientName}") String etClientName,
            @Value("${vehicle.sx.snapshot.timeout:PT60S}") Duration timeout,
            @Value("${entur.vehicle-positions.sx.enabled:false}") boolean enabled) {
        this.situationRepository = situationRepository;
        this.url = url;
        this.etClientName = etClientName;
        this.timeout = timeout;
        this.enabled = enabled;
    }

    @PostConstruct
    public void loadSnapshot() {
        if (!enabled) {
            LOG.info("SX is disabled - skipping situation snapshot.");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            String body = fetch();
            int loaded = load(body);
            LOG.info("Loaded {} situations from snapshot in {} ms",
                    loaded, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            // Non-fatal by design: the service still starts and serves the Pub/Sub stream,
            // it just begins with an incomplete set until producers republish.
            LOG.error("Failed to load situation snapshot from {} - continuing without it.", url, e);
        }
    }

    private String fetch() {
        int timeoutMillis = (int) timeout.toMillis();
        WebClient webClient = WebClient.builder()
                .defaultHeader("ET-Client-Name", etClientName)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                                .doOnConnected(connection -> {
                                    connection.addHandlerLast(
                                            new ReadTimeoutHandler(timeoutMillis, TimeUnit.MILLISECONDS));
                                    connection.addHandlerLast(
                                            new WriteTimeoutHandler(timeoutMillis, TimeUnit.MILLISECONDS));
                                })))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();

        return webClient.get()
                .uri(url)
                // Without this header the endpoint returns SIRI XML.
                .header("Accept", "application/avro+json")
                .retrieve()
                .bodyToMono(String.class)
                .block(timeout);
    }

    /**
     * Parses a snapshot response body and loads every situation it holds.
     *
     * @return the number of situations successfully loaded
     */
    int load(String body) {
        JsonNode deliveries;
        try {
            JsonNode serviceDelivery = MAPPER.readTree(body).path("serviceDelivery");
            deliveries = serviceDelivery.path("situationExchangeDeliveries");
        } catch (Exception e) {
            LOG.error("Could not parse situation snapshot response.", e);
            return 0;
        }

        int loaded = 0;
        int skipped = 0;
        for (JsonNode delivery : deliveries) {
            for (JsonNode situation : delivery.path("situations")) {
                if (addSituation(situation)) {
                    loaded++;
                } else {
                    skipped++;
                }
            }
        }

        if (skipped > 0) {
            LOG.warn("Skipped {} unparseable situations in the snapshot.", skipped);
        }
        return loaded;
    }

    private boolean addSituation(JsonNode situation) {
        try {
            PtSituationElementRecord record = JsonReader.readPtSituationElement(
                    MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA)));
            situationRepository.add(record);
            return true;
        } catch (Exception e) {
            // One malformed situation must not discard the rest of the snapshot.
            LOG.warn("Ignoring unparseable situation {} in snapshot.",
                    situation.path("situationNumber").asText("<unknown>"), e);
            return false;
        }
    }
}
