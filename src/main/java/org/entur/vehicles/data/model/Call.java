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
}
