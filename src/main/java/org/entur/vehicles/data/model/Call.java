package org.entur.vehicles.data.model;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.OccupancyStatus;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.ZonedDateTime;

import static org.entur.vehicles.data.model.PackedTime.EXACT;
import static org.entur.vehicles.data.model.PackedTime.NONE;

@SchemaMapping
public class Call {
    StopPoint stopPoint;
    Integer order;

    private OccupancyStatus occupancyStatus;

    Boolean vehicleAtStop;

    private static final int AIMED_ARRIVAL = 0;
    private static final int AIMED_DEPARTURE = 1;
    private static final int EXPECTED_ARRIVAL = 2;
    private static final int EXPECTED_DEPARTURE = 3;
    private static final int ACTUAL_ARRIVAL = 4;
    private static final int ACTUAL_DEPARTURE = 5;
    private static final int TIME_SLOTS = 6;

    /**
     * The six call times, indexed by the slot constants and packed with {@link PackedTime}:
     * one array instead of up to six four-object {@code ZonedDateTime}s per call, for every call
     * of every journey held for a day. The getters rebuild a {@code ZonedDateTime} on demand.
     */
    private final long[] times = {NONE, NONE, NONE, NONE, NONE, NONE};

    /** The times {@link PackedTime} cannot pack losslessly, by slot. Allocated only when one occurs. */
    private ZonedDateTime[] exactTimes;

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
        setTime(EXPECTED_ARRIVAL, expectedArrivalTime);
    }

    /** As {@link #setExpectedArrivalTime} with {@code ZonedDateTime.parse(text)}, without building one when it packs. */
    public void parseExpectedArrivalTime(CharSequence text) {
        parseTime(EXPECTED_ARRIVAL, text);
    }

    public ZonedDateTime getExpectedArrivalTime() {
        return time(EXPECTED_ARRIVAL);
    }

    public Long getExpectedArrivalTimeEpochSecond() {
        return epochSecond(EXPECTED_ARRIVAL);
    }

    public void setExpectedDepartureTime(ZonedDateTime expectedDepartureTime) {
        setTime(EXPECTED_DEPARTURE, expectedDepartureTime);
    }

    public void parseExpectedDepartureTime(CharSequence text) {
        parseTime(EXPECTED_DEPARTURE, text);
    }

    public ZonedDateTime getExpectedDepartureTime() {
        return time(EXPECTED_DEPARTURE);
    }

    public Long getExpectedDepartureTimeEpochSecond() {
        return epochSecond(EXPECTED_DEPARTURE);
    }

    public void setActualArrivalTime(ZonedDateTime actualArrivalTime) {
        setTime(ACTUAL_ARRIVAL, actualArrivalTime);
    }

    public void parseActualArrivalTime(CharSequence text) {
        parseTime(ACTUAL_ARRIVAL, text);
    }

    public ZonedDateTime getActualArrivalTime() {
        return time(ACTUAL_ARRIVAL);
    }

    public Long getActualArrivalTimeEpochSecond() {
        return epochSecond(ACTUAL_ARRIVAL);
    }

    public void setActualDepartureTime(ZonedDateTime actualDepartureTime) {
        setTime(ACTUAL_DEPARTURE, actualDepartureTime);
    }

    public void parseActualDepartureTime(CharSequence text) {
        parseTime(ACTUAL_DEPARTURE, text);
    }

    public ZonedDateTime getActualDepartureTime() {
        return time(ACTUAL_DEPARTURE);
    }

    public Long getActualDepartureTimeEpochSecond() {
        return epochSecond(ACTUAL_DEPARTURE);
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
        return time(AIMED_DEPARTURE);
    }

    public Long getAimedDepartureTimeEpochSecond() {
        return epochSecond(AIMED_DEPARTURE);
    }

    public void setAimedDepartureTime(ZonedDateTime aimedDepartureTime) {
        setTime(AIMED_DEPARTURE, aimedDepartureTime);
    }

    public void parseAimedDepartureTime(CharSequence text) {
        parseTime(AIMED_DEPARTURE, text);
    }

    public ZonedDateTime getAimedArrivalTime() {
        return time(AIMED_ARRIVAL);
    }

    public Long getAimedArrivalTimeEpochSecond() {
        return epochSecond(AIMED_ARRIVAL);
    }

    public void setAimedArrivalTime(ZonedDateTime aimedArrivalTime) {
        setTime(AIMED_ARRIVAL, aimedArrivalTime);
    }

    public void parseAimedArrivalTime(CharSequence text) {
        parseTime(AIMED_ARRIVAL, text);
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
        int arrival = firstSet(ACTUAL_ARRIVAL, EXPECTED_ARRIVAL, AIMED_ARRIVAL);
        return time(arrival != -1
                ? arrival
                : firstSet(ACTUAL_DEPARTURE, EXPECTED_DEPARTURE, AIMED_DEPARTURE));
    }

    /** End of the window during which the vehicle is at this stop. See {@link #getWindowStart()}. */
    public ZonedDateTime getWindowEnd() {
        int departure = firstSet(ACTUAL_DEPARTURE, EXPECTED_DEPARTURE, AIMED_DEPARTURE);
        return time(departure != -1
                ? departure
                : firstSet(ACTUAL_ARRIVAL, EXPECTED_ARRIVAL, AIMED_ARRIVAL));
    }

    private int firstSet(int first, int second, int third) {
        if (times[first] != NONE) {
            return first;
        }
        if (times[second] != NONE) {
            return second;
        }
        return times[third] != NONE ? third : -1;
    }

    private void setTime(int slot, ZonedDateTime time) {
        long packed = PackedTime.pack(time);
        times[slot] = packed;
        setExactTime(slot, packed == EXACT ? time : null);
    }

    private void parseTime(int slot, CharSequence text) {
        long packed = text != null ? PackedTime.parse(text) : NONE;
        if (packed == EXACT) {
            // Outside the fast path: parse as before, which also throws on malformed text as before
            setTime(slot, ZonedDateTime.parse(text));
        } else {
            times[slot] = packed;
            setExactTime(slot, null);
        }
    }

    private void setExactTime(int slot, ZonedDateTime time) {
        if (time != null) {
            if (exactTimes == null) {
                exactTimes = new ZonedDateTime[TIME_SLOTS];
            }
            exactTimes[slot] = time;
        } else if (exactTimes != null) {
            exactTimes[slot] = null;
        }
    }

    /** The time in a slot; null for a slot of -1 or one that holds no time. */
    private ZonedDateTime time(int slot) {
        if (slot == -1) {
            return null;
        }
        long packed = times[slot];
        return packed == EXACT ? exactTimes[slot] : PackedTime.unpack(packed);
    }

    private Long epochSecond(int slot) {
        long packed = times[slot];
        if (packed == NONE) {
            return null;
        }
        return packed == EXACT ? exactTimes[slot].toEpochSecond() : PackedTime.epochSecond(packed);
    }
}
