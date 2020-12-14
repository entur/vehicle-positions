package org.entur.vehicles.data.model;

public class ServiceJourney extends Identifier{

    public ServiceJourney(String id) {
        super(id);
    }

    public String getServiceJourneyId() {
        return super.getId();
    }
}
