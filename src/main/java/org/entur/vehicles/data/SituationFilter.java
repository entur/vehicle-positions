package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.StringJoiner;

import static org.entur.vehicles.data.MetricType.UNDEFINED;

/**
 * Filter for situations.
 * <p>
 * Kept separate from {@code QueryFilter} because SX matching tests membership in
 * collections of affected objects rather than equality against single values.
 * Buffer settings are duplicated rather than shared: {@code QueryFilter} extends
 * {@code AbstractUpdate}, so a common base class is not possible without
 * restructuring the update hierarchy.
 */
@SchemaMapping
public class SituationFilter {

    private static final int DEFAULT_BUFFER_SIZE = 20;
    private static final int DEFAULT_BUFFER_TIME_MILLIS = 250;

    private final PrometheusMetricsService metricsService;
    private final MetricType metricType;

    private final Set<String> situationNumbers;
    private final Codespace codespace;
    private final String operatorRef;
    private final String lineRef;
    /**
     * The queried stop ref together with every ancestor above it, already resolved by the
     * caller. Null means no stop filter at all - an empty set would mean "match nothing",
     * which is not the same thing.
     */
    private final Set<String> stopRefs;
    private final String serviceJourneyId;
    private final String datedServiceJourneyId;
    private final VehicleModeEnumeration mode;
    private final SeverityEnumeration severity;
    private final String reportType;
    private final Boolean validNow;
    private final Boolean openEnded;
    private final Duration minAge;
    private final boolean includeClosed;

    private int bufferSize = DEFAULT_BUFFER_SIZE;
    private int bufferTimeMillis = DEFAULT_BUFFER_TIME_MILLIS;

    public SituationFilter(PrometheusMetricsService metricsService,
                           MetricType metricType,
                           Set<String> situationNumbers,
                           String codespaceId,
                           String operatorRef,
                           String lineRef,
                           Set<String> stopRefs,
                           String serviceJourneyId,
                           String datedServiceJourneyId,
                           VehicleModeEnumeration mode,
                           SeverityEnumeration severity,
                           String reportType,
                           Boolean validNow,
                           Boolean openEnded,
                           Duration minAge,
                           Boolean includeClosed,
                           Integer bufferSize,
                           Integer bufferTimeMillis) {
        this.metricsService = metricsService;
        this.metricType = metricType != null ? metricType : UNDEFINED;
        this.situationNumbers = situationNumbers;
        this.codespace = codespaceId != null ? Codespace.getCodespace(codespaceId) : null;
        this.operatorRef = operatorRef;
        this.lineRef = lineRef;
        this.stopRefs = stopRefs;
        this.serviceJourneyId = serviceJourneyId;
        this.datedServiceJourneyId = datedServiceJourneyId;
        this.mode = mode;
        this.severity = severity;
        this.reportType = reportType;
        this.validNow = validNow;
        this.openEnded = openEnded;
        this.minAge = minAge;
        this.includeClosed = includeClosed != null && includeClosed;

        if (bufferSize != null) {
            this.bufferSize = bufferSize;
        }
        if (bufferTimeMillis != null) {
            this.bufferTimeMillis = bufferTimeMillis;
        }
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferTimeMillis() {
        return bufferTimeMillis;
    }

    public boolean isMatch(SituationUpdate situation) {

        if (!includeClosed && situation.getProgress() != null && situation.getProgress().isClosed()) {
            return false;
        }
        if (situationNumbers != null && !situationNumbers.contains(situation.getSituationNumber())) {
            return false;
        }
        if (codespace != null && !codespace.equals(situation.getCodespace())) {
            return false;
        }
        if (severity != null && severity != situation.getSeverity()) {
            return false;
        }
        if (reportType != null && !reportType.equals(situation.getReportType())) {
            return false;
        }

        Affects affects = situation.getAffects();
        if (operatorRef != null && (affects == null || !affects.getOperatorRefs().contains(operatorRef))) {
            return false;
        }
        if (lineRef != null && (affects == null || !affects.getLineRefs().contains(lineRef))) {
            return false;
        }
        if (stopRefs != null && (affects == null || Collections.disjoint(affects.getStopRefs(), stopRefs))) {
            return false;
        }
        if (serviceJourneyId != null && (affects == null || !affects.getServiceJourneyIds().contains(serviceJourneyId))) {
            return false;
        }
        if (datedServiceJourneyId != null
                && (affects == null || !affects.getDatedServiceJourneyIds().contains(datedServiceJourneyId))) {
            return false;
        }
        if (mode != null && (affects == null || !affects.getVehicleModes().contains(mode))) {
            return false;
        }

        if (validNow != null && validNow != situation.isValidAt(ZonedDateTime.now())) {
            return false;
        }
        if (openEnded != null && !openEnded.equals(situation.getOpenEnded())) {
            return false;
        }
        if (minAge != null) {
            // Computed per call - not once in the constructor - so a long-lived
            // subscription's window keeps rolling forward with "now" instead of
            // freezing at the moment the subscription was opened.
            ZonedDateTime maxCreationTime = ZonedDateTime.now().minus(minAge);
            if (situation.getCreationTime() == null || situation.getCreationTime().isAfter(maxCreationTime)) {
                return false;
            }
        }

        if (metricsService != null) {
            metricsService.markFilterMatch(situation.getCodespace(), metricType);
        }
        return true;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", SituationFilter.class.getSimpleName() + "[", "]")
                .add("situationNumbers=" + situationNumbers)
                .add("codespace=" + codespace)
                .add("operatorRef='" + operatorRef + "'")
                .add("lineRef='" + lineRef + "'")
                .add("stopRefs=" + stopRefs)
                .add("serviceJourneyId='" + serviceJourneyId + "'")
                .add("datedServiceJourneyId='" + datedServiceJourneyId + "'")
                .add("mode=" + mode)
                .add("severity=" + severity)
                .add("reportType='" + reportType + "'")
                .add("validNow=" + validNow)
                .add("openEnded=" + openEnded)
                .add("minAge=" + minAge)
                .add("includeClosed=" + includeClosed)
                .toString();
    }
}
