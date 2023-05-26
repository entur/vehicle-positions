package org.entur.vehicles.data.model;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class Operator extends ObjectRef {

    public static final Operator DEFAULT = new Operator("");
    private static Cache<String, Operator> objectCache = CacheBuilder.newBuilder()
        .expireAfterAccess(3600, TimeUnit.SECONDS)
        .build();

    public static Operator getOperator(String id) {
        try {
            return objectCache.get(id, () -> new Operator(id));
        }
        catch (ExecutionException e) {
            return new Operator(id);
        }
    }

    private Operator(String id) {
        super(id);
    }

    public String getOperatorRef() {
        return super.getRef();
    }
}
