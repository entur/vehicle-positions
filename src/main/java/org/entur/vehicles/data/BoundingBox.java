package org.entur.vehicles.data;

import java.util.StringJoiner;

public class BoundingBox {
  private Double minLat, minLon, maxLat, maxLon;

  /**
   *
   * @return true if the provided latitude/longitude is within this bounding box
   */
  public boolean contains(Location location) {
    if (location != null) {
      return contains(location.getLongitude(), location.getLatitude());
    }
    return false;
  }

  private boolean contains(Double longitude, Double latitude) {
    if (longitude != null && latitude != null) {
      return
          longitude >= minLon &&
          longitude <= maxLon &&
          latitude >= minLat &&
          latitude <= maxLat;
    }
    return false;
  }

  public Double getMinLat() {
    return minLat;
  }

  public void setMinLat(Double minLat) {
    this.minLat = minLat;
  }

  public Double getMinLon() {
    return minLon;
  }

  public void setMinLon(Double minLon) {
    this.minLon = minLon;
  }

  public Double getMaxLat() {
    return maxLat;
  }

  public void setMaxLat(Double maxLat) {
    this.maxLat = maxLat;
  }

  public Double getMaxLon() {
    return maxLon;
  }

  public void setMaxLon(Double maxLon) {
    this.maxLon = maxLon;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", BoundingBox.class.getSimpleName() + "[", "]")
        .add("minLat=" + minLat)
        .add("minLon=" + minLon)
        .add("maxLat=" + maxLat)
        .add("maxLon=" + maxLon)
        .toString();
  }
}
