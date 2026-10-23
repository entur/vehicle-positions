package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.ServiceJourneyIdAndDate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  @Test
  void aLineRefRegexMatchesVehiclesAndTimetables() {
    QueryFilter filter = lineFilter("TST:Line:1.*", null);

    VehicleUpdate vehicle = new VehicleUpdate();
    vehicle.setLine(new Line("TST:Line:123", "A - B"));
    EstimatedTimetableUpdate timetable = new EstimatedTimetableUpdate();
    timetable.setLine(new Line("TST:Line:123", "A - B"));
    VehicleUpdate other = new VehicleUpdate();
    other.setLine(new Line("TST:Line:223", "A - B"));

    assertTrue(filter.isMatch(vehicle));
    assertTrue(filter.isMatch(timetable));
    assertFalse(filter.isMatch(other));
  }

  @Test
  void aLineRegexMustMatchTheWholeValue() {
    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "Test Line"));

    assertFalse(lineFilter("TST:Line:12", null).isMatch(update));
    assertFalse(lineFilter(null, "Test").isMatch(update));
  }

  @Test
  void aVehicleWithoutTheFilteredLineFieldDoesNotMatch() {
    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", null));

    assertFalse(lineFilter(null, ".*").isMatch(update));
  }

  /**
   * Compiled once when the filter is built, so a malformed pattern is a bad request for the
   * query or subscription rather than an exception per vehicle or per published update.
   */
  @Test
  void anInvalidLineRegexIsRejectedWhenTheFilterIsBuilt() {
    assertThrows(IllegalArgumentException.class, () -> lineFilter("TST:Line:(", null));
    assertThrows(IllegalArgumentException.class, () -> lineFilter(null, "[A"));
  }

  private static QueryFilter lineFilter(String lineRef, String lineName) {
    return new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            lineRef, lineName,
            null, null, null, null
    );
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

  /**
   * A stored vehicle, flagged the way {@code VehicleRepository} flags it at ingest. Which
   * coordinates count as invalid is {@code InvalidLocationRegistryTest}'s concern; here only the
   * flag matters, which is all the filter reads.
   */
  private static VehicleUpdate vehicle(boolean locationInvalid) {
    VehicleUpdate update = new VehicleUpdate();
    // NOTE: Location takes (longitude, latitude) - longitude first.
    update.setLocation(locationInvalid ? new Location(0.0, 0.0) : new Location(10.757933, 59.911491));
    update.setLocationInvalid(locationInvalid);
    return update;
  }

  @Test
  void testInvalidLocationExcludedByDefault() {
    assertFalse(emptyFilter().withLocationValidity(false).isMatch(vehicle(true)));
  }

  @Test
  void testValidLocationMatchesRegardlessOfFlag() {
    assertTrue(emptyFilter().withLocationValidity(false).isMatch(vehicle(false)));
    assertTrue(emptyFilter().withLocationValidity(true).isMatch(vehicle(false)));
  }

  @Test
  void testInvalidLocationIncludedWhenRequested() {
    assertTrue(emptyFilter().withLocationValidity(true).isMatch(vehicle(true)));
  }

  @Test
  void testFilterWithoutLocationValidityIsUnchanged() {
    // Timetable and situation filters - and every pre-existing test - never call
    // withLocationValidity, and must keep matching invalid locations.
    assertTrue(emptyFilter().isMatch(vehicle(true)));
  }

  /** The filter reads the flag, not the coordinates: a stored 0,0 left unflagged is not re-checked. */
  @Test
  void testTheFlagDecidesNotTheCoordinates() {
    VehicleUpdate unflagged = vehicle(true);
    unflagged.setLocationInvalid(false);

    assertTrue(emptyFilter().withLocationValidity(false).isMatch(unflagged));
  }

  @Test
  void testInvalidLocationExcludedEvenWhenOtherCriteriaMatch() {
    QueryFilter filter = new QueryFilter(
            null,
            MetricType.QUERY,
            null, null, null, null, null, null,
            "TST:Line:123",
            null,
            null, null, null, null
    ).withLocationValidity(false);

    VehicleUpdate update = vehicle(true);
    update.setLine(new Line("TST:Line:123", "A - B"));

    assertFalse(filter.isMatch(update));
  }
}