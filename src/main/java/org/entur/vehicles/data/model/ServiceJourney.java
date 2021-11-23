package org.entur.vehicles.data.model;

public class ServiceJourney extends ObjectRef {

    private PointsOnLink pointsOnLink;

    public ServiceJourney() {super("dummy");}
    public ServiceJourney(String id) {
        super(id);
    }
    public ServiceJourney(String id, PointsOnLink pointsOnLink) {
        super(id);
    }

    public String getServiceJourneyId() {
        return super.getRef();
    }

    public PointsOnLink getPointsOnLink() {
        return pointsOnLink;
    }

    public void setPointsOnLink(PointsOnLink pointsOnLink) {
        this.pointsOnLink = pointsOnLink;
    }
}
