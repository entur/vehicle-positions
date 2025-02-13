package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.ServiceJourneyIdAndDate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleUpdateFilterTest {

  @Test
  void testEqualMatch() {
    VehicleUpdateFilter filter = new VehicleUpdateFilter(
        null,
        null,
        null,
        null,
        null,
        null,
        "TST:Line:123",
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
    VehicleUpdateFilter filter = new VehicleUpdateFilter(
        null,
        null,
        null,
        null,
        null,
        null,
        ".*123.*",
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
    VehicleUpdateFilter filter = new VehicleUpdateFilter(
            null,
            null,
            null,
            "TST",
            null,
            null,
            "TST:Line:123",
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

    VehicleUpdateFilter filter = new VehicleUpdateFilter(
            serviceJourneyIdAndDates,
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
}