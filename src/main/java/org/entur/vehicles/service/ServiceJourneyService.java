package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.metrics.PrometheusMetricsService;
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

    @Autowired
    private PrometheusMetricsService metricsService;

    private boolean serviceJourneyLookupEnabled;

    @Value("${vehicle.serviceJourney.concurrent.requests:2}")
    private int concurrentRequests;

    @Value("${vehicle.serviceJourney.concurrent.sleeptime:50}")
    private int sleepTime;

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
            .expireAfterAccess(6, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public ServiceJourney load(String serviceJourneyId) {
                    if (serviceJourneyLookupEnabled) {
                        return lookupServiceJourney(serviceJourneyId);
                    }
                    return new ServiceJourney(serviceJourneyId);
                }
            });

    private AtomicInteger initCounter = new AtomicInteger();

    public ServiceJourney getServiceJourney(String serviceJourneyId) throws ExecutionException {
        return serviceJourneyCache.get(serviceJourneyId);
    }

    private ServiceJourney lookupServiceJourney(String serviceJourneyId) {
        // No need to attempt lookup if id does not match pattern
        if (serviceJourneyId.contains(":ServiceJourney:")) {

            asyncExecutorService.submit(() -> {

                String query = "{\"query\":\"{serviceJourney(id:\\\"" + serviceJourneyId + "\\\"){ref:id pointsOnLink{length points}}}\",\"variables\":null}";

                try {
                    metricsService.markJourneyPlannerRequest("serviceJourney");
                    Data data = graphQLClient.executeQuery(query);

                    if (data != null && data.serviceJourney != null) {

                        metricsService.markJourneyPlannerResponse("serviceJourney");

                        serviceJourneyCache.put(serviceJourneyId, data.serviceJourney);
                        if (!initialized) {
                            initCounter.incrementAndGet();
                        }
                    }
                } catch (WebClientException e) {
                    // Ignore - return empty ServiceJourney
                }
                int waitingRequests = concurrentRequestCounter.decrementAndGet();
                if (waitingRequests == 0) {
                    if (!initialized) {
                        LOG.info("Cache initialization up to date - {} serviceJourneys updated", initCounter.get());
                        initialized = true;
                    }
                }

                try {
                    // Sleeping between each execution to offload request-rate
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });

            concurrentRequestCounter.incrementAndGet();
        }
        return new ServiceJourney(serviceJourneyId);
    }
}
