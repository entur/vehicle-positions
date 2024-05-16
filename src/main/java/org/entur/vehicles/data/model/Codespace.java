package org.entur.vehicles.data.model;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
@SchemaMapping
public class Codespace extends ObjectRef {

    private static final Cache<String, Codespace> objectCache = CacheBuilder.newBuilder()
        .expireAfterAccess(3600, TimeUnit.SECONDS)
        .build();

    public static Codespace getCodespace(String codespaceId) {
        try {
            return objectCache.get(codespaceId, () -> new Codespace(codespaceId));
        }
        catch (ExecutionException e) {
            return new Codespace(codespaceId);
        }
    }

    private Codespace(String id) {
        super(id);
    }

    public String getCodespaceId() {
        return super.getRef();
    }
}
