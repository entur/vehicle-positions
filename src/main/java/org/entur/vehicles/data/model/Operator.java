package org.entur.vehicles.data.model;

public class Operator extends Identifier{

    public Operator(String id) {
        super(id);
    }

    public String getOperatorRef() {
        return super.getId();
    }
}
