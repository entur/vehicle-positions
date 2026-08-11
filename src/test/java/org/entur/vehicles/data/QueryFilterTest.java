package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.ServiceJourneyIdAndDate;
import org.entur.vehicles.service.InvalidLocationRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryFilterTest {

  @Test
  void testEqualMatch() {
    QueryFilter filter = new QueryFilter(
            null,
            MetricType.QUERY,
        null,
        null,
        null,
        null,
        null,
        null,
        "TST:Line:123",
        null,
        null,
        null,
        null,
            null
    );

    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "A - B"));

    assertTrue(filter.isMatch(update));

    update.setLine(new Line("TST:Line:321", "C - D"));
    assertFalse(filter.isMatch(update));
  }

  @Test
  void testContainsMatch() {
    QueryFilter filter = new QueryFilter(
            null,
            MetricType.QUERY,
        null,
        null,
        null,
        null,
        null,
        null,
        ".*123.*",
        null,
        null,
        null,
        null,
            null
    );

    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "A - B"));

    assertTrue(filter.isMatch(update));

    update.setLine(new Line("TST:Line:321", "C - D"));
    assertFalse(filter.isMatch(update));
  }

  @Test
  void testMultipleCriteriaMatch() {
    QueryFilter filter = new QueryFilter(
            null,
            MetricType.QUERY,
            null,
            null,
            null,
            "TST",
            null,
            null,
            "TST:Line:123",
            null,
            null,
            null,
            null,
            null
    );

    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "A - B"));
    update.setCodespace(Codespace.getCodespace("TST"));

    assertTrue(filter.isMatch(update));

    update.setCodespace(Codespace.getCodespace("ABC"));
    assertFalse(filter.isMatch(update));
  }


  @Test
  void testMultipleServiceJourneyIdAndDateMatch() {
    Set<ServiceJourneyIdAndDate> serviceJourneyIdAndDates =
            Set.of(
                    new ServiceJourneyIdAndDate("123", "2021-01-01"),
                    new ServiceJourneyIdAndDate("234", "2021-01-02"),
                    new ServiceJourneyIdAndDate("999", null)
            );

    QueryFilter filter = new QueryFilter(
            null,
            MetricType.QUERY,
            serviceJourneyIdAndDates,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
    );

    VehicleUpdate update = new VehicleUpdate();

    // Filter matches id and date
    update.setServiceJourney(new ServiceJourney("123", "2021-01-01"));
    assertTrue(filter.isMatch(update));

    // Filter matches id, and has no date
    update.setServiceJourney(new ServiceJourney("999", "2021-01-01"));
    assertTrue(filter.isMatch(update));

    // Filter matches id, but has wrong date
    update.setServiceJourney(new ServiceJourney("123", "2021-01-02"));
    assertFalse(filter.isMatch(update));

    // Filter matches other id and date
    update.setServiceJourney(new ServiceJourney("234", "2021-01-02"));
    assertTrue(filter.isMatch(update));
  }

  @Test
  void testShortCircuitEvaluation() {
    // Test that logical operators short-circuit properly
    QueryFilter filterWithLine = new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            "TST:Line:123",  // lineRef provided
            null,            // lineName is null
            null, null, null, null
    );

    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "Test Line"));

    // Should match even though lineName filter is null
    assertTrue(filterWithLine.isMatch(update));

    // Test with both lineRef and lineName
    QueryFilter filterWithBoth = new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            "TST:Line:123",  // lineRef
            "Test.*",        // lineName regex
            null, null, null, null
    );

    assertTrue(filterWithBoth.isMatch(update));

    // Test that first condition failure skips second evaluation
    QueryFilter filterNoMatch = new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            "OTHER:Line:999",  // Different lineRef
            null,
            null, null, null, null
    );

    assertFalse(filterNoMatch.isMatch(update));
  }

  private static QueryFilter emptyFilter() {
    return new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            null, null,
            null, null, null, null
    );
  }

  private static VehicleUpdate vehicleAt(double latitude, double longitude) {
    VehicleUpdate update = new VehicleUpdate();
    // NOTE: Location takes (longitude, latitude) - longitude first.
    update.setLocation(new Location(longitude, latitude));
    return update;
  }

  @Test
  void testInvalidLocationExcludedByDefault() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0");
    QueryFilter filter = emptyFilter().withLocationValidity(registry, false);

    assertFalse(filter.isMatch(vehicleAt(0.0, 0.0)));
    assertFalse(filter.isMatch(vehicleAt(-1.0, -1.0)));
    assertFalse(filter.isMatch(vehicleAt(1.0, 1.0)));
  }

  @Test
  void testValidLocationMatchesRegardlessOfFlag() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0");

    assertTrue(emptyFilter().withLocationValidity(registry, false).isMatch(vehicleAt(59.911491, 10.757933)));
    assertTrue(emptyFilter().withLocationValidity(registry, true).isMatch(vehicleAt(59.911491, 10.757933)));
  }

  @Test
  void testInvalidLocationIncludedWhenRequested() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0,-1.0/-1.0,1.0/1.0");
    QueryFilter filter = emptyFilter().withLocationValidity(registry, true);

    assertTrue(filter.isMatch(vehicleAt(0.0, 0.0)));
  }

  @Test
  void testFilterWithoutLocationValidityIsUnchanged() {
    // Timetable and situation filters - and every pre-existing test - never call
    // withLocationValidity, and must keep matching invalid locations.
    assertTrue(emptyFilter().isMatch(vehicleAt(0.0, 0.0)));
  }

  @Test
  void testNullRegistryDisablesTheCheck() {
    assertTrue(emptyFilter().withLocationValidity(null, false).isMatch(vehicleAt(0.0, 0.0)));
  }

  @Test
  void testInvalidLocationExcludedEvenWhenOtherCriteriaMatch() {
    InvalidLocationRegistry registry = new InvalidLocationRegistry("0.0/0.0");
    QueryFilter filter = new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            "TST:Line:123",
            null,
            null, null, null, null
    ).withLocationValidity(registry, false);

    VehicleUpdate update = vehicleAt(0.0, 0.0);
    update.setLine(new Line("TST:Line:123", "A - B"));

    assertFalse(filter.isMatch(update));
  }
}