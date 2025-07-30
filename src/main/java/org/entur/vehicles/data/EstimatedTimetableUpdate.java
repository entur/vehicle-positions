package org.entur.vehicles.data;

import com.google.common.base.Objects;
import org.entur.vehicles.data.model.Call;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@SchemaMapping
public class EstimatedTimetableUpdate extends AbstractUpdate {

  private String vehicleId;
  private String direction;
  private ZonedDateTime lastUpdated;
  private ZonedDateTime expiration;
  private long delay;
  private OccupancyEnumeration occupancy;
  private String originName;
  private String originRef;
  private String destinationName;
  private String destinationRef;
  private OccupancyStatus occupancyStatus;
  private List<Call> calls;

  public String getVehicleId() {
    return vehicleId;
  }

  public void setVehicleId(String vehicleId) {
    this.vehicleId = vehicleId;
  }

  public String getLineRef() {
    return super.getLine().getLineRef();
  }

  public String getLineName() {
    return super.getLine().getLineName();
  }

  public String getDirection() {
    return direction;
  }

  public void setDirection(String direction) {
    this.direction = direction;
  }

  public ZonedDateTime getLastUpdated() {
    return lastUpdated;
  }

  public long getLastUpdatedEpochSecond() {
    return lastUpdated.toEpochSecond();
  }

  public void setLastUpdated(ZonedDateTime lastUpdated) {
    this.lastUpdated = lastUpdated;
  }

  public ZonedDateTime getExpiration() {
    return expiration;
  }

  public void setExpiration(ZonedDateTime expiration) {
    this.expiration = expiration;
  }

  public OccupancyEnumeration getOccupancy() {
    return occupancy;
  }

  public void setOccupancy(OccupancyEnumeration occupancy) {
    this.occupancy = occupancy;
  }

  public Long getDelay() {
    return delay;
  }

  public void setDelay(long delay) {
    this.delay = delay;
  }

  @Override
  public boolean equals(Object o) {
    return super.equals(o);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(super.hashCode());
  }

  public String getOriginName() {
    return originName;
  }

  public void setOriginName(String originName) {
    this.originName = originName;
  }

  public String getDestinationName() {
    return destinationName;
  }

  public void setDestinationName(String destinationName) {
    this.destinationName = destinationName;
  }

  public void setOriginRef(String originRef) {
    this.originRef = originRef;
  }

  public void setDestinationRef(String destinationRef) {
    this.destinationRef = destinationRef;
  }

  public String getOriginRef() {
    return originRef;
  }
  public String getDestinationRef() {
    return destinationRef;
  }

  public void setOccupancyStatus(OccupancyStatus occupancyStatus) {
    this.occupancyStatus = occupancyStatus;
  }

  public OccupancyStatus getOccupancyStatus() {
    return occupancyStatus;
  }

  public List<Call> getCalls() {
      return calls;
  }

  public void addCall(Call calls) {
      if (this.calls == null) {
          this.calls = new ArrayList<>();
      }
          this.calls.add(calls);
  }

  public enum CallType { RECORDED, ESTIMATED}
}
