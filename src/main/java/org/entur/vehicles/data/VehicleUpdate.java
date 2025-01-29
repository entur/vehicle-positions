package org.entur.vehicles.data;

import com.google.common.base.Objects;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.MonitoredCall;
import org.entur.vehicles.data.model.ProgressBetweenStops;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.ZonedDateTime;

@SchemaMapping
public class VehicleUpdate extends AbstractVehicleUpdate {

  private String vehicleId;
  private String direction;
  private ZonedDateTime lastUpdated;
  private ZonedDateTime expiration;
  private Location location;
  private Double speed;
  private Double bearing;
  private long delay;
  private OccupancyEnumeration occupancy;
  private VehicleStatusEnumeration vehicleStatus;
  private Boolean inCongestion;
  private String originName;
  private String originRef;
  private String destinationName;
  private String destinationRef;
  private OccupancyStatus occupancyStatus;

  private ProgressBetweenStops progressBetweenStops;

  private MonitoredCall monitoredCall;

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

  public long getLastUpdatedEpochSecond() {
    return lastUpdated.toEpochSecond();
  }

  public void setLastUpdated(ZonedDateTime lastUpdated) {
    this.lastUpdated = lastUpdated;
  }

  public ZonedDateTime getExpiration() {
    return expiration;
  }

  public long getExpirationEpochSecond() {
    return expiration.toEpochSecond();
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

  public Double getBearing() {
    return bearing;
  }

  public void setBearing(Double bearing) {
    this.bearing = bearing;
  }

  @Deprecated
  public Double getHeading() {
    return bearing;
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

    public void setVehicleStatus(VehicleStatusEnumeration vehicleStatus) {
        this.vehicleStatus = vehicleStatus;
    }

    public VehicleStatusEnumeration getVehicleStatus() {
        return vehicleStatus;
    }

  public void setInCongestion(Boolean inCongestion) {
    this.inCongestion = inCongestion;
  }

  public Boolean getInCongestion() {
    return inCongestion;
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

  public ProgressBetweenStops getProgressBetweenStops() {
    return progressBetweenStops;
  }

  public void setProgressBetweenStops(ProgressBetweenStops progressBetweenStops) {
    this.progressBetweenStops = progressBetweenStops;
  }

  public MonitoredCall getMonitoredCall() {
    return monitoredCall;
  }

  public void setMonitoredCall(MonitoredCall monitoredCall) {
    this.monitoredCall = monitoredCall;
  }
}
