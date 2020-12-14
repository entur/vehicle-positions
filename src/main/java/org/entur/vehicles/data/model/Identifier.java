package org.entur.vehicles.data.model;

import com.google.common.base.Objects;

import java.util.StringJoiner;

class Identifier {
    private String id;

    protected Identifier(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
