package org.entur.vehicles.data.model;

public class ServiceJourney extends ObjectRef {

    private PointsOnLink pointsOnLink;

    private String date;

    public ServiceJourney() {super("");}
    public ServiceJourney(String id) {
        super(id);
    }
    public ServiceJourney(String id, String date) {
        super(id);
        this.date = date;
    }
    public ServiceJourney(String id, String date, PointsOnLink pointsOnLink) {
        super(id);
    }

    public String getServiceJourneyId() {
        return super.getRef();
    }
    public String getId() {
        return super.getRef();
    }

    public PointsOnLink getPointsOnLink() {
        return pointsOnLink;
    }

    public void setPointsOnLink(PointsOnLink pointsOnLink) {
        this.pointsOnLink = pointsOnLink;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }
}
