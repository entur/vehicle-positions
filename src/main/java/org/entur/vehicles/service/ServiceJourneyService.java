package org.entur.vehicles.service;

import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.PointsOnLink;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.service.planned.DatedJourneyRef;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ServiceJourneyService {

    private final PlannedDataService plannedData;

    @Autowired
    public ServiceJourneyService(PlannedDataService plannedData) {
        this.plannedData = plannedData;
    }

    /**
     * A fresh ServiceJourney per call. Callers set the date on it, so instances must not be
     * shared; the PointsOnLink it carries is immutable and is shared.
     */
    public ServiceJourney getServiceJourney(String serviceJourneyId) {
        ServiceJourney serviceJourney = new ServiceJourney(serviceJourneyId);
        if (plannedData.hasServiceJourney(serviceJourneyId)) {
            serviceJourney.setPointsOnLink(plannedData.findPointsOnLink(serviceJourneyId));
        }
        return serviceJourney;
    }

    /**
     * A fresh DatedServiceJourney per call, with the operating day and a ServiceJourney
     * dated to it - the same shape the JourneyPlanner lookup used to build.
     */
    public DatedServiceJourney getDatedServiceJourney(String datedServiceJourneyId) {
        DatedJourneyRef ref = plannedData.findDatedServiceJourney(datedServiceJourneyId);
        if (ref == null) {
            return new DatedServiceJourney(datedServiceJourneyId, new ServiceJourney(datedServiceJourneyId));
        }
        ServiceJourney serviceJourney = new ServiceJourney(ref.serviceJourneyId(), ref.operatingDate());
        PointsOnLink pointsOnLink = plannedData.findPointsOnLink(ref.serviceJourneyId());
        serviceJourney.setPointsOnLink(pointsOnLink);
        DatedServiceJourney datedServiceJourney = new DatedServiceJourney(datedServiceJourneyId, serviceJourney);
        datedServiceJourney.setOperatingDay(ref.operatingDate());
        return datedServiceJourney;
    }
}
