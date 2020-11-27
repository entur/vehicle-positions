package org.entur.vehicles.data;

import com.google.common.base.Objects;

import java.util.StringJoiner;

public class Codespace {
    private String id;

    public Codespace(String id) {
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
        if (!(o instanceof Codespace)) return false;
        Codespace codespace = (Codespace) o;
        return Objects.equal(id, codespace.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Codespace.class.getSimpleName() + "[", "]")
            .add("id='" + id + "'")
            .toString();
    }
}
