package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.planned.PlannedDataSink.StopDestinationDisplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A NeTEx {@code JourneyPattern} or {@code ServiceJourneyPattern}, from the parse to the
 * builder and through the snapshot unchanged. Read and written by {@link JourneyPatternCodec}.
 * <p>
 * Keeps only the stops where the destination display changes, however it is built: most
 * producers repeat the ref on every stop, and a stop without one keeps the previous anyway.
 * Compares its arrays by content: a record's own {@code equals} compares them by reference.
 *
 * @param serviceLinkIds      the {@code ServiceLinkRef}s under {@code linksInSequence}, in
 *                            document order; never null
 * @param destinationDisplays the {@code DestinationDisplayRef} of each
 *                            {@code StopPointInJourneyPattern} where the display changes, by
 *                            the stop's order; never null
 */
public record JourneyPatternRecord(String id, String[] serviceLinkIds, StopDestinationDisplay[] destinationDisplays) {

    private static final String[] NO_LINKS = new String[0];
    private static final StopDestinationDisplay[] NO_DISPLAYS = new StopDestinationDisplay[0];

    public JourneyPatternRecord {
        serviceLinkIds = serviceLinkIds == null ? NO_LINKS : serviceLinkIds;
        destinationDisplays = changesOnly(destinationDisplays);
    }

    private static StopDestinationDisplay[] changesOnly(StopDestinationDisplay[] displays) {
        if (displays == null || displays.length == 0) {
            return NO_DISPLAYS;
        }
        List<StopDestinationDisplay> changes = new ArrayList<>(displays.length);
        for (StopDestinationDisplay display : displays) {
            if (changes.isEmpty() || !changes.get(changes.size() - 1).destinationDisplayId().equals(display.destinationDisplayId())) {
                changes.add(display);
            }
        }
        return changes.size() == displays.length ? displays : changes.toArray(NO_DISPLAYS);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JourneyPatternRecord other
                && Objects.equals(id, other.id)
                && Arrays.equals(serviceLinkIds, other.serviceLinkIds)
                && Arrays.equals(destinationDisplays, other.destinationDisplays);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, Arrays.hashCode(serviceLinkIds), Arrays.hashCode(destinationDisplays));
    }

    @Override
    public String toString() {
        return "JourneyPatternRecord[id=" + id + ", serviceLinkIds=" + Arrays.toString(serviceLinkIds)
                + ", destinationDisplays=" + Arrays.toString(destinationDisplays) + "]";
    }
}
