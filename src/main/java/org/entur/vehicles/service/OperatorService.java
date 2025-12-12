package org.entur.vehicles.service;

import jakarta.annotation.PostConstruct;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.graphql.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyList;

@Service
public class OperatorService {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorService.class);

    @Autowired
    private JourneyPlannerGraphQLClient graphQLClient;

    @Autowired
    private PrometheusMetricsService metricsService;

    private final boolean operatorLookupEnabled;

    private static final ConcurrentHashMap<String, Operator> operatorCache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor;

    public OperatorService(@Value("${vehicle.operator.lookup.enabled:false}") boolean operatorLookupEnabled) {
        this.operatorLookupEnabled = operatorLookupEnabled;
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @PostConstruct
    private void warmUpCache() {
        if (operatorLookupEnabled) {
            //Trigger synchronous update during startup
            updateOperators();
            //Schedule periodic updates
            executor.scheduleAtFixedRate(this::updateOperators, 60, 60, TimeUnit.MINUTES);
        }
    }

    private void updateOperators() {
        try {
            final List<Operator> allOperators = getAllOperators();
            for (Operator operator : allOperators) {
                operatorCache.put(operator.getOperatorRef(), operator);
            }
            LOG.info("OperatorCache populated with {} operators", operatorCache.size());
        }
        catch (WebClientException e) {
            LOG.error("Error while getting all operators", e);
        }
    }

    public static Operator getOperator(String operatorRef) {
        return operatorCache.get(operatorRef);
    }

    private List<Operator> getAllOperators() {
        String query = "{\"query\":\"{operators {operatorRef:id name:name}}\",\"variables\":null}";

        Data data = graphQLClient.executeQuery(query);
        metricsService.markJourneyPlannerRequest("operators");
        if (data != null) {
            return data.operators;
        }
        return emptyList();
    }
}
