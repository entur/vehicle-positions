package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import org.entur.vehicles.data.model.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class LineService {

    private static final Logger LOG = LoggerFactory.getLogger(LineService.class);

    @Autowired
    private JourneyPlannerGraphQLClient graphQLClient;

    private boolean lineLookupEnabled;
    public LineService(@Value("${vehicle.line.lookup.enabled:false}") boolean lineLookupEnabled) {
        this.lineLookupEnabled = lineLookupEnabled;
    }

    private LoadingCache<String, Line> lineCache = CacheBuilder.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public Line load(String lineRef) {
                    if (lineLookupEnabled) {
                        return lookupLine(lineRef);
                    }
                    return new Line(lineRef);
                }
            });

    @PostConstruct
    private void warmUpLineCache() {
        if (lineLookupEnabled) {
            try {
                final List<Line> allLines = getAllLines();
                for (Line line : allLines) {
                    lineCache.put(line.getLineRef(), line);
                }
                LOG.info("LineCache initialized with {} lines", lineCache.size());
            }
            catch (WebClientException e) {
                LOG.error("Error while getting all lines", e);
            }
        }
    }


    public Line getLine(String lineRef) throws ExecutionException {
        return lineCache.get(lineRef);
    }

    private Line lookupLine(String lineRef) {
        String query = "{\"query\":\"{line(id:\\\"" + lineRef + "\\\"){lineRef:id publicCode lineName:name}}\",\"variables\":null}";

        Data data = null;
        try {
            data = graphQLClient.executeQuery(query);
        } catch (WebClientException e) {
            // Ignore - return empty Line
        }
        if (data != null && data.line != null) {
            return data.line;
        }
        return new Line(lineRef);
    }

    private List<Line> getAllLines() {
        String query = "{\"query\":\"{lines {lineRef:id publicCode lineName:name}}\",\"variables\":null}";

        Data data = graphQLClient.executeQuery(query);
        if (data != null) {
            return data.lines;
        }
        return null;
    }
}
