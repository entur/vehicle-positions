package org.entur.vehicles.service.planned;

/**
 * A NeTEx {@code ServiceJourney} as published, from the parse to the builder and through the
 * snapshot unchanged. Only the id is non-null. Read and written by {@link ServiceJourneyCodec}.
 *
 * @param journeyPatternId its {@code JourneyPatternRef}
 * @param lineId           its own {@code LineRef} or {@code FlexibleLineRef}
 * @param transportMode    its own NeTEx {@code TransportMode}; null when it inherits its line's
 */
public record ServiceJourneyRecord(String id, String journeyPatternId, String lineId, String transportMode) {
}
