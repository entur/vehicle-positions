package org.entur.vehicles.service;

import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@link #getOperator} is static because its three callers - VehicleRepository,
 * TimetableRepository, SituationMapper - call it that way and have done so since the
 * operator cache was a static map. The static reference is set when Spring constructs the
 * bean; before that (and in tests that never construct one) every lookup misses, exactly as
 * the empty static cache did.
 */
@Service
public class OperatorService {

    private static volatile PlannedDataService plannedData;

    @Autowired
    public OperatorService(PlannedDataService plannedData) {
        OperatorService.plannedData = plannedData;
    }

    /** The operator from planned data, or null if unknown. */
    public static Operator getOperator(String operatorRef) {
        PlannedDataService service = plannedData;
        return service == null ? null : service.findOperator(operatorRef);
    }

    /**
     * Test hook: clears the static reference so a test that constructed an OperatorService
     * does not affect later tests.
     */
    public static void resetForTest() {
        plannedData = null;
    }
}
