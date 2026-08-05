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
}
