package org.entur.vehicles.service.planned;

/**
 * A NeTEx {@code Line} or {@code FlexibleLine} as published, from the parse to the builder and
 * through the snapshot unchanged. Only the id is non-null. Read and written by {@link LineCodec}.
 *
 * @param colour        {@code Presentation/Colour}
 * @param textColour    {@code Presentation/TextColour}
 * @param transportMode the line's NeTEx {@code TransportMode}
 */
public record LineRecord(String id, String name, String publicCode,
                         String colour, String textColour, String transportMode) {
}
