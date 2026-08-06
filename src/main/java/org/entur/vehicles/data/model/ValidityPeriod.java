package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.ZonedDateTime;

@SchemaMapping
public class ValidityPeriod {

    private final ZonedDateTime startTime;
    private final ZonedDateTime endTime;

    public ValidityPeriod(ZonedDateTime startTime, ZonedDateTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }

    public boolean isOpenEnded() {
        return endTime == null;
    }

    public boolean isValidAt(ZonedDateTime timestamp) {
        if (startTime != null && startTime.isAfter(timestamp)) {
            return false;
        }
        return endTime == null || !endTime.isBefore(timestamp);
    }

    /**
     * True when this period overlaps the window {@code [from, to]}, inclusive at both ends.
     * A null bound means unbounded on that side, so an unresolvable call window overlaps
     * every period rather than silently dropping the situation.
     */
    public boolean overlaps(ZonedDateTime from, ZonedDateTime to) {
        if (endTime != null && from != null && endTime.isBefore(from)) {
            return false;
        }
        return startTime == null || to == null || !startTime.isAfter(to);
    }
}
