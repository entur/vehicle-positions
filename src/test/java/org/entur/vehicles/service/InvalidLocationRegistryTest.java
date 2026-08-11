package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidLocationRegistryTest {

  private static final String DEFAULT_CONFIG = "0.0/0.0,-1.0/-1.0,1.0/1.0";

  // NOTE: Location takes (longitude, latitude) - longitude first.
  private static Location location(double latitude, double longitude) {
    return new Location(longitude, latitude);
  }

  @Test
  void theDefaultConfiguredCoordinatesAreInvalid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(location(0.0, 0.0))).isTrue();
    assertThat(registry.isInvalid(location(-1.0, -1.0))).isTrue();
    assertThat(registry.isInvalid(location(1.0, 1.0))).isTrue();
  }

  @Test
  void aRealCoordinateIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    // Oslo
    assertThat(registry.isInvalid(location(59.911491, 10.757933))).isFalse();
  }

  @Test
  void aCoordinateMatchingOnlyOneComponentIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(location(0.0, 10.757933))).isFalse();
    assertThat(registry.isInvalid(location(59.911491, 0.0))).isFalse();
  }

  @Test
  void negativeZeroMatchesAConfiguredZero() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(location(-0.0, -0.0))).isTrue();
  }

  @Test
  void aNullLocationIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    assertThat(registry.isInvalid(null)).isFalse();
  }

  @Test
  void aLocationWithNullComponentsIsValid() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry(DEFAULT_CONFIG);

    Location location = location(0.0, 0.0);
    location.setLatitude(null);

    assertThat(registry.isInvalid(location)).isFalse();
  }

  @Test
  void aCustomConfigurationReplacesTheDefaults() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("12.5/13.5");

    assertThat(registry.isInvalid(location(12.5, 13.5))).isTrue();
    assertThat(registry.isInvalid(location(0.0, 0.0))).isFalse();
  }

  @Test
  void whitespaceAroundEntriesIsIgnored() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("  0.0 / 0.0 ,  1.0/1.0  ");

    assertThat(registry.isInvalid(location(0.0, 0.0))).isTrue();
    assertThat(registry.isInvalid(location(1.0, 1.0))).isTrue();
  }

  @Test
  void aMalformedEntryIsSkippedAndTheRestStillParsed() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,not-a-coordinate,7.0/8.0/9.0,1.0/1.0");

    assertThat(registry.isInvalid(location(0.0, 0.0))).isTrue();
    assertThat(registry.isInvalid(location(1.0, 1.0))).isTrue();
  }

  @Test
  void anEmptyConfigurationDisablesTheRegistry() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("");

    assertThat(registry.isInvalid(location(0.0, 0.0))).isFalse();
  }
}
