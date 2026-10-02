package org.entur.vehicles.service.planned;

/**
 * A NeTEx {@code OperatingDay} as published, from the parse to the builder and through the
 * snapshot unchanged. Only the id is non-null. Read and written by {@link OperatingDayCodec}.
 *
 * @param calendarDate its {@code CalendarDate}, kept as the published string: xsd:date permits
 *                     a timezone that {@code LocalDate.parse} rejects
 */
public record OperatingDayRecord(String id, String calendarDate) {
}
