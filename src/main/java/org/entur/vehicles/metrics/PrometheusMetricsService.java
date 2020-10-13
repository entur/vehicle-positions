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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

@Service
public class PrometheusMetricsService {

    private static final String METRICS_PREFIX = "app.vehicles.";
    private static final String DATA_COUNTER_NAME = METRICS_PREFIX + "data";
    private static final String CODESPACE_TAG_NAME = "codespaceId";
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public PrometheusMetricsService(@Autowired PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @PreDestroy
    public void shutdown() {
        prometheusMeterRegistry.close();
    }

    public void markUpdate(int count, String codespaceId) {
        List<Tag> counterTags = new ArrayList<>();
        counterTags.add(new ImmutableTag(CODESPACE_TAG_NAME, codespaceId));

        prometheusMeterRegistry.counter(DATA_COUNTER_NAME, counterTags).increment(count);
    }

}
