package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.entur.vehicles.data.model.ServiceJourney;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ServiceJourneyService {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceJourneyService.class.getName());

    @Autowired
    private JourneyPlannerGraphQLClient graphQLClient;

    private boolean serviceJourneyLookupEnabled;

    @Value("${vehicle.serviceJourney.concurrent.requests:2}")
    private int concurrentRequests;

    private ExecutorService asyncExecutorService;

    private boolean initialized = false;

    private AtomicInteger concurrentRequestCounter = new AtomicInteger();
    public ServiceJourneyService(@Value("${vehicle.serviceJourney.lookup.enabled:true}") boolean serviceJourneyLookupEnabled) {
        this.serviceJourneyLookupEnabled = serviceJourneyLookupEnabled;
        if (serviceJourneyLookupEnabled) {
            if (concurrentRequests < 1) {
                concurrentRequests = 1;
            }
            asyncExecutorService = Executors.newFixedThreadPool(concurrentRequests);
        }
    }
    private LoadingCache<String, ServiceJourney> serviceJourneyCache = CacheBuilder.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public ServiceJourney load(String serviceJourneyId) {
                    if (serviceJourneyLookupEnabled) {
                        return lookupServiceJourney(serviceJourneyId);
                    }
                    return new ServiceJourney(serviceJourneyId);
                }
            });


    public ServiceJourney getServiceJourney(String serviceJourneyId) throws ExecutionException {
        return serviceJourneyCache.get(serviceJourneyId);
    }

    private ServiceJourney lookupServiceJourney(String serviceJourneyId) {
        // No need to attempt lookup if id does not match pattern
        if (serviceJourneyId.contains(":ServiceJourney:")) {

            asyncExecutorService.submit(() -> {
                String query = "{\"query\":\"{serviceJourney(id:\\\"" + serviceJourneyId + "\\\"){ref:id pointsOnLink{length points}}}\",\"variables\":null}";

                Data data = null;
                try {
                    data = graphQLClient.executeQuery(query);
                } catch (WebClientException e) {
                    // Ignore - return empty ServiceJourney
                }
                if (data != null && data.serviceJourney != null) {
                    serviceJourneyCache.put(serviceJourneyId, data.serviceJourney);
                }
                int waitingRequests = concurrentRequestCounter.decrementAndGet();
                if (waitingRequests == 0) {
                    if (!initialized) {
                        LOG.info("Cache initialization complete");
                        initialized = true;
                    }
                }
            });
            concurrentRequestCounter.incrementAndGet();
        }
        return new ServiceJourney(serviceJourneyId);
    }
}
