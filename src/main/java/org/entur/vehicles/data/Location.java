package org.entur.vehicles.data;

public class Location {
  private Double longitude, latitude;

  public Location(double longitude, double latitude) {
    this.longitude = longitude;
    this.latitude = latitude;
  }

  public Double getLongitude() {
    return longitude;
  }

  public void setLongitude(Double longitude) {
    this.longitude = longitude;
  }

  public Double getLatitude() {
    return latitude;
  }

  public void setLatitude(Double latitude) {
    this.latitude = latitude;
  }
}
