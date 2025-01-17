package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.Line;
import org.junit.jupiter.api.Test;

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
}