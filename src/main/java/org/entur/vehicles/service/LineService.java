package org.entur.vehicles.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.entur.vehicles.data.model.Line;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class LineService {

    @Autowired
    private JourneyPlannerGraphQLClient graphQLClient;

    private boolean lineCacheEnabled;
    public LineService(@Value("${vehicle.linecache.enabled:false}") boolean lineCacheEnabled) {
        this.lineCacheEnabled = lineCacheEnabled;
    }

    private LoadingCache<String, Line> lineCache = CacheBuilder.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public Line load(String lineRef) {
                    if (lineCacheEnabled) {
                        return lookupLine(lineRef);
                    }
                    return new Line(lineRef);
                }
            });

    @PostConstruct
    private void warmUpLineCache() {
        if (lineCacheEnabled) {
            try {
                final List<Line> allLines = getAllLines();
                for (Line line : allLines) {
                    lineCache.put(line.getLineRef(), line);
                }
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public Line getLine(String lineRef) throws ExecutionException {
        return lineCache.get(lineRef);
    }

    private Line lookupLine(String lineRef) {
        String query = "{\"query\":\"{line(id:\\\"" + lineRef + "\\\"){lineId:id publicCode lineName:name}}\",\"variables\":null}";

        Data data = null;
        try {
            data = graphQLClient.executeQuery(query);
        } catch (IOException e) {
            // Ignore - return empty Line
        }
        if (data != null && data.line != null) {
            return data.line;
        }
        return new Line(lineRef);
    }

    private List<Line> getAllLines() throws IOException {
        String query = "{\"query\":\"{lines {lineRef:id publicCode lineName:name}}\",\"variables\":null}";

        Data data = graphQLClient.executeQuery(query);
        if (data != null) {
            return data.lines;
        }
        return null;
    }
}
