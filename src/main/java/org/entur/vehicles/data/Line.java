package org.entur.vehicles.data;

import com.google.common.base.Objects;

public class Line {
    private String lineRef;
    private String lineName;

    public Line(String lineRef, String lineName) {
        this.lineRef = lineRef;
        this.lineName = lineName;
    }

    public String getLineRef() {
        return lineRef;
    }

    public void setLineRef(String lineRef) {
        this.lineRef = lineRef;
    }

    public String getLineName() {
        return lineName;
    }

    public void setLineName(String lineName) {
        this.lineName = lineName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Line)) return false;
        Line line = (Line) o;
        return Objects.equal(lineRef, line.lineRef) &&
                Objects.equal(lineName, line.lineName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(lineRef, lineName);
    }
}
