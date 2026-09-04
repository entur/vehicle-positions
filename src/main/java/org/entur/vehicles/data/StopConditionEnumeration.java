package org.entur.vehicles.data;

/**
 * SIRI-SX {@code StopCondition}. Carried through to clients so they can tell a stop the
 * vehicle passes without stopping from one where the disruption starts; it deliberately
 * does not drive the affected-segment rule, because producers tag it inconsistently -
 * see the spec's Decisions section.
 */
public enum StopConditionEnumeration {
    exceptionalStop,
    destination,
    notStopping,
    requestStop,
    startPoint;

    /** Null for an unrecognised or absent value - callers drop it rather than failing. */
    public static StopConditionEnumeration fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (StopConditionEnumeration condition : values()) {
            if (condition.name().equalsIgnoreCase(value)) {
                return condition;
            }
        }
        return null;
    }
}
