package org.entur.vehicles.data;

import com.google.common.base.Objects;

import java.time.ZonedDateTime;

public class VehicleUpdate extends AbstractVehicleUpdate {

  private String direction;
  private ZonedDateTime lastUpdated;
  private ZonedDateTime expiration;
  private Location location;
  private Double speed;
  private Double heading;
  private Long delay;
  private String occupancy;

  public String getLineRef() {
    return super.getLine().getLineRef();
  }

  public String getLineName() {
    return super.getLine().getLineName();
  }


  public Location getLocation() {
    return location;
  }

  public void setLocation(Location location) {
    this.location = location;
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

  public void setLastUpdated(ZonedDateTime lastUpdated) {
    this.lastUpdated = lastUpdated;
  }

  public ZonedDateTime getExpiration() {
    return expiration;
  }

  public void setExpiration(ZonedDateTime expiration) {
    this.expiration = expiration;
  }

  public Double getSpeed() {
    return speed;
  }

  public void setSpeed(Double speed) {
    this.speed = speed;
  }

  public Double getHeading() {
    return heading;
  }

  public void setHeading(Double heading) {
    this.heading = heading;
  }

  public String getOccupancy() {
    return occupancy;
  }

  public void setOccupancy(String occupancy) {
    this.occupancy = occupancy;
  }

  public Long getDelay() {
    return delay;
  }

  public void setDelay(Long delay) {
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
}
