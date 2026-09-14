package org.entur.vehicles.service;

import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LineService {

    private final PlannedDataService plannedData;

    @Autowired
    public LineService(PlannedDataService plannedData) {
        this.plannedData = plannedData;
    }

    /** The line from planned data, or a bare ref if unknown. Never null. */
    public Line getLine(String lineRef) {
        Line line = plannedData.findLine(lineRef);
        return line != null ? line : new Line(lineRef);
    }

    /**
     * The mode NeTEx plans for a vehicle on this journey and line - the journey's own
     * TransportMode before its line's - or null when neither is known.
     */
    public VehicleModeEnumeration getTransportMode(String serviceJourneyId, String lineRef) {
        return plannedData.findTransportMode(serviceJourneyId, lineRef);
    }
}
