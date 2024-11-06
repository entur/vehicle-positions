package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public class DatedServiceJourney extends ObjectRef {

    private ServiceJourney serviceJourney;

    public DatedServiceJourney() {super("");}
    public DatedServiceJourney(String id) {
        super(id);
    }
    public DatedServiceJourney(String id, ServiceJourney serviceJourney) {
        super(id);
        this.serviceJourney = serviceJourney;
    }

    public String getId() {
        return super.getRef();
    }

    public ServiceJourney getServiceJourney() {
        return serviceJourney;
    }

    public void setServiceJourney(ServiceJourney serviceJourney) {
        this.serviceJourney = serviceJourney;
    }
}
