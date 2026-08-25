package org.entur.vehicles.service.planned;

/**
 * What a DatedServiceJourney resolves to: the service journey it dates, and the calendar
 * date (ISO yyyy-MM-dd) of its operating day. {@code operatingDate} is null when the
 * OperatingDayRef could not be resolved in the export.
 */
public record DatedJourneyRef(String serviceJourneyId, String operatingDate) {
}
