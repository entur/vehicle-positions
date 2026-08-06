package org.entur.vehicles.graphql;

import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.SituationMatcher;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.repository.SituationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Attaches situations to estimated timetable data.
 * <p>
 * Batched deliberately: GraphQL resolves a field once per parent object, so a per-object
 * resolver would rebuild the match index for every journey in the result. A batch resolver
 * builds it once per batch.
 * <p>
 * Neither method runs unless the client selects the field, so consumers that do not ask for
 * situations pay nothing.
 */
@Controller
public class SituationJoinController {

    private final SituationRepository situationRepository;

    public SituationJoinController(@Autowired SituationRepository situationRepository) {
        this.situationRepository = situationRepository;
    }

    /**
     * Returns a list positionally aligned with {@code timetables} - NOT a Map keyed by the
     * source objects. {@code EstimatedTimetableUpdate} inherits value-based equals/hashCode
     * from AbstractUpdate that ignore datedServiceJourney, so two journeys on different
     * operating days compare equal and a Map would collapse them.
     */
    @BatchMapping(typeName = "EstimatedTimetableUpdate", field = "situations")
    public List<List<SituationUpdate>> timetableSituations(List<EstimatedTimetableUpdate> timetables) {
        SituationMatcher matcher = matcher();
        List<List<SituationUpdate>> result = new ArrayList<>(timetables.size());
        for (EstimatedTimetableUpdate timetable : timetables) {
            result.add(matcher.match(timetable));
        }
        return result;
    }

    /** Positionally aligned with {@code calls}, for the same reason as above. */
    @BatchMapping(typeName = "Call", field = "situations")
    public List<List<SituationUpdate>> callSituations(List<Call> calls) {
        SituationMatcher matcher = matcher();
        List<List<SituationUpdate>> result = new ArrayList<>(calls.size());
        for (Call call : calls) {
            result.add(matcher.match(call));
        }
        return result;
    }

    private SituationMatcher matcher() {
        return new SituationMatcher(situationRepository.getSituations(null));
    }
}
