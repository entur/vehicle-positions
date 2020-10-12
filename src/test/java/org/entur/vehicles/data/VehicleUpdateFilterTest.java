package org.entur.vehicles.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleUpdateFilterTest {

  @Test
  void testEqualMatch() {
    VehicleUpdateFilter filter = new VehicleUpdateFilter();
    filter.setLineRef("TST:Line:123");

    VehicleUpdate update = new VehicleUpdate();
    update.setLineRef("TST:Line:123");

    assertTrue(filter.isMatch(update));

    update.setLineRef("TST:Line:321");
    assertFalse(filter.isMatch(update));
  }

  @Test
  void testContainsMatch() {
    VehicleUpdateFilter filter = new VehicleUpdateFilter();
    filter.setLineRef(".*123.*");

    VehicleUpdate update = new VehicleUpdate();
    update.setLineRef("TST:Line:123");

    assertTrue(filter.isMatch(update));

    update.setLineRef("TST:Line:321");
    assertFalse(filter.isMatch(update));
  }

  @Test
  void testMultipleCriteriaMatch() {
    VehicleUpdateFilter filter = new VehicleUpdateFilter();
    filter.setLineRef("TST:Line:123");
    filter.setCodespaceId("TST");

    VehicleUpdate update = new VehicleUpdate();
    update.setLineRef("TST:Line:123");
    update.setCodespaceId("TST");

    assertTrue(filter.isMatch(update));

    update.setCodespaceId("ABC");
    assertFalse(filter.isMatch(update));
  }
}