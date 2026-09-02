package org.entur.vehicles.service.planned;

import java.util.List;

/**
 * Where {@link NetexPlannedDataExtractor} puts what it finds. The builder is the sink that
 * matters; the snapshot writer is a second one, and a tee feeds both during a full parse.
 * Ids are never null (the extractor skips elements without one); every other argument may be.
 */
public interface PlannedDataSink {

    PlannedDataSink addOperator(String id, String name);

    PlannedDataSink addLine(String id, String name, String publicCode);

    /** @param geometry interleaved lat/lon microdegrees, or null when the link has no gis:posList */
    PlannedDataSink addServiceLink(String id, int[] geometry);

    PlannedDataSink addJourneyPattern(String id, List<String> serviceLinkIds);

    PlannedDataSink addServiceJourney(String id, String journeyPatternId, String lineId);

    PlannedDataSink addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId);

    PlannedDataSink addOperatingDay(String id, String calendarDate);

    /**
     * Seeds the duplicate-id count a v2 snapshot's header carries, so a replay can hand it to
     * any sink without an {@code instanceof} check. A no-op for sinks (such as {@link
     * PlannedDataSnapshot.Writer}) that have no use for it.
     */
    default void seedDuplicateIds(int duplicateIds) {
    }
}
