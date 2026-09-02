package org.entur.vehicles.service.snapshot;

import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SnapshotConfiguration {

    @Bean
    public ExportDownloader exportDownloader() {
        return new ExportDownloader();
    }

    @Bean // close() is @PreDestroy on the class; Spring runs it on context shutdown
    public SnapshotCache snapshotCache(@Value("${vehicle.snapshot.uri:}") String uri, PrometheusMetricsService metrics) {
        return SnapshotCache.fromUri(uri, metrics);
    }
}
