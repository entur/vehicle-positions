package org.entur.vehicles.data.model;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class ServiceJourney extends ObjectRef {

    private static Cache<String, ServiceJourney> objectCache = CacheBuilder.newBuilder()
        .expireAfterAccess(3600, TimeUnit.SECONDS)
        .build();

    public static ServiceJourney getServiceJourney(String id) {
        try {
            return objectCache.get(id, () -> new ServiceJourney(id));
        }
        catch (ExecutionException e) {
            return new ServiceJourney(id);
        }
    }

    private ServiceJourney(String id) {
        super(id);
    }

    public String getServiceJourneyId() {
        return super.getRef();
    }
}
