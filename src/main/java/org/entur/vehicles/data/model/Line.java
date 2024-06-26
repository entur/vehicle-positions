package org.entur.vehicles.data.model;

import com.google.common.base.Objects;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.StringJoiner;

@SchemaMapping
public class Line extends ObjectRef {
    private String lineName;
    private String publicCode;

    public static final Line DEFAULT = new Line();

    public Line() {
        super("");
    }

    public Line(String lineRef, String lineName) {
        super(lineRef);
        this.lineName = lineName;
    }

    public Line(String lineRef) {
        super(lineRef);
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

    public String getPublicCode() {
        return publicCode;
    }

    public void setPublicCode(String publicCode) {
        this.publicCode = publicCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Line line)) return false;
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
