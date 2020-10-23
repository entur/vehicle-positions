package org.entur.vehicles.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleUpdateFilterTest {

  @Test
  void testEqualMatch() {
    VehicleUpdateFilter filter = new VehicleUpdateFilter();
    filter.setLine(new Line("TST:Line:123", "A - B"));

    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "A - B"));

    assertTrue(filter.isMatch(update));

    update.setLine(new Line("TST:Line:321", "C - D"));
    assertFalse(filter.isMatch(update));
  }

  @Test
  void testContainsMatch() {
    VehicleUpdateFilter filter = new VehicleUpdateFilter();
    filter.setLine(new Line(".*123.*",null));

    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "A - B"));

    assertTrue(filter.isMatch(update));

    update.setLine(new Line("TST:Line:321", "C - D"));
    assertFalse(filter.isMatch(update));
  }

  @Test
  void testMultipleCriteriaMatch() {
    VehicleUpdateFilter filter = new VehicleUpdateFilter();
    filter.setLine(new Line("TST:Line:123", null));
    filter.setCodespaceId("TST");

    VehicleUpdate update = new VehicleUpdate();
    update.setLine(new Line("TST:Line:123", "A - B"));
    update.setCodespaceId("TST");

    assertTrue(filter.isMatch(update));

    update.setCodespaceId("ABC");
    assertFalse(filter.isMatch(update));
  }
}