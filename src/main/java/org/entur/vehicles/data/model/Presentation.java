package org.entur.vehicles.data.model;

/**
 * A line's colours from NeTEx {@code Line/Presentation}, as published: six hex digits without
 * '#', never defaulted or normalised. Either may be null when the line publishes only the other.
 */
public record Presentation(String colour, String textColour) {

    /** The presentation for the given colours, or null when the line publishes neither. */
    public static Presentation of(String colour, String textColour) {
        return colour == null && textColour == null ? null : new Presentation(colour, textColour);
    }
}
