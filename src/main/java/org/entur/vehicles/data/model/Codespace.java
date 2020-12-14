package org.entur.vehicles.data.model;

public class Codespace extends Identifier {

    public Codespace(String id) {
        super(id);
    }

    public String getCodespaceId() {
        return super.getId();
    }
}
