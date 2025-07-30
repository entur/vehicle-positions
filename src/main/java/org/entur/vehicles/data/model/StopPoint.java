package org.entur.vehicles.data.model;

public class StopPoint extends ObjectRef{

    private String name;
    private Location location;

    public StopPoint(String ref) {
        super(ref);
    }
    public StopPoint(String ref, String name, Location location) {
        super(ref);
        this.name = name;
        this.location = location;
    }
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Location getLocation() {
        return location;
    }
    public String getId() {
        return super.getRef();
    }
}
