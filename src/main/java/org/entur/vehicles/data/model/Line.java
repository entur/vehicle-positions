package org.entur.vehicles.data.model;

import com.google.common.base.Objects;

import java.util.StringJoiner;

public class Line extends ObjectRef {
    private String lineName;

    public Line(String lineRef, String lineName) {
        super(lineRef);
        this.lineName = lineName;
    }

    public String getLineRef() {
        return getRef();
    }

    public void setLineRef(String lineRef) {
        setRef(lineRef);
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
        return super.equals(o) &&
                Objects.equal(lineName, line.lineName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getLineRef(), lineName);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Line.class.getSimpleName() + "[", "]")
            .add("lineRef='" + getLineRef() + "'")
            .add("lineName='" + getLineName() + "'")
            .toString();
    }
}
