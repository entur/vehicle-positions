package org.entur.vehicles.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.model.Codespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Clocks at the producer, Anshar, Pub/Sub and this pod are not in sync, so a latency can come out
 * slightly negative. Micrometer rejects those with a logged IllegalArgumentException, so they are
 * kept out of the timer and counted instead, so skewed producers stay visible.
 */
class PrometheusMetricsServiceLatencyTest {

    private PrometheusMeterRegistry registry;
    private PrometheusMetricsService metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new PrometheusMetricsService(registry);
    }

    @Test
    void negativeSourceLatencyIsSkipped() {
        metrics.recordSourceLatency("vehicle", Codespace.getCodespace("TST"), Duration.ofMillis(-1));

        assertNull(registry.find("app.vehicles.latency.source").timer());
        Counter skewed = registry.find("app.vehicles.latency.source.skewed")
                .tags("dataType", "vehicle", "codespaceId", "TST").counter();
        assertNotNull(skewed, "source skew counter not registered");
        assertEquals(1, skewed.count());
    }

    @Test
    void negativePubsubLatencyIsSkipped() {
        metrics.recordPubsubLatency("vehicle", Duration.ofMillis(-1));

        assertNull(registry.find("app.vehicles.latency.pubsub").timer());
        Counter skewed = registry.find("app.vehicles.latency.pubsub.skewed").tags("dataType", "vehicle").counter();
        assertNotNull(skewed, "pubsub skew counter not registered");
        assertEquals(1, skewed.count());
    }

    @Test
    void zeroLatencyIsRecorded() {
        metrics.recordSourceLatency("vehicle", Codespace.getCodespace("TST"), Duration.ZERO);

        Timer source = registry.find("app.vehicles.latency.source").timer();
        assertNotNull(source);
        assertEquals(1, source.count());
        assertNull(registry.find("app.vehicles.latency.source.skewed").counter());
    }
}
