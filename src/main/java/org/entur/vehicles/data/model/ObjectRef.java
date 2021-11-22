package org.entur.vehicles.data.model;

import com.google.common.base.Objects;

import java.util.StringJoiner;

public abstract class ObjectRef {
    private String ref;

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
        return getRef().matches(other.getRef());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ObjectRef)) return false;
        ObjectRef codespace = (ObjectRef) o;
        return Objects.equal(ref, codespace.ref);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ref);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", this.getClass().getSimpleName() + "[", "]")
            .add("id='" + ref + "'")
            .toString();
    }
}
