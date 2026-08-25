package org.entur.vehicles.service.planned;

import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the current {@link PlannedDataset}. Loads it once at startup - blocking the Spring
 * context, and therefore readiness, until it is in place - and swaps in a fresh one from a
 * nightly reload. A failed startup load is fatal; a failed nightly reload keeps the
 * previous dataset.
 * <p>
 * The {@code find*} methods are the lookup surface the enrichment services use. They return
 * null on a miss and count it, so a producer referencing ids the export lacks is visible.
 */
@Service
public class PlannedDataService {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataService.class);

    private static final int DOWNLOAD_TIMEOUT_MILLIS = 60_000;

    private final boolean enabled;
    private final String url;
    private final PlannedDataLoader loader;
    private final PrometheusMetricsService metrics;
    private final AtomicReference<PlannedDataset> current = new AtomicReference<>(PlannedDataset.EMPTY);

    @Autowired
    public PlannedDataService(@Value("${vehicle.planned.data.enabled:false}") boolean enabled,
                              @Value("${vehicle.planned.data.url:https://storage.googleapis.com/marduk-production/outbound/netex/rb_norway-aggregated-netex.zip}") String url,
                              PlannedDataLoader loader,
                              PrometheusMetricsService metrics) {
        this.enabled = enabled;
        this.url = url;
        this.loader = loader;
        this.metrics = metrics;
    }

    /** A service that never loads and serves {@link PlannedDataset#EMPTY} - for tests. */
    public static PlannedDataService disabled() {
        return new PlannedDataService(false, null, null, null);
    }

    /** Runs a load with startup semantics (throws on failure). For tests outside this package. */
    public void reloadForTest() {
        initialLoad();
    }

    @PostConstruct
    void initialLoad() {
        if (!enabled) {
            LOG.info("Planned data disabled - lookups return bare refs");
            return;
        }
        try {
            load();
        } catch (PlannedDataLoadException e) {
            throw new IllegalStateException("Initial planned data load failed", e);
        }
    }

    @Scheduled(cron = "${vehicle.planned.data.reload.cron:0 0 8 * * *}", zone = "Europe/Oslo")
    void scheduledReload() {
        if (!enabled) {
            return;
        }
        try {
            load();
        } catch (PlannedDataLoadException e) {
            LOG.error("Planned data reload failed - keeping the current dataset", e);
        }
    }

    private void load() throws PlannedDataLoadException {
        long start = System.currentTimeMillis();
        Path zip;
        try {
            zip = Files.createTempFile("planned-netex", ".zip");
        } catch (IOException e) {
            if (metrics != null) {
                metrics.markPlannedDataLoadFailure();
            }
            throw new PlannedDataLoadException("Could not create temp file for planned data download", e);
        }
        try {
            download(url, zip);
            PlannedDataset fresh = loader.load(zip);
            PlannedDataset previous = current.get();
            if (isSuspiciouslySmall(fresh, previous)) {
                throw new PlannedDataLoadException("Fresh dataset has " + fresh.serviceJourneyCount()
                        + " service journeys, current has " + previous.serviceJourneyCount()
                        + " - rejecting as a truncated export");
            }
            current.set(fresh);
            long duration = System.currentTimeMillis() - start;
            if (metrics != null) {
                metrics.markPlannedDataLoaded(duration, fresh.stats());
            }
            LOG.info("Planned data loaded in {} ms: {}", duration, fresh.stats());
        } catch (PlannedDataLoadException e) {
            if (metrics != null) {
                metrics.markPlannedDataLoadFailure();
            }
            throw e;
        } finally {
            try {
                Files.deleteIfExists(zip);
            } catch (IOException e) {
                LOG.warn("Could not delete temp file {}", zip, e);
            }
        }
    }

    /**
     * A fresh dataset with fewer than half the current one's service journeys is far more
     * likely a truncated export than a real change in Norway's timetable. Never applies to
     * the first load, which has nothing to compare against.
     */
    static boolean isSuspiciouslySmall(PlannedDataset fresh, PlannedDataset previous) {
        if (previous.serviceJourneyCount() == 0) {
            return false;
        }
        return fresh.serviceJourneyCount() * 2 < previous.serviceJourneyCount();
    }

    private static void download(String url, Path target) throws PlannedDataLoadException {
        long start = System.currentTimeMillis();
        try {
            FileUtils.copyURLToFile(new URL(url), target.toFile(), DOWNLOAD_TIMEOUT_MILLIS, DOWNLOAD_TIMEOUT_MILLIS);
            LOG.info("Download of {} took {} ms", url, System.currentTimeMillis() - start);
        } catch (IOException e) {
            throw new PlannedDataLoadException("Could not download " + url, e);
        }
    }

    public PlannedDataset current() {
        return current.get();
    }

    public Line findLine(String lineRef) {
        Line line = current.get().line(lineRef);
        if (line == null) {
            miss("line");
        }
        return line;
    }

    public Operator findOperator(String operatorRef) {
        Operator operator = current.get().operator(operatorRef);
        if (operator == null) {
            miss("operator");
        }
        return operator;
    }

    public boolean hasServiceJourney(String serviceJourneyId) {
        boolean known = current.get().hasServiceJourney(serviceJourneyId);
        if (!known) {
            miss("serviceJourney");
        }
        return known;
    }

    /** Geometry for a service journey, or null if the journey or its geometry is unknown. Not miss-counted - use {@link #hasServiceJourney} for that. */
    public PointsOnLink findPointsOnLink(String serviceJourneyId) {
        PlannedDataset dataset = current.get();
        return dataset.pointsOnLink(dataset.journeyPatternOf(serviceJourneyId));
    }

    public DatedJourneyRef findDatedServiceJourney(String datedServiceJourneyId) {
        DatedJourneyRef ref = current.get().datedServiceJourney(datedServiceJourneyId);
        if (ref == null) {
            miss("datedServiceJourney");
        }
        return ref;
    }

    private void miss(String type) {
        if (metrics != null) {
            metrics.markPlannedDataLookupMiss(type);
        }
    }
}
