package org.entur.vehicles.service.planned;

/**
 * A NeTEx {@code DatedServiceJourney} as published, from the parse to the builder and through
 * the snapshot unchanged. Only the id is non-null. Read and written by
 * {@link DatedServiceJourneyCodec}. The export holds millions of these: keep it to references.
 *
 * @param serviceJourneyId its {@code ServiceJourneyRef}
 * @param operatingDayId   its {@code OperatingDayRef}
 */
public record DatedServiceJourneyRecord(String id, String serviceJourneyId, String operatingDayId) {
}
