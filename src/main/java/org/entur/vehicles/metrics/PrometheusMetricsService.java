/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package org.entur.vehicles.metrics;

import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.entur.vehicles.data.model.Codespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PrometheusMetricsService {
    private static final Logger LOG = LoggerFactory.getLogger(PrometheusMetricsService.class);

    private static final String METRICS_PREFIX = "app.vehicles.";
    private static final String DATA_COUNTER_NAME = METRICS_PREFIX + "data";
    private static final String CODESPACE_TAG_NAME = "codespaceId";
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    private int lastLoggedCount;
    private long lastLoggedCountTimeMillis = System.currentTimeMillis();

    private AtomicInteger counter = new AtomicInteger(0);

    public PrometheusMetricsService(@Autowired PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @PreDestroy
    public void shutdown() {
        prometheusMeterRegistry.close();
    }

    public void markUpdate(int count, Codespace codespace) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(CODESPACE_TAG_NAME, codespace.getCodespaceId()));

        prometheusMeterRegistry.counter(DATA_COUNTER_NAME, counterTags).increment(count);
        if (counter.addAndGet(count) % 1000 == 0) {
            final int currentCount = counter.get();

            LOG.debug("Processed {} updates. Current rate: {}/s", currentCount, calculateRate(currentCount));

        }
    }

    private long calculateRate(int currentCount) {
        long now = System.currentTimeMillis();

        final int updatesSinceLastTime = currentCount - lastLoggedCount;
        final long elapsedSinceLastTime = now - lastLoggedCountTimeMillis;

        final double elapsedTimeSeconds = Math.max(elapsedSinceLastTime / 1000, 0.1);

        final long rate = (long) (updatesSinceLastTime / elapsedTimeSeconds);

        lastLoggedCount = currentCount;
        lastLoggedCountTimeMillis = now;

        return rate;
    }

}
