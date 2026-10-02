package org.entur.vehicles.service.planned;

/**
 * Where {@link NetexPlannedDataExtractor} puts what it finds during a full parse, and what a
 * snapshot replay feeds into on a hit. A record's id is never null (the codecs skip elements
 * without one); its other fields may be, unless the record says otherwise.
 */
public interface PlannedDataSink {

    PlannedDataSink addOperator(OperatorRecord operator);

    PlannedDataSink addLine(LineRecord line);

    PlannedDataSink addServiceLink(ServiceLinkRecord link);

    PlannedDataSink addJourneyPattern(JourneyPatternRecord pattern);

    PlannedDataSink addDestinationDisplay(DestinationDisplayRecord display);

    PlannedDataSink addServiceJourney(ServiceJourneyRecord journey);

    PlannedDataSink addDatedServiceJourney(DatedServiceJourneyRecord dated);

    PlannedDataSink addOperatingDay(OperatingDayRecord day);

    /** A journey pattern's stop, by its {@code order}, and the destination display it sets. */
    record StopDestinationDisplay(int order, String destinationDisplayId) {
    }

    /**
     * Seeds the duplicate-id count a snapshot's header carries, so a replay can hand it to any
     * sink without an {@code instanceof} check. A no-op for a sink that has no use for it.
     */
    default void seedDuplicateIds(int duplicateIds) {
    }
}
