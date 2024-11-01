package org.entur.vehicles.data.model;

import com.google.common.base.Objects;

import java.util.StringJoiner;

public abstract class ObjectRef {
    private String ref;

    private transient int hashCode = -1;

    protected ObjectRef(String ref) {
        this.ref = ref;
    }

    public String getRef() {
        return ref;
    }
    protected void setRef(String ref) {
        this.ref = ref;
    }
    public boolean matches(ObjectRef other) {
        if (other == null) {
            return false;
        }
        return getRef().matches(other.getRef());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ObjectRef other)) return false;
        return Objects.equal(ref, other.ref);
    }

    @Override
    public int hashCode() {
        if (hashCode == -1) {
            hashCode = Objects.hashCode(ref);
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", this.getClass().getSimpleName() + "[", "]")
            .add("id='" + ref + "'")
            .toString();
    }
}
