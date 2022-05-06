package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.entur.vehicles.data.model.ServiceJourney;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class ServiceJourneyService {

    @Autowired
    private JourneyPlannerGraphQLClient graphQLClient;

    @Value("${vehicle.serviceJourneyCache.enabled:false}")
    private boolean serviceJourneyCacheEnabled;

    private LoadingCache<String, ServiceJourney> serviceJourneyCache = CacheBuilder.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public ServiceJourney load(String serviceJourneyId) {
                    if (serviceJourneyCacheEnabled) {
                        return lookupServiceJourney(serviceJourneyId);
                    }
                    return new ServiceJourney(serviceJourneyId, null);
                }
            });


    public ServiceJourney getServiceJourney(String serviceJourneyId) throws ExecutionException {
        return serviceJourneyCache.get(serviceJourneyId);
    }

    private ServiceJourney lookupServiceJourney(String serviceJourneyId) {
        // No need to attempt lookup if id does not match pattern
        if (serviceJourneyId.contains(":ServiceJourney:")) {

            String query = "{\"query\":\"{serviceJourney(id:\\\"" + serviceJourneyId + "\\\"){ref:id pointsOnLink{length points}}}\",\"variables\":null}";

            Data data = null;
            try {
                data = graphQLClient.executeQuery(query);
            } catch (IOException e) {
                // Ignore - return empty ServiceJourney
            }
            if (data != null && data.serviceJourney != null) {
                return data.serviceJourney;
            }
        }
        return new ServiceJourney(serviceJourneyId);
    }
}