package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.graphql.Data;
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

    private final boolean serviceJourneyLookupEnabled;
    private final boolean datedServiceJourneyLookupEnabled;

    @Value("${vehicle.serviceJourney.concurrent.requests:2}")
    private int concurrentRequests;

    @Value("${vehicle.serviceJourney.concurrent.sleeptime:50}")
    private int sleepTime;

    private ExecutorService asyncExecutorService;

    private boolean initialized = false;

    private AtomicInteger concurrentServiceJourneyRequestCounter = new AtomicInteger();
    private AtomicInteger concurrentDatedServiceJourneyRequestCounter = new AtomicInteger();

    public ServiceJourneyService(@Value("${vehicle.serviceJourney.lookup.enabled:true}") boolean serviceJourneyLookupEnabled,
                                 @Value("${vehicle.datedserviceJourney.lookup.enabled:true}") boolean datedServiceJourneyLookupEnabled) {
        this.serviceJourneyLookupEnabled = serviceJourneyLookupEnabled;
        this.datedServiceJourneyLookupEnabled = datedServiceJourneyLookupEnabled;
        if (serviceJourneyLookupEnabled || datedServiceJourneyLookupEnabled) {
            if (concurrentRequests < 1) {
                concurrentRequests = 1;
            }
            asyncExecutorService = Executors.newFixedThreadPool(concurrentRequests);
        }
    }
    private final LoadingCache<String, ServiceJourney> serviceJourneyCache = CacheBuilder.newBuilder()
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

    private final LoadingCache<String, DatedServiceJourney> datedServiceJourneyCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public DatedServiceJourney load(String datedServiceJourneyId) {
                    if (datedServiceJourneyLookupEnabled) {
                        return lookupDatedServiceJourney(datedServiceJourneyId);
                    }
                    return new DatedServiceJourney(datedServiceJourneyId, new ServiceJourney(datedServiceJourneyId));
                }
            });

    public ServiceJourney getServiceJourney(String serviceJourneyId) throws ExecutionException {
        return serviceJourneyCache.get(serviceJourneyId);
    }
    public DatedServiceJourney getDatedServiceJourney(String datedServiceJourneyId) throws ExecutionException {
        return datedServiceJourneyCache.get(datedServiceJourneyId);
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

                    } else {
                        LOG.info("No service journey found for id " + serviceJourneyId);
                    }
                } catch (WebClientException e) {
                    // Ignore - return empty ServiceJourney
                }
                int waitingRequests = concurrentServiceJourneyRequestCounter.decrementAndGet();
                if (waitingRequests == 0) {
                    if (!initialized) {
                        LOG.info("Cache initialization up to date - {} serviceJourneys updated", serviceJourneyCache.size());
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

            concurrentServiceJourneyRequestCounter.incrementAndGet();
        }
        return new ServiceJourney(serviceJourneyId);
    }

    private DatedServiceJourney lookupDatedServiceJourney(String datedServiceJourneyId) {
        // No need to attempt lookup if id does not match pattern
        if (datedServiceJourneyId.contains(":DatedServiceJourney:")) {

            String query = "{\"query\":\"{datedServiceJourney(id:\\\"" + datedServiceJourneyId + "\\\"){ref:id operatingDay serviceJourney {ref:id pointsOnLink{length points}}}}\",\"variables\":null}";

            try {
                metricsService.markJourneyPlannerRequest("datedServiceJourney");
                Data data = graphQLClient.executeQuery(query);

                if (data != null && data.datedServiceJourney != null) {

                    if (data.datedServiceJourney.getServiceJourney() != null) {
                        data.datedServiceJourney.getServiceJourney().setDate(data.datedServiceJourney.getOperatingDay());
                    }

                    metricsService.markJourneyPlannerResponse("datedServiceJourney");
                    return data.datedServiceJourney;
                }

            } catch (WebClientException e) {
                // Ignore - return empty DatedServiceJourney
            }
        }
        return new DatedServiceJourney(datedServiceJourneyId, new ServiceJourney(datedServiceJourneyId));
    }
}
