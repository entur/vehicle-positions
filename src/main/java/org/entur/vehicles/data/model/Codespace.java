package org.entur.vehicles.data.model;

public class Codespace extends ObjectRef {

    public Codespace(String id) {
        super(id);
    }

    public String getCodespaceId() {
        return super.getRef();
    }
}
