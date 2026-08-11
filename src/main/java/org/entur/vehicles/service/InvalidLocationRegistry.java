package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Coordinates that upstream data producers are known to report for vehicles whose real position
 * is unknown - e.g. {@code 0,0}. Such vehicles are still stored and still queryable, but are
 * excluded from responses unless the client explicitly asks for them.
 * <p>
 * The list is configuration rather than code so that a new variant of the same upstream bug can
 * be handled by a config change and redeploy. Matching is exact: only the configured pairs are
 * treated as invalid.
 */
@Component
public class InvalidLocationRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(InvalidLocationRegistry.class);

  private final Set<Coordinate> invalidCoordinates;

  public InvalidLocationRegistry(
      @Value("${vehicle.invalid.locations:0.0/0.0,-1.0/-1.0,1.0/1.0}") String invalidLocations
  ) {
    this.invalidCoordinates = parse(invalidLocations);
    LOG.info("Treating {} coordinate(s) as invalid vehicle locations: {}",
        invalidCoordinates.size(), invalidCoordinates);
  }

  public boolean isInvalid(Location location) {
    if (location == null || location.getLatitude() == null || location.getLongitude() == null) {
      return false;
    }
    return invalidCoordinates.contains(
        new Coordinate(normalize(location.getLatitude()), normalize(location.getLongitude()))
    );
  }

  private static Set<Coordinate> parse(String invalidLocations) {
    Set<Coordinate> coordinates = new HashSet<>();
    if (invalidLocations == null || invalidLocations.isBlank()) {
      return coordinates;
    }
    for (String entry : invalidLocations.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] parts = trimmed.split("/");
      if (parts.length != 2) {
        LOG.warn("Ignoring malformed entry '{}' in vehicle.invalid.locations - expected 'latitude/longitude'", trimmed);
        continue;
      }
      try {
        coordinates.add(new Coordinate(
            normalize(Double.parseDouble(parts[0].trim())),
            normalize(Double.parseDouble(parts[1].trim()))
        ));
      } catch (NumberFormatException e) {
        LOG.warn("Ignoring entry '{}' in vehicle.invalid.locations - not a numeric coordinate pair", trimmed);
      }
    }
    return coordinates;
  }

  /**
   * {@code -0.0} and {@code 0.0} are distinct values to {@code Double.equals}, so a feed
   * reporting {@code -0.0} would not match a configured {@code 0.0} without this.
   */
  private static double normalize(double value) {
    return value == 0.0 ? 0.0 : value;
  }

  private record Coordinate(double latitude, double longitude) {
  }
}
