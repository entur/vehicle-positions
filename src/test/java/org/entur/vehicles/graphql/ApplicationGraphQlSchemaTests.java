package org.entur.vehicles.graphql;

import graphql.ExecutionResult;
import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectedVehicleJourneyRecord;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    // Every fixture below uses a line/quay/situationNumber unique to its own test. The
    // repositories are shared, mutable, singleton beans that persist state across every test
    // method in this class (and are never reset between them), so a query that isn't scoped
    // to its own fixture's identifiers would pass or fail depending on what other test
    // methods happened to run first - see the join test's history for why this matters.
    private static final String QUAY_JOIN_LINE = "TST:Line:quay-join-probe";
    private static final String QUAY_JOIN_DSJ = "TST:DatedServiceJourney:quay-join-probe";
    private static final String QUAY_JOIN_QUAY_1 = "NSR:Quay:quay-join-probe-1";
    private static final String QUAY_JOIN_QUAY_2 = "NSR:Quay:quay-join-probe-2";
    private static final String QUAY_JOIN_SITUATION = "TST:SituationNumber:quay-join-probe";
    private static final String QUAY_JOIN_LINE_SITUATION = "TST:SituationNumber:quay-join-probe-line";

    private static final String TWO_JOURNEY_LINE = "TST:Line:two-journey-probe";
    private static final String TWO_JOURNEY_DSJ_A = "TST:DatedServiceJourney:two-journey-probe-A";
    private static final String TWO_JOURNEY_DSJ_B = "TST:DatedServiceJourney:two-journey-probe-B";
    private static final String TWO_JOURNEY_QUAY_A = "NSR:Quay:two-journey-probe-A";
    private static final String TWO_JOURNEY_QUAY_B = "NSR:Quay:two-journey-probe-B";
    private static final String TWO_JOURNEY_SITUATION = "TST:SituationNumber:two-journey-probe";

    private static final String STALE_LINE = "TST:Line:subscription-stale-probe";
    private static final String STALE_DSJ = "TST:DatedServiceJourney:subscription-stale-probe";
    private static final String STALE_QUAY = "NSR:Quay:subscription-stale-probe";
    private static final String STALE_SITUATION = "TST:SituationNumber:subscription-stale-probe";

    @Test
    void contextLoads() {
        // If the schema fails to parse, or a field can't be wired to any resolver/getter,
        // context startup itself fails - this alone is a meaningful assertion.
    }

    @Test
    void situationsQueryResolvesEveryFieldIncludingSetAndDurationCoercions() {
        situationRepository.add(situationRecord());

        // Scoped to this fixture's own situationNumber: situationRepository is a shared
        // singleton never reset between test methods, and an unfiltered situations query
        // would return every situation any method in this class has ever added (including
        // "TST:SituationNumber:closes" below, which shares the same lineRef). An unfiltered
        // query previously indexed into situations[0], which only happened to land on this
        // fixture because of ConcurrentHashMap's incidental bucket layout - it was not true by
        // construction. See the join test's history in this file for the same class of bug.
        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["TST:SituationNumber:schema-wiring"]) {
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

    /**
     * The two fields partition rather than overlap: a situation scoped to a stop is reported
     * against that call, a situation affecting the line as a whole against the journey, and
     * neither appears in both places. A client rendering both lists therefore never has to
     * deduplicate across them.
     */
    @Test
    void situationsResolveOnBothTheJourneyAndTheSpecificCall() {
        situationRepository.add(situationAffectingStop(QUAY_JOIN_SITUATION, QUAY_JOIN_QUAY_1));
        situationRepository.add(situationAffectingLine(QUAY_JOIN_LINE_SITUATION, QUAY_JOIN_LINE));
        timetableRepository.add(journeyCallingAt(QUAY_JOIN_LINE, QUAY_JOIN_DSJ, QUAY_JOIN_QUAY_1, QUAY_JOIN_QUAY_2));

        String document = """
                query {
                  timetables(lineRef: "%s") {
                    serviceJourney { id }
                    situations { situationNumber }
                    calls {
                      stopPoint { id }
                      situations { situationNumber }
                    }
                  }
                }
                """.formatted(QUAY_JOIN_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-join", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        // The line-wide situation is the journey's; the stop-scoped one is not repeated here.
        List<String> journeySituations = situationNumbersOf(response.field("timetables[0].situations").getValue());
        assertThat(journeySituations)
                .withFailMessage("the journey lists the line-wide situation only - the stop-scoped one "
                        + "is reported against its call instead, so a client need not deduplicate")
                .containsExactly(QUAY_JOIN_LINE_SITUATION);

        // The stop-triggered situation belongs to the call it came from.
        List<String> firstCallSituations =
                situationNumbersOf(response.field("timetables[0].calls[0].situations").getValue());
        assertThat(firstCallSituations).containsExactly(QUAY_JOIN_SITUATION);

        // ...and not to the journey's other call.
        List<String> secondCallSituations =
                situationNumbersOf(response.field("timetables[0].calls[1].situations").getValue());
        assertThat(secondCallSituations).isEmpty();
    }

    @Test
    void aTimetablesQueryThatDoesNotSelectSituationsDoesNoMatchingWork() {
        situationRepository.add(situationAffectingStop(QUAY_JOIN_SITUATION, QUAY_JOIN_QUAY_1));
        timetableRepository.add(journeyCallingAt(QUAY_JOIN_LINE, QUAY_JOIN_DSJ, QUAY_JOIN_QUAY_1, QUAY_JOIN_QUAY_2));

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
     * DataLoader (java-dataloader) defaults to caching a batch loader's results per key via
     * equals/hashCode, and Spring GraphQL's {@code @BatchMapping} infrastructure calls
     * {@code dataLoader.load(source)} once per parent object - so a cache hit never reaches
     * {@code SituationJoinController} at all. {@code EstimatedTimetableUpdate} inherits
     * {@code AbstractUpdate}'s value-based equals/hashCode (serviceJourney, operator,
     * codespace, mode, line), and the {@code DatedVehicleJourneyRef} ingest path used by
     * {@code journeyCallingAt} never populates the plain {@code serviceJourney} field, so it
     * plays no part in equality either. Two distinct journeys sharing a line, operator,
     * codespace and mode are therefore mutually equal keys: with DataLoader's default cache
     * on, the second key registered in a batch is silently answered from the first key's
     * cached result instead of being matched itself. {@code GraphQlBatchLoaderConfiguration}
     * disables that cache; without it, this test fails with one journey's situations answering
     * for the other's.
     */
    @Test
    void twoDistinctJourneysOnTheSameLineGetTheirOwnSituationsNotEachOthers() {
        // Scoped to journey A's dated service journey, not to a stop: a stop-scoped situation
        // is reported against its call rather than on the journey, which would leave both
        // journeys' situations empty and make this test pass no matter what.
        situationRepository.add(situationAffectingDatedServiceJourney(TWO_JOURNEY_SITUATION, TWO_JOURNEY_DSJ_A));

        // Registered "B then A" on purpose - nothing about which key a DataLoader batch sees
        // first should change the outcome.
        timetableRepository.add(journeyCallingAt(TWO_JOURNEY_LINE, TWO_JOURNEY_DSJ_B, TWO_JOURNEY_QUAY_B));
        timetableRepository.add(journeyCallingAt(TWO_JOURNEY_LINE, TWO_JOURNEY_DSJ_A, TWO_JOURNEY_QUAY_A));

        String document = """
                query {
                  timetables(lineRef: "%s") {
                    datedServiceJourney { id }
                    situations { situationNumber }
                  }
                }
                """.formatted(TWO_JOURNEY_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-two-journeys", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<Map<String, Object>> timetables = response.field("timetables").getValue();
        assertThat(timetables).hasSize(2);

        Map<String, List<String>> situationsByJourneyId = new HashMap<>();
        for (Map<String, Object> timetable : timetables) {
            situationsByJourneyId.put(datedServiceJourneyId(timetable), situationNumbersOf(timetable.get("situations")));
        }

        assertThat(situationsByJourneyId.get(TWO_JOURNEY_DSJ_A))
                .withFailMessage("the situation names journey A's dated service journey, so A must see it")
                .containsExactly(TWO_JOURNEY_SITUATION);
        assertThat(situationsByJourneyId.get(TWO_JOURNEY_DSJ_B))
                .withFailMessage("the situation does not name journey B, which must not inherit "
                        + "journey A's situations through a shared DataLoader cache key")
                .isEmpty();
    }

    /**
     * The {@code DataLoaderRegistry} backing {@code @BatchMapping} is built once per
     * subscription (see {@code GraphQlBatchLoaderConfiguration}) and reused for every event
     * delivered on it, for the connection's whole lifetime. With DataLoader's default per-key
     * cache, a journey published again after a situation starts affecting it would keep
     * resolving {@code situations} from its first, pre-situation answer instead of being
     * re-matched. Filtering by a unique {@code lineRef} keeps this test isolated from whatever
     * other journeys the shared, never-reset repository accumulates across the test class.
     */
    @Test
    void subscriptionJourneyFieldPicksUpASituationAddedBetweenTwoEventsForTheSameJourney() throws InterruptedException {
        // Present before subscribing, so it arrives as the subscription's initial snapshot -
        // the first of the two events under test, with no situation attached yet.
        timetableRepository.add(journeyCallingAt(STALE_LINE, STALE_DSJ, STALE_QUAY));

        String document = """
                subscription {
                  timetables(lineRef: "%s", bufferSize: 1, bufferTime: 50) {
                    datedServiceJourney { id }
                    situations { situationNumber }
                  }
                }
                """.formatted(STALE_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-subscription-stale", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        CountDownLatch situationSeen = new CountDownLatch(1);
        AtomicReference<List<String>> lastSituations = new AtomicReference<>(List.of());

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timetables = (List<Map<String, Object>>) data.get("timetables");
            for (Map<String, Object> timetable : timetables) {
                if (STALE_DSJ.equals(datedServiceJourneyId(timetable))) {
                    List<String> situations = situationNumbersOf(timetable.get("situations"));
                    lastSituations.set(situations);
                    if (!situations.isEmpty()) {
                        situationSeen.countDown();
                    }
                }
            }
        });

        try {
            // The situation appears only after the first (initial-snapshot) event has gone out.
            // Scoped to the dated service journey rather than to the stop, because a
            // stop-scoped situation is reported against its call and never on the journey.
            situationRepository.add(situationAffectingDatedServiceJourney(STALE_SITUATION, STALE_DSJ));

            // The same journey - equal to its earlier self under AbstractUpdate's value
            // equality - republished. This is the second event under test.
            timetableRepository.add(journeyCallingAt(STALE_LINE, STALE_DSJ, STALE_QUAY));

            assertThat(situationSeen.await(5, TimeUnit.SECONDS))
                    .withFailMessage("the second event for this journey must reflect the situation added "
                            + "since the first event - last seen situations: " + lastSituations.get()
                            + " - check DataLoader caching in GraphQlBatchLoaderConfiguration")
                    .isTrue();
        } finally {
            subscription.dispose();
        }
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

    @SuppressWarnings("unchecked")
    private String datedServiceJourneyId(Map<String, Object> timetable) {
        return (String) ((Map<String, Object>) timetable.get("datedServiceJourney")).get("id");
    }

    private EstimatedVehicleJourneyRecord journeyCallingAt(String lineRef, String datedServiceJourneyId, String... stopRefs) {
        List<EstimatedCallRecord> calls = new ArrayList<>();
        int order = 1;
        for (String stopRef : stopRefs) {
            EstimatedCallRecord call = new EstimatedCallRecord();
            call.setStopPointRef(stopRef);
            call.setOrder(order);
            call.setAimedArrivalTime(ZonedDateTime.now().plusMinutes(order * 5L).toString());
            call.setAimedDepartureTime(ZonedDateTime.now().plusMinutes(order * 5L + 1).toString());
            calls.add(call);
            order++;
        }

        EstimatedVehicleJourneyRecord journey = new EstimatedVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef(lineRef);
        journey.setDatedVehicleJourneyRef(datedServiceJourneyId);
        journey.setRecordedAtTime(ZonedDateTime.now().toString());
        journey.setMonitored(true);
        journey.setEstimatedCalls(calls);
        return journey;
    }

    private PtSituationElementRecord situationAffectingStop(String situationNumber, String stopRef) {
        PtSituationElementRecord record = openSituation(situationNumber);

        AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
        stopPoint.setStopPointRef(stopRef);

        AffectsRecord affects = new AffectsRecord();
        affects.setStopPoints(List.of(stopPoint));
        record.setAffects(affects);

        return record;
    }

    /**
     * Names a line and nothing else, so it matches at journey level. The stop-scoped
     * fixtures above deliberately cannot: a situation matching one of the journey's calls
     * is reported against that call instead of on the journey.
     */
    private PtSituationElementRecord situationAffectingLine(String situationNumber, String lineRef) {
        PtSituationElementRecord record = openSituation(situationNumber);

        AffectedLineRecord line = new AffectedLineRecord();
        line.setLineRef(lineRef);

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setAffectedLines(List.of(line));

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        record.setAffects(affects);

        return record;
    }

    /**
     * Names one dated service journey and no stop, so it matches at journey level and only
     * for that journey - which is what lets a test tell two journeys on the same line apart
     * through the {@code situations} field.
     */
    private PtSituationElementRecord situationAffectingDatedServiceJourney(String situationNumber,
                                                                          String datedServiceJourneyId) {
        PtSituationElementRecord record = openSituation(situationNumber);

        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setDatedVehicleJourneyRefs(List.of(datedServiceJourneyId));

        AffectsRecord affects = new AffectsRecord();
        affects.setVehicleJourneys(List.of(journey));
        record.setAffects(affects);

        return record;
    }

    /** The scaffolding every fixture shares: published, open-ended, no validity periods. */
    private PtSituationElementRecord openSituation(String situationNumber) {
        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
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
