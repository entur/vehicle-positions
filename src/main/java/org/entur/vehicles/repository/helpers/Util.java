package org.entur.vehicles.repository.helpers;

import org.entur.vehicles.data.VehicleModeEnumeration;

import java.time.ZonedDateTime;
import java.util.List;

public class Util {
    public static boolean containsValues(List list) {
      return list != null && !list.isEmpty();
    }

    public static VehicleModeEnumeration resolveModeByOperator(String operator) {
        return switch (operator) {
            case "Sporvognsdrift" -> VehicleModeEnumeration.TRAM;
            case "Tide_sjø_AS" -> VehicleModeEnumeration.FERRY;
            default -> VehicleModeEnumeration.BUS;
        };
    }

    public static ZonedDateTime convert(CharSequence timestamp) {
      return ZonedDateTime.parse(timestamp);
    }
}
