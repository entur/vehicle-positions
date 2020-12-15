package org.entur.vehicles.data.model;

import com.google.common.base.Objects;

import java.util.StringJoiner;

public class Identifier {
    private String id;

    protected Identifier(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
    protected void setId(String id) {
        this.id = id;
    }
    public boolean matches(Identifier other) {
        return getId().matches(other.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Identifier)) return false;
        Identifier codespace = (Identifier) o;
        return Objects.equal(id, codespace.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", this.getClass().getSimpleName() + "[", "]")
            .add("id='" + id + "'")
            .toString();
    }
}
