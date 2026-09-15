package org.entur.vehicles.service.planned;

import java.util.List;

/**
 * Where {@link NetexPlannedDataExtractor} puts what it finds during a full parse, and what a
 * snapshot replay feeds into on a hit. Ids are never null (the extractor skips elements
 * without one); every other argument may be.
 */
public interface PlannedDataSink {

    PlannedDataSink addOperator(String id, String name);

    /**
     * @param colour     {@code Line/Presentation/Colour}, as published
     * @param textColour {@code Line/Presentation/TextColour}, as published
     * @param transportMode the line's NeTEx {@code TransportMode}, as published
     */
    PlannedDataSink addLine(String id, String name, String publicCode, String colour, String textColour, String transportMode);

    /** @param geometry interleaved lat/lon microdegrees, or null when the link has no gis:posList */
    PlannedDataSink addServiceLink(String id, int[] geometry);

    PlannedDataSink addJourneyPattern(String id, List<String> serviceLinkIds);

    /** @param transportMode the journey's own NeTEx {@code TransportMode}; null when it inherits its line's */
    PlannedDataSink addServiceJourney(String id, String journeyPatternId, String lineId, String transportMode);

    PlannedDataSink addDatedServiceJourney(String id, String serviceJourneyId, String operatingDayId);

    PlannedDataSink addOperatingDay(String id, String calendarDate);

    /**
     * Seeds the duplicate-id count a snapshot's header carries, so a replay can hand it to any
     * sink without an {@code instanceof} check. A no-op for a sink that has no use for it.
     */
    default void seedDuplicateIds(int duplicateIds) {
    }
}
