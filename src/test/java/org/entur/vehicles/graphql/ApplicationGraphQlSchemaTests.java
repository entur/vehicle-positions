package org.entur.vehicles.graphql;

import graphql.ExecutionResult;
import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.repository.TimetableRepository;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * No test anywhere in this suite previously booted a Spring context, so nothing verified
 * that {@code vehicle-updates.graphqls} actually wires to the resolvers - e.g. that every
 * {@code Situation} field resolves against a {@code SituationUpdate} getter, that
 * {@code Affects.vehicleModes} (a {@code Set}) coerces to {@code [VehicleModeEnumeration]},
 * that {@code age: Duration} serializes on the output side, and that
 * {@code lastUpdatedEpochSecond: Float} accepts a boxed {@code Long}. A mismatch there is a
 * silent runtime null, not a compile error.
 * <p>
 * All three Pub/Sub subscribers and every external lookup (line, operator, NSR,
 * service journey) are disabled via {@code src/test/resources/application.properties},
 * so this stays hermetic - no GCP credentials and no network access required.
 * <p>
 * Uses {@link ExecutionGraphQlService} - the transport-agnostic core that Spring Boot's
 * GraphQL autoconfiguration always registers - directly, rather than the
 * {@code spring-graphql-test} module's {@code GraphQlTester}/{@code @GraphQlTest}, which
 * is not on this project's classpath (spring-boot-starter-graphql only, no added
 * dependency) and would need to be added to the build to use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationGraphQlSchemaTests {

    @Autowired
    private ExecutionGraphQlService graphQlService;

    // A spy rather than a plain @Autowired bean: the opt-out test below needs to observe,
    // not just infer, that getSituations() is never called when the client doesn't select
    // the situations field.
    @MockitoSpyBean
    private SituationRepository situationRepository;

    @Autowired
    private TimetableRepository timetableRepository;

    @Test
    void contextLoads() {
        // If the schema fails to parse, or a field can't be wired to any resolver/getter,
        // context startup itself fails - this alone is a meaningful assertion.
    }

    @Test
    void situationsQueryResolvesEveryFieldIncludingSetAndDurationCoercions() {
        situationRepository.add(situationRecord());

        String document = """
                query {
                  situations(includeClosed: true) {
                    situationNumber
                    codespace { codespaceId }
                    version
                    progress
                    severity
                    reportType
                    lastUpdatedEpochSecond
                    age
                    openEnded
                    affects {
                      vehicleModes
                      lines { lineRef }
                    }
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-request", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();
        assertThat(response.isValid()).isTrue();

        String situationNumber = response.field("situations[0].situationNumber").getValue();
        assertThat(situationNumber).isEqualTo("TST:SituationNumber:schema-wiring");

        String codespaceId = response.field("situations[0].codespace.codespaceId").getValue();
        assertThat(codespaceId).isEqualTo("TST");

        Integer version = response.field("situations[0].version").getValue();
        assertThat(version).isEqualTo(1);

        String progress = response.field("situations[0].progress").getValue();
        assertThat(progress).isEqualTo("published");

        String severity = response.field("situations[0].severity").getValue();
        assertThat(severity).isEqualTo("severe");

        // Boxed Long (SituationUpdate.getLastUpdatedEpochSecond()) coerced to GraphQL Float.
        Number lastUpdatedEpochSecond = response.field("situations[0].lastUpdatedEpochSecond").getValue();
        assertThat(lastUpdatedEpochSecond.doubleValue()).isPositive();

        // Duration serialized on the output side by DurationScalarConfiguration.
        String age = response.field("situations[0].age").getValue();
        assertThat(age).startsWith("PT");

        // Set<VehicleModeEnumeration> coerced to a GraphQL list.
        List<String> vehicleModes = response.field("situations[0].affects.vehicleModes").getValue();
        assertThat(vehicleModes).containsExactly("BUS");

        String lineRef = response.field("situations[0].affects.lines[0].lineRef").getValue();
        assertThat(lineRef).isEqualTo("TST:Line:1");
    }

    /**
     * {@code Subscription.situations} declares {@code includeClosed: Boolean = true} while
     * {@code Query.situations} declares {@code = false}. That asymmetry is deliberate: a
     * subscriber has to observe a situation closing in order to drop it from display, and the
     * publisher applies the same filter to the live stream as to the initial snapshot - so with
     * the query's default, a close would be published to the sink and then filtered out before
     * reaching anyone.
     * <p>
     * This executes a real subscription document that OMITS the argument, so the schema default
     * is what is under test. Reverting {@code vehicle-updates.graphqls} to {@code = false} makes
     * this fail; asserting on a hand-built filter would not.
     */
    @Test
    void subscriptionDefaultDeliversTheCloseTransitionWithoutAskingForIt() throws InterruptedException {
        situationRepository.add(situation("TST:SituationNumber:closes", 1, "PUBLISHED"));

        String document = """
                subscription {
                  situations(bufferSize: 1, bufferTime: 50) {
                    situationNumber
                    progress
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-subscription", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        CountDownLatch closeReceived = new CountDownLatch(1);

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> situations = (List<Map<String, Object>>) data.get("situations");
            for (Map<String, Object> situation : situations) {
                if ("TST:SituationNumber:closes".equals(situation.get("situationNumber"))
                        && "closed".equals(situation.get("progress"))) {
                    closeReceived.countDown();
                }
            }
        });

        try {
            situationRepository.add(situation("TST:SituationNumber:closes", 2, "CLOSED"));

            assertThat(closeReceived.await(5, TimeUnit.SECONDS))
                    .withFailMessage("a subscriber that never mentioned includeClosed must still be told "
                            + "the situation closed - check the Subscription.situations default in "
                            + "vehicle-updates.graphqls")
                    .isTrue();
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void situationsResolveOnBothTheJourneyAndTheSpecificCall() {
        situationRepository.add(quaySituationRecord());
        timetableRepository.add(journeyCallingAtQuay());

        String document = """
                query {
                  timetables {
                    serviceJourney { id }
                    situations { situationNumber }
                    calls {
                      stopPoint { id }
                      situations { situationNumber }
                    }
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-join", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<String> journeySituations = situationNumbersOf(response.field("timetables[0].situations").getValue());
        assertThat(journeySituations).containsExactly("TST:SituationNumber:quay");

        // The stop-triggered situation belongs to the call it came from.
        List<String> firstCallSituations =
                situationNumbersOf(response.field("timetables[0].calls[0].situations").getValue());
        assertThat(firstCallSituations).containsExactly("TST:SituationNumber:quay");

        // ...and not to the journey's other call.
        List<String> secondCallSituations =
                situationNumbersOf(response.field("timetables[0].calls[1].situations").getValue());
        assertThat(secondCallSituations).isEmpty();
    }

    @Test
    void aTimetablesQueryThatDoesNotSelectSituationsDoesNoMatchingWork() {
        situationRepository.add(quaySituationRecord());
        timetableRepository.add(journeyCallingAtQuay());

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(
                        "query { timetables { serviceJourney { id } calls { stopPoint { id } } } }",
                        null, Map.of(), Map.of(), "test-optout", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        // The resolvers are the only callers of getSituations in this context, so never
        // touching the repository proves no index was built and no matching was done.
        verify(situationRepository, never()).getSituations(any());
    }

    /**
     * {@code response.field(...)} does not support a {@code [*]} projection - its path parser
     * only accepts numeric list indices in brackets - so the list of maps is read as-is and
     * the numbers extracted here instead.
     */
    @SuppressWarnings("unchecked")
    private List<String> situationNumbersOf(Object value) {
        List<Map<String, Object>> situations = (List<Map<String, Object>>) value;
        return situations.stream().map(s -> (String) s.get("situationNumber")).toList();
    }

    private EstimatedVehicleJourneyRecord journeyCallingAtQuay() {
        EstimatedCallRecord first = new EstimatedCallRecord();
        first.setStopPointRef("NSR:Quay:1");
        first.setOrder(1);
        first.setAimedArrivalTime(ZonedDateTime.now().plusMinutes(5).toString());
        first.setAimedDepartureTime(ZonedDateTime.now().plusMinutes(6).toString());

        EstimatedCallRecord second = new EstimatedCallRecord();
        second.setStopPointRef("NSR:Quay:2");
        second.setOrder(2);
        second.setAimedArrivalTime(ZonedDateTime.now().plusMinutes(10).toString());
        second.setAimedDepartureTime(ZonedDateTime.now().plusMinutes(11).toString());

        EstimatedVehicleJourneyRecord journey = new EstimatedVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef("TST:Line:1");
        journey.setDatedVehicleJourneyRef("TST:DatedServiceJourney:quay-join");
        journey.setRecordedAtTime(ZonedDateTime.now().toString());
        journey.setMonitored(true);
        journey.setEstimatedCalls(List.of(first, second));
        return journey;
    }

    private PtSituationElementRecord quaySituationRecord() {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber("TST:SituationNumber:quay");
        record.setParticipantRef("TST");
        record.setVersion(1);
        record.setProgress("PUBLISHED");
        record.setSeverity("SEVERE");
        record.setReportType("general");
        record.setCreationTime(ZonedDateTime.now().minusMinutes(1).toString());
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());

        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef("NSR:Quay:1");

        AffectsRecord affects = new AffectsRecord();
        affects.setStopPoints(List.of(stopPoint));
        record.setAffects(affects);

        return record;
    }

    private PtSituationElementRecord situation(String situationNumber, int version, String progress) {
        PtSituationElementRecord record = situationRecord();
        record.setSituationNumber(situationNumber);
        record.setVersion(version);
        record.setProgress(progress);
        return record;
    }

    private PtSituationElementRecord situationRecord() {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber("TST:SituationNumber:schema-wiring");
        record.setParticipantRef("TST");
        record.setVersion(1);
        record.setProgress("PUBLISHED");
        record.setSeverity("SEVERE");
        record.setReportType("general");
        record.setCreationTime(ZonedDateTime.now().minusMinutes(1).toString());
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());

        AffectedLineRecord line = new AffectedLineRecord();
        line.setLineRef("TST:Line:1");

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setVehicleMode("BUS");
        network.setAffectedLines(List.of(line));

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        record.setAffects(affects);

        return record;
    }
}
