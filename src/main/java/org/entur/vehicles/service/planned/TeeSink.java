package org.entur.vehicles.service.planned;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * Forwards every record to the builder and, as long as it keeps working, to the snapshot
 * writer. The writer is the optional part: its first failure is logged once and it is
 * dropped, so a full disk can cost the snapshot but never the dataset.
 */
final class TeeSink implements PlannedDataSink {

    private static final Logger LOG = LoggerFactory.getLogger(TeeSink.class);

    private final PlannedDataSink primary;
    private final PlannedDataSnapshot.Writer writer;
    private boolean writerFailed = false;

    TeeSink(PlannedDataSink primary, PlannedDataSnapshot.Writer writer) {
        this.primary = primary;
        this.writer = writer;
    }

    boolean writerFailed() {
        return writerFailed || writer.failed();
    }

    private interface Write {
        void to(PlannedDataSink sink);
    }

    private TeeSink both(Write write) {
        write.to(primary);
        if (!writerFailed) {
            try {
                write.to(writer);
            } catch (UncheckedIOException e) {
                writerFailed = true;
                LOG.warn("Snapshot writer failed - the dataset is unaffected, no snapshot will be uploaded", e);
            }
        }
        return this;
    }

    @Override
    public TeeSink addOperator(String id, String name) {
        return both(s -> s.addOperator(id, name));
    }

    @Override
    public TeeSink addLine(String id, String name, String publicCode) {
        return both(s -> s.addLine(id, name, publicCode));
    }

    @Override
    public TeeSink addServiceLink(String id, int[] geometry) {
        return both(s -> s.addServiceLink(id, geometry));
    }

    @Override
    public TeeSink addJourneyPattern(String id, List<String> serviceLinkIds) {
        return both(s -> s.addJourneyPattern(id, serviceLinkIds));
    }

    @Override
    public TeeSink addServiceJourney(String id, String journeyPatternId, String lineId) {
        return both(s -> s.addServiceJourney(id, journeyPatternId, lineId));
    }

    @Override
    public TeeSink addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId) {
        return both(s -> s.addDatedServiceJourney(id, serviceJourneyId, operatingDayId));
    }

    @Override
    public TeeSink addOperatingDay(String id, String calendarDate) {
        return both(s -> s.addOperatingDay(id, calendarDate));
    }
}
