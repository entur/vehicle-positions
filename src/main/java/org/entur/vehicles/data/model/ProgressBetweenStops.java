package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public class ProgressBetweenStops {
    double linkDistance;
    double percentage;

    public ProgressBetweenStops(double linkDistance, double percentage) {
        this.linkDistance = linkDistance;
        this.percentage = percentage;
    }

    public double getLinkDistance() {
        return linkDistance;
    }

    public void setLinkDistance(double linkDistance) {
        this.linkDistance = linkDistance;
    }

    public double getPercentage() {
        return percentage;
    }

    public void setPercentage(double percentage) {
        this.percentage = percentage;
    }
}
