package org.entur.vehicles.service.planned;

/**
 * A NeTEx {@code DestinationDisplay} as published, from the parse to the builder and through
 * the snapshot unchanged. Only the id is non-null. Read and written by
 * {@link DestinationDisplayCodec}.
 *
 * @param frontText the display's own {@code FrontText}, not a variant's
 */
public record DestinationDisplayRecord(String id, String frontText) {
}
