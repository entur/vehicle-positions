package org.entur.vehicles.data.model;

import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public class Operator extends ObjectRef {

    private String name;

    public static final Operator DEFAULT = new Operator();

    public Operator() {
        super("");
    }
    public Operator(String id) {
        super(id);
    }

    public String getOperatorRef() {
        return super.getRef();
    }

    public void setOperatorRef(String operatorRef) {
        super.setRef(operatorRef);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
