package org.entur.vehicles.data.model;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.OccupancyStatus;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.ZonedDateTime;

@SchemaMapping
public class Call {
    StopPoint stopPoint;
    Integer order;

    private OccupancyStatus occupancyStatus;

    Boolean vehicleAtStop;
    private ZonedDateTime aimedArrivalTime;
    private ZonedDateTime aimedDepartureTime;
    private ZonedDateTime expectedArrivalTime;
    private ZonedDateTime expectedDepartureTime;
    private ZonedDateTime actualArrivalTime;
    private ZonedDateTime actualDepartureTime;
    private String arrivalStatus;
    private String departureStatus;
    private boolean cancellation;
    private EstimatedTimetableUpdate.CallType callType;
    private String arrivalBoardingActivity;
    private String departureBoardingActivity;

    /**
     * The journey this call belongs to, set by {@link org.entur.vehicles.data.EstimatedTimetableUpdate#addCall}.
     * Read by SituationMatcher so a stop scoped to a journey can be checked against the call's
     * own journey. Deliberately not part of the GraphQL schema.
     * <p>
     * There is nothing to exclude it from: {@code Call} declares no equals, hashCode or toString
     * and so is compared by identity, inherited from Object. That is load-bearing rather than an
     * oversight - the {@code Call.situations} {@code @BatchMapping} keys its DataLoader on the
     * Call objects themselves, so two calls of one journey at the same stop must stay distinct
     * keys and receive their own answers. Giving this class value equality would collapse them,
     * and adding this back-reference to it would recurse besides. See
     * {@code GraphQlBatchLoaderConfiguration} for what value equality on a batch key already cost
     * on the {@code EstimatedTimetableUpdate} side.
     */
    private EstimatedTimetableUpdate owner;

    public EstimatedTimetableUpdate getOwner() {
        return owner;
    }

    public void setOwner(EstimatedTimetableUpdate owner) {
        this.owner = owner;
    }

    public StopPoint getStopPoint() {
        return stopPoint;
    }

    public void setStopPoint(StopPoint stopPoint) {
        this.stopPoint = stopPoint;
    }

    public Integer getOrder() {
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }

    public OccupancyStatus getOccupancyStatus() {
        return occupancyStatus;
    }

    public void setOccupancyStatus(OccupancyStatus occupancyStatus) {
        this.occupancyStatus = occupancyStatus;
    }

    public Boolean getVehicleAtStop() {
        return vehicleAtStop;
    }

    public void setVehicleAtStop(Boolean vehicleAtStop) {
        this.vehicleAtStop = vehicleAtStop;
    }

    public void setExpectedArrivalTime(ZonedDateTime expectedArrivalTime) {
        this.expectedArrivalTime = expectedArrivalTime;
    }

    public ZonedDateTime getExpectedArrivalTime() {
        return expectedArrivalTime;
    }

    public Long getExpectedArrivalTimeEpochSecond() {
        return expectedArrivalTime != null ? expectedArrivalTime.toEpochSecond() : null;
    }

    public void setExpectedDepartureTime(ZonedDateTime expectedDepartureTime) {
        this.expectedDepartureTime = expectedDepartureTime;
    }

    public ZonedDateTime getExpectedDepartureTime() {
        return expectedDepartureTime;
    }

    public Long getExpectedDepartureTimeEpochSecond() {
        return expectedDepartureTime != null ? expectedDepartureTime.toEpochSecond() : null;
    }

    public void setActualArrivalTime(ZonedDateTime actualArrivalTime) {
        this.actualArrivalTime = actualArrivalTime;
    }

    public ZonedDateTime getActualArrivalTime() {
        return actualArrivalTime;
    }

    public Long getActualArrivalTimeEpochSecond() {
        return actualArrivalTime != null ? actualArrivalTime.toEpochSecond() : null;
    }

    public void setActualDepartureTime(ZonedDateTime actualDepartureTime) {
        this.actualDepartureTime = actualDepartureTime;
    }

    public ZonedDateTime getActualDepartureTime() {
        return actualDepartureTime;
    }

    public Long getActualDepartureTimeEpochSecond() {
        return actualDepartureTime != null ? actualDepartureTime.toEpochSecond() : null;
    }

    public void setArrivalStatus(String arrivalStatus) {
        this.arrivalStatus = arrivalStatus;
    }

    public CharSequence getArrivalStatus() {
        return arrivalStatus;
    }

    public void setDepartureStatus(String departureStatus) {
        this.departureStatus = departureStatus;
    }

    public CharSequence getDepartureStatus() {
        return departureStatus;
    }

    public void setCancellation(boolean cancellation) {
        this.cancellation = cancellation;
    }

    public boolean getCancellation() {
        return cancellation;
    }

    public void setCallType(EstimatedTimetableUpdate.CallType callType) {
        this.callType = callType;
    }

    public EstimatedTimetableUpdate.CallType getCallType() {
        return callType;
    }

    public ZonedDateTime getAimedDepartureTime() {
        return aimedDepartureTime;
    }

    public Long getAimedDepartureTimeEpochSecond() {
        return aimedDepartureTime != null ? aimedDepartureTime.toEpochSecond() : null;
    }

    public void setAimedDepartureTime(ZonedDateTime aimedDepartureTime) {
        this.aimedDepartureTime = aimedDepartureTime;
    }

    public ZonedDateTime getAimedArrivalTime() {
        return aimedArrivalTime;
    }

    public Long getAimedArrivalTimeEpochSecond() {
        return aimedArrivalTime != null ? aimedArrivalTime.toEpochSecond() : null;
    }

    public void setAimedArrivalTime(ZonedDateTime aimedArrivalTime) {
        this.aimedArrivalTime = aimedArrivalTime;
    }

    public void setArrivalBoardingActivity(String arrivalBoardingActivity) {
        this.arrivalBoardingActivity = arrivalBoardingActivity;
    }

    public String getArrivalBoardingActivity() {
        return arrivalBoardingActivity;
    }

    public void setDepartureBoardingActivity(String departureBoardingActivity) {
        this.departureBoardingActivity = departureBoardingActivity;
    }

    public String getDepartureBoardingActivity() {
        return departureBoardingActivity;
    }

    /**
     * Start of the window during which the vehicle is at this stop, resolved
     * actual -> expected -> aimed. Falls back to the departure side when no arrival time
     * is known, so a call with a single timestamp is an instant. Null when the call
     * carries no timestamps at all, meaning an unbounded window.
     * <p>
     * Not exposed through GraphQL - the schema declares no such field.
     */
    public ZonedDateTime getWindowStart() {
        ZonedDateTime arrival = firstNonNull(actualArrivalTime, expectedArrivalTime, aimedArrivalTime);
        return arrival != null
                ? arrival
                : firstNonNull(actualDepartureTime, expectedDepartureTime, aimedDepartureTime);
    }

    /** End of the window during which the vehicle is at this stop. See {@link #getWindowStart()}. */
    public ZonedDateTime getWindowEnd() {
        ZonedDateTime departure = firstNonNull(actualDepartureTime, expectedDepartureTime, aimedDepartureTime);
        return departure != null
                ? departure
                : firstNonNull(actualArrivalTime, expectedArrivalTime, aimedArrivalTime);
    }

    private static ZonedDateTime firstNonNull(ZonedDateTime... candidates) {
        for (ZonedDateTime candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
