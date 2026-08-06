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
     * operating days compare equal. Returning a Map would additionally collapse the
     * *results* onto one key; returning a List avoids that half of the problem.
     * <p>
     * It does not avoid the other half: Spring GraphQL's {@code AnnotatedControllerConfigurer}
     * calls {@code dataLoader.load(source)} per object, and DataLoader deduplicates *keys* by
     * equals/hashCode before this method is ever invoked - upstream of whatever this method
     * returns. Two journeys that compare equal are therefore batched as a single key and both
     * receive the one match computed for it, regardless of the return shape here. What
     * actually prevents that collapse is {@link GraphQlBatchLoaderConfiguration}, which
     * disables DataLoader's per-key cache so each object is looked up by identity instead of
     * by value - see its Javadoc for the full mechanism, including why this also affects
     * subscription staleness.
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
