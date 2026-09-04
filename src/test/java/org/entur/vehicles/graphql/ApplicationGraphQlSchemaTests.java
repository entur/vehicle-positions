package org.entur.vehicles.graphql;

import graphql.ExecutionResult;
import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedRouteRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPlaceRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.LocationRecord;
import org.entur.avro.realtime.siri.model.MonitoredVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.StopPointsRecord;
import org.entur.avro.realtime.siri.model.VehicleActivityRecord;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.repository.SituationRepository;
import org.entur.vehicles.repository.TimetableRepository;
import org.entur.vehicles.repository.VehicleRepository;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.planned.PlannedDataset;
import org.entur.vehicles.service.planned.PlannedDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Autowired
    private VehicleRepository vehicleRepository;

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

    private static final String REPUBLISH_LINE = "TST:Line:republish-probe";
    private static final String REPUBLISH_DSJ = "TST:DatedServiceJourney:republish-probe";
    private static final String REPUBLISH_QUAY = "NSR:Quay:republish-probe";
    private static final String REPUBLISH_SITUATION = "TST:SituationNumber:republish-probe";

    private static final String QUAY_ANCESTOR_LINE = "TST:Line:ancestor-probe";
    private static final String QUAY_ANCESTOR_DSJ = "TST:DatedServiceJourney:ancestor-probe";
    private static final String QUAY_ANCESTOR_QUAY = "NSR:Quay:ancestor-probe";
    private static final String QUAY_ANCESTOR_STOP_PLACE = "NSR:StopPlace:ancestor-probe";
    private static final String QUAY_ANCESTOR_SITUATION = "TST:SituationNumber:ancestor-probe";
    private static final String TAGGED_PLACE_LINE = "TST:Line:tagged-place-probe";
    private static final String TAGGED_PLACE_DSJ = "TST:DatedServiceJourney:tagged-place-probe";
    private static final String TAGGED_PLACE_QUAY = "NSR:Quay:tagged-place-probe";
    private static final String TAGGED_PLACE_STOP_PLACE = "NSR:StopPlace:tagged-place-probe";
    private static final String TAGGED_PLACE_SITUATION = "TST:SituationNumber:tagged-place-probe";

    private static final String INVALID_LOCATION_QUERY_LINE = "TST:Line:invalid-location-query-probe";
    private static final String INVALID_LOCATION_QUERY_VALID_VEHICLE = "TST:Vehicle:invalid-location-query-valid";
    private static final String INVALID_LOCATION_QUERY_INVALID_VEHICLE = "TST:Vehicle:invalid-location-query-invalid";

    private static final String INVALID_LOCATION_SUBSCRIPTION_LINE = "TST:Line:invalid-location-subscription-probe";
    private static final String INVALID_LOCATION_SUBSCRIPTION_VALID_VEHICLE = "TST:Vehicle:invalid-location-subscription-valid";
    private static final String INVALID_LOCATION_SUBSCRIPTION_INVALID_VEHICLE = "TST:Vehicle:invalid-location-subscription-invalid";

    private static final String INVALID_LOCATION_SUBSCRIPTION_INCLUDE_LINE = "TST:Line:invalid-location-subscription-include-probe";
    private static final String INVALID_LOCATION_SUBSCRIPTION_INCLUDE_INVALID_VEHICLE =
            "TST:Vehicle:invalid-location-subscription-include-invalid";

    private static final String AFFECTED_GEOMETRY_SITUATION = "TST:SituationNumber:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_DSJ = "TST:DatedServiceJourney:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_SJ = "TST:ServiceJourney:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_PATTERN = "TST:JourneyPattern:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_LINK = "TST:ServiceLink:affected-geometry-probe";
    private static final String AFFECTED_GEOMETRY_STOP_1 = "NSR:StopPlace:affected-geometry-probe-1";
    private static final String AFFECTED_GEOMETRY_STOP_2 = "NSR:StopPlace:affected-geometry-probe-2";
    private static final String AFFECTED_GEOMETRY_OPT_OUT_SITUATION = "TST:SituationNumber:affected-geometry-opt-out";
    private static final String AFFECTED_GEOMETRY_OPT_OUT_DSJ = "TST:DatedServiceJourney:affected-geometry-opt-out";
    private static final String MIXED_GEOMETRY_SITUATION = "TST:SituationNumber:mixed-geometry-probe";
    private static final String MIXED_GEOMETRY_KNOWN_DSJ = "TST:DatedServiceJourney:mixed-geometry-known";
    private static final String MIXED_GEOMETRY_UNKNOWN_DSJ = "TST:DatedServiceJourney:mixed-geometry-unknown";
    private static final String WHOLE_JOURNEY_SITUATION = "TST:SituationNumber:whole-journey-probe";
    private static final String WHOLE_JOURNEY_DSJ = "TST:DatedServiceJourney:whole-journey-probe";

    private static final String AFFECTED_LINE_GEOMETRY_SITUATION = "TST:SituationNumber:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_LINE = "TST:Line:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_LINK = "TST:ServiceLink:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_PATTERN = "TST:JourneyPattern:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_SJ = "TST:ServiceJourney:affected-line-geometry";
    private static final String AFFECTED_LINE_GEOMETRY_STOP_1 = "NSR:StopPlace:affected-line-geometry-1";
    private static final String AFFECTED_LINE_GEOMETRY_STOP_2 = "NSR:StopPlace:affected-line-geometry-2";

    /**
     * NSR lookup is disabled in the test context, so the real hierarchy is empty and this test
     * has to supply one. It replaces NSRService with a stub whose only knowledge is that the
     * probe quay sits under the probe stop place - which is exactly the relationship under test.
     */
    @MockitoBean
    private NSRService nsrService;

    /**
     * Planned data is disabled in the test context (an empty dataset). Replacing the bean
     * lets the catalogue tests below serve a hand-built dataset; every other test sees the
     * same "nothing known" answers the disabled bean gives (mock defaults: null / false).
     */
    @MockitoBean
    private PlannedDataService plannedDataService;

    @BeforeEach
    void stubPlannedDataDefaults() {
        when(plannedDataService.current()).thenReturn(PlannedDataset.EMPTY);
    }

    /**
     * Every other test in this class was written against the real (disabled) NSRService, whose
     * fallback behaviour is {@code getStop(ref) -> new StopPoint(ref)} and
     * {@code ancestorsOf(ref) -> Set.of()}. {@code @MockitoBean} substitutes a bare mock for the
     * whole class - resetting its stubbing after every test, not restoring the original bean -
     * so without reinstating that fallback here, every other test's call-level stop resolution
     * would silently start returning null. Individual tests (see
     * {@link #aSituationOnAStopPlaceReachesTheCallAtItsQuay}) layer more specific stubbing on
     * top, which Mockito prefers over this default.
     */
    @BeforeEach
    void stubNsrServiceDefaults() {
        when(nsrService.getStop(anyString())).thenAnswer(i -> new StopPoint(i.getArgument(0)));
        when(nsrService.ancestorsOf(anyString())).thenReturn(Set.of());
        when(nsrService.expandWithAncestors(anyString())).thenAnswer(i -> Set.of(i.getArgument(0)));
    }

    @Test
    void contextLoads() {
        // If the schema fails to parse, or a field can't be wired to any resolver/getter,
        // context startup itself fails - this alone is a meaningful assertion.
    }

    /**
     * Pins the schema default. {@code VehicleGraphQLTests.queryVehicles} calls the resolver
     * method directly in Java with a null argument, which only exercises
     * {@code Boolean.TRUE.equals(null)} - never the SDL default. This executes a real query
     * document with {@code includeInvalidLocations} omitted, so flipping
     * {@code Query.vehicles}'s {@code includeInvalidLocations: Boolean = false} to {@code = true}
     * in vehicle-updates.graphqls makes this fail; a hand-built filter would not.
     */
    @Test
    void vehiclesQueryOmittingTheArgumentExcludesTheKnownInvalidLocation() {
        vehicleRepository.addAll(List.of(
                vehicleActivity(INVALID_LOCATION_QUERY_VALID_VEHICLE, INVALID_LOCATION_QUERY_LINE, 59.911491, 10.757933),
                vehicleActivity(INVALID_LOCATION_QUERY_INVALID_VEHICLE, INVALID_LOCATION_QUERY_LINE, 0.0, 0.0)
        ));

        String document = """
                query {
                  vehicles(lineRef: "%s") {
                    vehicleId
                  }
                }
                """.formatted(INVALID_LOCATION_QUERY_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-invalid-location-query", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<String> vehicleIds = vehicleIdsOf(response.field("vehicles").getValue());
        assertThat(vehicleIds)
                .withFailMessage("includeInvalidLocations was omitted from the document, so the schema default "
                        + "(false) must apply - check Query.vehicles's `includeInvalidLocations: Boolean = false` "
                        + "in vehicle-updates.graphqls")
                .containsExactly(INVALID_LOCATION_QUERY_VALID_VEHICLE);
    }

    /**
     * Companion to the query test above, and the first test in this class to exercise
     * {@code Subscription.vehicles} at all - so it also proves the {@code @Argument} name
     * actually binds to the resolver parameter: Spring GraphQL silently ignores a schema
     * argument with no matching resolver parameter, so a misspelled {@code @Argument} name would
     * leave every other test in the suite green.
     * <p>
     * The invalid-location vehicle is present before subscribing (covering the initial snapshot,
     * which flows through the same {@code QueryFilter} as live events - see
     * {@code VehicleUpdateRxPublisher.getPublisher}) and republished after subscribing (covering
     * the live path). Neither publish may reach the subscriber.
     */
    @Test
    void vehiclesSubscriptionOmittingTheArgumentExcludesTheKnownInvalidLocationFromSnapshotAndLiveUpdates() throws InterruptedException {
        vehicleRepository.addAll(List.of(
                vehicleActivity(INVALID_LOCATION_SUBSCRIPTION_VALID_VEHICLE, INVALID_LOCATION_SUBSCRIPTION_LINE, 59.911491, 10.757933),
                vehicleActivity(INVALID_LOCATION_SUBSCRIPTION_INVALID_VEHICLE, INVALID_LOCATION_SUBSCRIPTION_LINE, 0.0, 0.0)
        ));

        String document = """
                subscription {
                  vehicles(lineRef: "%s", bufferSize: 1, bufferTime: 50) {
                    vehicleId
                  }
                }
                """.formatted(INVALID_LOCATION_SUBSCRIPTION_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-invalid-location-subscription", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        AtomicBoolean invalidVehicleSeen = new AtomicBoolean(false);
        // Counts down once for the initial snapshot and once for the live republish below - both
        // must deliver the valid vehicle for this test to prove anything about the invalid one.
        CountDownLatch validVehicleSeenTwice = new CountDownLatch(2);

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> vehicles = (List<Map<String, Object>>) data.get("vehicles");
            for (Map<String, Object> vehicle : vehicles) {
                String vehicleId = (String) vehicle.get("vehicleId");
                if (INVALID_LOCATION_SUBSCRIPTION_INVALID_VEHICLE.equals(vehicleId)) {
                    invalidVehicleSeen.set(true);
                } else if (INVALID_LOCATION_SUBSCRIPTION_VALID_VEHICLE.equals(vehicleId)) {
                    validVehicleSeenTwice.countDown();
                }
            }
        });

        try {
            // Republished after subscribing - the live path under test, as opposed to the
            // initial snapshot fixture added above.
            vehicleRepository.addAll(List.of(
                    vehicleActivity(INVALID_LOCATION_SUBSCRIPTION_VALID_VEHICLE, INVALID_LOCATION_SUBSCRIPTION_LINE, 59.911491, 10.757934),
                    vehicleActivity(INVALID_LOCATION_SUBSCRIPTION_INVALID_VEHICLE, INVALID_LOCATION_SUBSCRIPTION_LINE, 0.0, 0.0)
            ));

            assertThat(validVehicleSeenTwice.await(5, TimeUnit.SECONDS))
                    .withFailMessage("the valid vehicle must be delivered once in the initial snapshot and once "
                            + "on the live republish - if this times out, the live delivery path itself is "
                            + "broken, not just the invalid-location filtering under test")
                    .isTrue();
        } finally {
            subscription.dispose();
        }

        assertThat(invalidVehicleSeen.get())
                .withFailMessage("includeInvalidLocations was omitted, so the vehicle at 0,0 must never reach "
                        + "the subscriber - check Subscription.vehicles's @Argument name and its "
                        + ".withLocationValidity(...) call")
                .isFalse();
    }

    /**
     * The other half of the pair above: with {@code includeInvalidLocations: true} passed
     * explicitly, the vehicle at 0,0 must be delivered rather than filtered out.
     */
    @Test
    void vehiclesSubscriptionWithIncludeInvalidLocationsTrueDeliversTheInvalidLocation() throws InterruptedException {
        vehicleRepository.addAll(List.of(
                vehicleActivity(INVALID_LOCATION_SUBSCRIPTION_INCLUDE_INVALID_VEHICLE,
                        INVALID_LOCATION_SUBSCRIPTION_INCLUDE_LINE, 0.0, 0.0)
        ));

        String document = """
                subscription {
                  vehicles(lineRef: "%s", includeInvalidLocations: true, bufferSize: 1, bufferTime: 50) {
                    vehicleId
                  }
                }
                """.formatted(INVALID_LOCATION_SUBSCRIPTION_INCLUDE_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-invalid-location-subscription-include", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        CountDownLatch invalidVehicleSeen = new CountDownLatch(1);

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> vehicles = (List<Map<String, Object>>) data.get("vehicles");
            for (Map<String, Object> vehicle : vehicles) {
                if (INVALID_LOCATION_SUBSCRIPTION_INCLUDE_INVALID_VEHICLE.equals(vehicle.get("vehicleId"))) {
                    invalidVehicleSeen.countDown();
                }
            }
        });

        try {
            assertThat(invalidVehicleSeen.await(5, TimeUnit.SECONDS))
                    .withFailMessage("includeInvalidLocations: true was passed explicitly, so the vehicle at "
                            + "0,0 must appear in the initial snapshot")
                    .isTrue();
        } finally {
            subscription.dispose();
        }
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
                      affectedLines { line { lineRef } }
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

        String lineRef = response.field("situations[0].affects.affectedLines[0].line.lineRef").getValue();
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
     * The behaviour this whole mechanism exists for. The timetables subscription is fed only by
     * EstimatedTimetableUpdateRxPublisher, and the situations field is resolved once per emitted
     * event - so without SituationTriggeredRepublisher, a situation appearing after the journey
     * was published never reaches the subscriber at all.
     * <p>
     * Note there is deliberately NO ET update after the subscription opens: the journey is
     * published first, and the only thing that happens afterwards is the situation being added.
     */
    @Test
    void addingASituationRepublishesTheAffectedJourneyWithNoEtUpdate() throws InterruptedException {
        timetableRepository.add(journeyCallingAt(REPUBLISH_LINE, REPUBLISH_DSJ, REPUBLISH_QUAY));

        String document = """
                subscription {
                  timetables(lineRef: "%s", bufferSize: 1, bufferTime: 50) {
                    datedServiceJourney { id }
                    calls { situations { situationNumber } }
                  }
                }
                """.formatted(REPUBLISH_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-republish", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        CountDownLatch situationSeen = new CountDownLatch(1);

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timetables = (List<Map<String, Object>>) data.get("timetables");
            for (Map<String, Object> timetable : timetables) {
                if (REPUBLISH_DSJ.equals(datedServiceJourneyId(timetable))
                        && callSituationNumbers(timetable).contains(REPUBLISH_SITUATION)) {
                    situationSeen.countDown();
                }
            }
        });

        try {
            situationRepository.add(situationAffectingStop(REPUBLISH_SITUATION, REPUBLISH_QUAY));

            assertThat(situationSeen.await(10, TimeUnit.SECONDS))
                    .withFailMessage("a situation affecting a stored journey must reach an active "
                            + "timetables subscriber without waiting for an ET update - check "
                            + "SituationTriggeredRepublisher is wired into SituationRepository.add")
                    .isTrue();
        } finally {
            subscription.dispose();
        }
    }

    /**
     * Closing is the case that fails if the PREVIOUS version is not captured: the matcher excludes
     * closed situations, so matching only the new state finds no journeys and nothing would be
     * republished - leaving the disruption on the client's display indefinitely.
     */
    @Test
    void closingASituationRepublishesTheAffectedJourneyWithoutIt() throws InterruptedException {
        String line = REPUBLISH_LINE + "-close";
        String dsj = REPUBLISH_DSJ + "-close";
        String quay = REPUBLISH_QUAY + "-close";
        String situationNumber = REPUBLISH_SITUATION + "-close";

        timetableRepository.add(journeyCallingAt(line, dsj, quay));
        situationRepository.add(situationAffectingStop(situationNumber, quay));

        String document = """
                subscription {
                  timetables(lineRef: "%s", bufferSize: 1, bufferTime: 50) {
                    datedServiceJourney { id }
                    calls { situations { situationNumber } }
                  }
                }
                """.formatted(line);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-republish-close", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        Publisher<ExecutionResult> publisher = response.getData();
        CountDownLatch situationGone = new CountDownLatch(1);
        AtomicReference<List<String>> lastSeen = new AtomicReference<>(List.of());

        Disposable subscription = Flux.from(publisher).subscribe(result -> {
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timetables = (List<Map<String, Object>>) data.get("timetables");
            for (Map<String, Object> timetable : timetables) {
                if (dsj.equals(datedServiceJourneyId(timetable))) {
                    List<String> situations = callSituationNumbers(timetable);
                    lastSeen.set(situations);
                    if (!situations.contains(situationNumber)) {
                        situationGone.countDown();
                    }
                }
            }
        });

        try {
            PtSituationElementRecord closed = situationAffectingStop(situationNumber, quay);
            closed.setProgress("CLOSED");
            closed.setVersion(2);
            situationRepository.add(closed);

            assertThat(situationGone.await(10, TimeUnit.SECONDS))
                    .withFailMessage("closing a situation must republish the journey without it - "
                            + "the matcher excludes closed situations, so the republisher has to "
                            + "trigger on the PREVIOUS version's refs. Last seen: " + lastSeen.get())
                    .isTrue();
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void aSituationOnAStopPlaceReachesTheCallAtItsQuay() {
        when(nsrService.ancestorsOf(QUAY_ANCESTOR_QUAY)).thenReturn(Set.of(QUAY_ANCESTOR_STOP_PLACE));
        when(nsrService.ancestorsOf(argThat(ref -> !QUAY_ANCESTOR_QUAY.equals(ref)))).thenReturn(Set.of());
        when(nsrService.getStop(anyString())).thenAnswer(i -> new StopPoint(i.getArgument(0)));

        situationRepository.add(situationAffectingStop(QUAY_ANCESTOR_SITUATION, QUAY_ANCESTOR_STOP_PLACE));
        timetableRepository.add(journeyCallingAt(QUAY_ANCESTOR_LINE, QUAY_ANCESTOR_DSJ, QUAY_ANCESTOR_QUAY));

        String document = """
                query {
                  timetables(lineRef: "%s") {
                    calls {
                      stopPoint { id }
                      situations { situationNumber }
                    }
                  }
                }
                """.formatted(QUAY_ANCESTOR_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-ancestor", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<String> callSituations =
                situationNumbersOf(response.field("timetables[0].calls[0].situations").getValue());
        assertThat(callSituations)
                .withFailMessage("a situation tagged on NSR:StopPlace must appear on the call at its quay")
                .containsExactly(QUAY_ANCESTOR_SITUATION);
    }

    /**
     * The production path for the reported bug. {@code aSituationOnAStopPlaceReachesTheCallAtItsQuay}
     * above proves resolution end to end, but ingests the stop place ref through the
     * {@code AffectedStopPoint} branch - so the branch a real SIRI-SX producer actually uses to
     * tag a stop place was only ever covered separately, in {@code SituationMapperTest}. This
     * closes that chain: an {@code AffectedStopPlace} record all the way to the call at the quay.
     * <p>
     * It also asserts that {@code affects} still reports what the producer named and nothing more -
     * the stop place under {@code stopPlaces}, and no quay invented under {@code stopPoints}.
     */
    @Test
    void aSituationTaggedAsAnAffectedStopPlaceReachesTheCallAtItsQuay() {
        when(nsrService.ancestorsOf(TAGGED_PLACE_QUAY)).thenReturn(Set.of(TAGGED_PLACE_STOP_PLACE));
        when(nsrService.ancestorsOf(argThat(ref -> !TAGGED_PLACE_QUAY.equals(ref)))).thenReturn(Set.of());
        when(nsrService.getStop(anyString())).thenAnswer(i -> new StopPoint(i.getArgument(0)));

        situationRepository.add(situationAffectingStopPlace(TAGGED_PLACE_SITUATION, TAGGED_PLACE_STOP_PLACE));
        timetableRepository.add(journeyCallingAt(TAGGED_PLACE_LINE, TAGGED_PLACE_DSJ, TAGGED_PLACE_QUAY));

        String document = """
                query {
                  timetables(lineRef: "%s") {
                    calls {
                      stopPoint { id }
                      situations {
                        situationNumber
                        affects { stopPlaces { id } stopPoints { id } }
                      }
                    }
                  }
                }
                """.formatted(TAGGED_PLACE_LINE);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-tagged-place", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<String> callSituations =
                situationNumbersOf(response.field("timetables[0].calls[0].situations").getValue());
        assertThat(callSituations)
                .withFailMessage("a situation tagged as an AffectedStopPlace must appear on the call at its quay")
                .containsExactly(TAGGED_PLACE_SITUATION);

        String affectedStopPlaceId =
                response.field("timetables[0].calls[0].situations[0].affects.stopPlaces[0].id").getValue();
        assertThat(affectedStopPlaceId)
                .withFailMessage("affects must still name the stop place the producer tagged")
                .isEqualTo(TAGGED_PLACE_STOP_PLACE);
        List<Object> affectedStopPoints =
                response.field("timetables[0].calls[0].situations[0].affects.stopPoints").getValue();
        assertThat(affectedStopPoints)
                .withFailMessage("resolution must not write the resolved quay back onto affects")
                .isEmpty();
    }

    /**
     * The whole feature, through the real schema: stops nested under a dated journey come back
     * as that journey's own entry, and the polyline is cut to the span between them rather than
     * being the journey's full geometry.
     */
    @Test
    void anAffectedJourneysStopsResolveWithAPolylineCutToTheirSpan() {
        // Six points about 111 m apart, due north.
        int[] geometry = new int[12];
        for (int i = 0; i < 6; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        PlannedDataset affectedGeometryDataset = new PlannedDataset.Builder()
                .addServiceLink(AFFECTED_GEOMETRY_LINK, geometry)
                .addJourneyPattern(AFFECTED_GEOMETRY_PATTERN, List.of(AFFECTED_GEOMETRY_LINK))
                .addServiceJourney(AFFECTED_GEOMETRY_SJ, AFFECTED_GEOMETRY_PATTERN)
                .addOperatingDay("TST:OperatingDay:affected-geometry-probe", "2026-09-03")
                .addDatedServiceJourney(AFFECTED_GEOMETRY_DSJ, AFFECTED_GEOMETRY_SJ,
                        "TST:OperatingDay:affected-geometry-probe")
                .build();
        when(plannedDataService.current()).thenReturn(affectedGeometryDataset);
        // PlannedDataService.findDatedServiceJourney reads its own internal AtomicReference,
        // not the current() method - stubbing current() alone leaves it answering the mock
        // default (null) on a full @MockitoBean. SituationMapper.resolveDatedServiceJourney
        // goes through ServiceJourneyService, which calls findDatedServiceJourney, so it has
        // to be stubbed too or the dated journey never resolves to its service journey.
        when(plannedDataService.findDatedServiceJourney(AFFECTED_GEOMETRY_DSJ))
                .thenReturn(affectedGeometryDataset.datedServiceJourney(AFFECTED_GEOMETRY_DSJ));
        when(nsrService.getStop(AFFECTED_GEOMETRY_STOP_1))
                .thenReturn(new StopPoint(AFFECTED_GEOMETRY_STOP_1, "One", new Location(10.0, 59.001)));
        when(nsrService.getStop(AFFECTED_GEOMETRY_STOP_2))
                .thenReturn(new StopPoint(AFFECTED_GEOMETRY_STOP_2, "Two", new Location(10.0, 59.004)));

        situationRepository.add(situationAffectingJourneyAtStops(
                AFFECTED_GEOMETRY_SITUATION, AFFECTED_GEOMETRY_DSJ,
                AFFECTED_GEOMETRY_STOP_1, AFFECTED_GEOMETRY_STOP_2));

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects {
                      vehicleJourneys {
                        datedServiceJourney { id }
                        stops { stop { id } stopConditions }
                        affectedPointsOnLink { length points }
                      }
                    }
                  }
                }
                """.formatted(AFFECTED_GEOMETRY_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-affected-geometry", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        String datedId = response.field(
                "situations[0].affects.vehicleJourneys[0].datedServiceJourney.id").getValue();
        assertThat(datedId).isEqualTo(AFFECTED_GEOMETRY_DSJ);

        List<Map<String, Object>> stops = response.field(
                "situations[0].affects.vehicleJourneys[0].stops").getValue();
        assertThat(stops).hasSize(2);
        assertThat(stops.get(0).get("stopConditions")).isEqualTo(List.of("startPoint"));

        // Vertices 1..4: the span between the two stops, not the pattern's full six points.
        Number length = response.field(
                "situations[0].affects.vehicleJourneys[0].affectedPointsOnLink.length").getValue();
        assertThat(length.intValue()).isEqualTo(4);
    }

    /**
     * The line-level half of the feature, through the real schema: a situation tagged on a line
     * and two of its stops resolves to a span on one of the line's journey patterns, not to null
     * and not to the pattern's full geometry.
     */
    @Test
    void anAffectedLinesStopsResolveWithAPolylineCutToTheirSpan() {
        // Six points about 111 m apart, due north.
        int[] geometry = new int[12];
        for (int i = 0; i < 6; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        when(plannedDataService.current()).thenReturn(new PlannedDataset.Builder()
                .addServiceLink(AFFECTED_LINE_GEOMETRY_LINK, geometry)
                .addJourneyPattern(AFFECTED_LINE_GEOMETRY_PATTERN, List.of(AFFECTED_LINE_GEOMETRY_LINK))
                .addLine(AFFECTED_LINE_GEOMETRY_LINE, "Affected line", "31")
                .addServiceJourney(AFFECTED_LINE_GEOMETRY_SJ, AFFECTED_LINE_GEOMETRY_PATTERN,
                        AFFECTED_LINE_GEOMETRY_LINE)
                .build());
        when(nsrService.getStop(AFFECTED_LINE_GEOMETRY_STOP_1)).thenReturn(
                new StopPoint(AFFECTED_LINE_GEOMETRY_STOP_1, "One", new Location(10.0, 59.001)));
        when(nsrService.getStop(AFFECTED_LINE_GEOMETRY_STOP_2)).thenReturn(
                new StopPoint(AFFECTED_LINE_GEOMETRY_STOP_2, "Two", new Location(10.0, 59.004)));

        situationRepository.add(situationAffectingLineAtStops(
                AFFECTED_LINE_GEOMETRY_SITUATION, AFFECTED_LINE_GEOMETRY_LINE,
                AFFECTED_LINE_GEOMETRY_STOP_1, AFFECTED_LINE_GEOMETRY_STOP_2));

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects {
                      affectedLines {
                        line { lineRef }
                        stops { stop { id } }
                        affectedPointsOnLink { length points }
                      }
                    }
                  }
                }
                """.formatted(AFFECTED_LINE_GEOMETRY_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-affected-line-geometry", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();
        String lineId = response.field("situations[0].affects.affectedLines[0].line.lineRef").getValue();
        assertThat(lineId).isEqualTo(AFFECTED_LINE_GEOMETRY_LINE);
        // Vertices 1..4: the span between the two stops, not the pattern's full six points.
        Number length = response.field(
                "situations[0].affects.affectedLines[0].affectedPointsOnLink.length").getValue();
        assertThat(length.intValue()).isEqualTo(4);
    }

    /**
     * The mirror of the existing opt-out test for journey situations: the cut is lazy, so a
     * client that selects the stops but not the polyline must not make the resolver touch the
     * planned dataset at all.
     */
    @Test
    void anAffectsSelectionWithoutTheGeometryFieldDoesNoGeometryWork() {
        situationRepository.add(situationAffectingJourneyAtStops(
                AFFECTED_GEOMETRY_OPT_OUT_SITUATION, AFFECTED_GEOMETRY_OPT_OUT_DSJ,
                AFFECTED_GEOMETRY_STOP_1, AFFECTED_GEOMETRY_STOP_2));
        // The mapper resolves the dated journey through the dataset at ingest, so only what
        // happens from here on is under test.
        clearInvocations(plannedDataService);

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects { vehicleJourneys { stops { stop { id } } } }
                  }
                }
                """.formatted(AFFECTED_GEOMETRY_OPT_OUT_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-affected-geometry-opt-out", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();
        verify(plannedDataService, never()).current();
    }

    private VehicleActivityRecord vehicleActivity(String vehicleRef, String lineRef, double latitude, double longitude) {
        VehicleActivityRecord record = new VehicleActivityRecord();
        record.setRecordedAtTime(ZonedDateTime.now().toString());
        record.setValidUntilTime(ZonedDateTime.now().plusMinutes(10).toString());

        MonitoredVehicleJourneyRecord journey = new MonitoredVehicleJourneyRecord();
        journey.setDataSource("TST");
        journey.setLineRef(lineRef);
        journey.setVehicleRef(vehicleRef);
        journey.setMonitored(true);

        LocationRecord location = new LocationRecord();
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        journey.setVehicleLocation(location);

        record.setMonitoredVehicleJourney(journey);
        return record;
    }

    @SuppressWarnings("unchecked")
    private List<String> vehicleIdsOf(Object value) {
        List<Map<String, Object>> vehicles = (List<Map<String, Object>>) value;
        return vehicles.stream().map(v -> (String) v.get("vehicleId")).toList();
    }

    private PtSituationElementRecord situationAffectingStopPlace(String situationNumber, String stopPlaceRef) {
        PtSituationElementRecord record = openSituation(situationNumber);

        AffectedStopPlaceRecord stopPlace = new AffectedStopPlaceRecord();
        stopPlace.setStopPlaceRef(stopPlaceRef);

        AffectsRecord affects = new AffectsRecord();
        affects.setStopPlaces(List.of(stopPlace));
        record.setAffects(affects);

        return record;
    }

    /** Flattens the situationNumbers across every call of a timetable in a subscription payload. */
    private List<String> callSituationNumbers(Map<String, Object> timetable) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) timetable.get("calls");
        List<String> numbers = new ArrayList<>();
        if (calls != null) {
            for (Map<String, Object> call : calls) {
                numbers.addAll(situationNumbersOf(call.get("situations")));
            }
        }
        return numbers;
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

    private static PtSituationElementRecord situationAffectingJourneyAtStops(String situationNumber,
                                                                            String datedServiceJourneyId,
                                                                            String... stopRefs) {
        List<AffectedStopPointRecord> stopPoints = new ArrayList<>();
        for (String stopRef : stopRefs) {
            AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
            stopPoint.setStopPointRef(stopRef);
            stopPoint.setStopPointNames(List.of());
            stopPoint.setStopConditions(List.of("startPoint"));
            stopPoints.add(stopPoint);
        }
        StopPointsRecord stops = new StopPointsRecord();
        stops.setStopPoints(stopPoints);
        AffectedRouteRecord route = new AffectedRouteRecord();
        route.setStopPoints(stops);
        route.setSections(List.of());

        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setVehicleJourneyRefs(List.of());
        journey.setDatedVehicleJourneyRefs(List.of(datedServiceJourneyId));
        journey.setRoutes(List.of(route));

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of());
        affects.setStopPoints(List.of());
        affects.setStopPlaces(List.of());
        affects.setVehicleJourneys(List.of(journey));

        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusHours(1).toString());
        record.setReportType("general");
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        record.setAffects(affects);
        return record;
    }

    /** The line-level shape of a tagged situation: AffectedLine with its stops, no journeys. */
    private static PtSituationElementRecord situationAffectingLineAtStops(String situationNumber,
                                                                         String lineRef,
                                                                         String... stopRefs) {
        List<AffectedStopPointRecord> stopPoints = new ArrayList<>();
        for (String stopRef : stopRefs) {
            AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
            stopPoint.setStopPointRef(stopRef);
            stopPoint.setStopPointNames(List.of());
            stopPoint.setStopConditions(List.of("startPoint"));
            stopPoints.add(stopPoint);
        }
        StopPointsRecord stops = new StopPointsRecord();
        stops.setStopPoints(stopPoints);
        AffectedRouteRecord route = new AffectedRouteRecord();
        route.setStopPoints(stops);
        route.setSections(List.of());

        AffectedLineRecord affectedLine = new AffectedLineRecord();
        affectedLine.setLineRef(lineRef);
        affectedLine.setRoutes(List.of(route));

        AffectedNetworkRecord network = new AffectedNetworkRecord();
        network.setAffectedLines(List.of(affectedLine));
        network.setAffectedOperators(List.of());

        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of(network));
        affects.setStopPoints(List.of());
        affects.setStopPlaces(List.of());
        affects.setVehicleJourneys(List.of());

        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusHours(1).toString());
        record.setReportType("general");
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
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

    /**
     * Catalogue journeys are created without geometry; {@code pointsOnLink} resolves from the
     * dataset only when a client selects the field. A pattern with one two-point link must
     * therefore come back with length 2 through the real schema.
     */
    @Test
    void catalogueServiceJourneyResolvesPointsOnLinkLazilyFromPlannedData() {
        when(plannedDataService.current()).thenReturn(new PlannedDataset.Builder()
                .addLine("TST:Line:1", "One", "1")
                .addServiceLink("TST:ServiceLink:1", new int[]{59_000_000, 10_000_000, 59_001_000, 10_001_000})
                .addJourneyPattern("TST:JourneyPattern:1", List.of("TST:ServiceLink:1"))
                .addServiceJourney("TST:ServiceJourney:1", "TST:JourneyPattern:1", "TST:Line:1")
                .build());

        String document = """
                query {
                  serviceJourneys(lineRef: "TST:Line:1") {
                    id
                    pointsOnLink { length points }
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-catalogue-geometry", Locale.ENGLISH)
        ).block();

        assertThat(response.getExecutionResult().getErrors()).isEmpty();
        Map<String, Object> data = response.getExecutionResult().getData();
        List<Map<String, Object>> journeys = (List<Map<String, Object>>) data.get("serviceJourneys");
        assertThat(journeys).hasSize(1);
        assertThat(journeys.get(0).get("id")).isEqualTo("TST:ServiceJourney:1");
        Map<String, Object> pointsOnLink = (Map<String, Object>) journeys.get(0).get("pointsOnLink");
        assertThat(pointsOnLink).isNotNull();
        assertThat(pointsOnLink.get("length")).isEqualTo(2.0);
        assertThat((String) pointsOnLink.get("points")).isNotEmpty();
    }

    @Test
    void catalogueServiceJourneysLooksUpMultipleIds() {
        when(plannedDataService.current()).thenReturn(new PlannedDataset.Builder()
                .addLine("TST:Line:1", "One", "1")
                .addJourneyPattern("TST:JourneyPattern:1", List.of())
                .addServiceJourney("TST:ServiceJourney:1", "TST:JourneyPattern:1", "TST:Line:1")
                .addServiceJourney("TST:ServiceJourney:2", "TST:JourneyPattern:1", "TST:Line:1")
                .addOperatingDay("TST:OperatingDay:1", "2026-09-02")
                .addDatedServiceJourney("TST:DatedServiceJourney:2", "TST:ServiceJourney:2", "TST:OperatingDay:1")
                .build());

        String document = """
                query {
                  serviceJourneys(ids: ["TST:DatedServiceJourney:2", "TST:ServiceJourney:nope", "TST:ServiceJourney:1"]) {
                    id
                    date
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-catalogue-ids", Locale.ENGLISH)
        ).block();

        assertThat(response.getExecutionResult().getErrors()).isEmpty();
        Map<String, Object> data = response.getExecutionResult().getData();
        List<Map<String, Object>> journeys = (List<Map<String, Object>>) data.get("serviceJourneys");
        assertThat(journeys).extracting(j -> j.get("id"))
                .containsExactly("TST:ServiceJourney:2", "TST:ServiceJourney:1");
        assertThat(journeys.get(0).get("date")).isEqualTo("2026-09-02");
        assertThat(journeys.get(1).get("date")).isNull();
    }

    @Test
    void catalogueDatedServiceJourneysLookUpByIdAndByIds() {
        when(plannedDataService.current()).thenReturn(new PlannedDataset.Builder()
                .addLine("TST:Line:1", "One", "1")
                .addServiceLink("TST:ServiceLink:1", new int[]{59_000_000, 10_000_000, 59_001_000, 10_001_000})
                .addJourneyPattern("TST:JourneyPattern:1", List.of("TST:ServiceLink:1"))
                .addServiceJourney("TST:ServiceJourney:1", "TST:JourneyPattern:1", "TST:Line:1")
                .addOperatingDay("TST:OperatingDay:1", "2026-09-02")
                .addOperatingDay("TST:OperatingDay:2", "2026-09-03")
                .addDatedServiceJourney("TST:DatedServiceJourney:1", "TST:ServiceJourney:1", "TST:OperatingDay:1")
                .addDatedServiceJourney("TST:DatedServiceJourney:2", "TST:ServiceJourney:1", "TST:OperatingDay:2")
                .build());

        String document = """
                query {
                  one: datedServiceJourney(id: "TST:DatedServiceJourney:1") {
                    id
                    operatingDay
                    serviceJourney { id date pointsOnLink { length } }
                  }
                  missing: datedServiceJourney(id: "TST:DatedServiceJourney:nope") { id }
                  many: datedServiceJourneys(ids: ["TST:DatedServiceJourney:2", "TST:DatedServiceJourney:nope", "TST:DatedServiceJourney:1"]) {
                    id
                    operatingDay
                  }
                }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-catalogue-dated", Locale.ENGLISH)
        ).block();

        assertThat(response.getExecutionResult().getErrors()).isEmpty();
        Map<String, Object> data = response.getExecutionResult().getData();
        Map<String, Object> one = (Map<String, Object>) data.get("one");
        assertThat(one.get("id")).isEqualTo("TST:DatedServiceJourney:1");
        assertThat(one.get("operatingDay")).isEqualTo("2026-09-02");
        Map<String, Object> serviceJourney = (Map<String, Object>) one.get("serviceJourney");
        assertThat(serviceJourney.get("id")).isEqualTo("TST:ServiceJourney:1");
        assertThat(serviceJourney.get("date")).isEqualTo("2026-09-02");
        assertThat(((Map<String, Object>) serviceJourney.get("pointsOnLink")).get("length")).isEqualTo(2.0);
        assertThat(data.get("missing")).isNull();
        List<Map<String, Object>> many = (List<Map<String, Object>>) data.get("many");
        assertThat(many).extracting(j -> j.get("id"))
                .containsExactly("TST:DatedServiceJourney:2", "TST:DatedServiceJourney:1");
        assertThat(many.get(0).get("operatingDay")).isEqualTo("2026-09-03");
    }

    @Test
    void serviceJourneysWithoutAFilterIsABadRequest() {
        String document = """
                query { serviceJourneys { id } }
                """;

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(), "test-catalogue-filter", Locale.ENGLISH)
        ).block();

        assertThat(response.getExecutionResult().getErrors()).hasSize(1);
        var error = response.getExecutionResult().getErrors().get(0);
        assertThat(error.getMessage()).contains("ids").contains("lineRef").contains("codespaceId");
        // What a client sees: the classification is written into extensions on serialization
        Map<String, Object> extensions = (Map<String, Object>) error.toSpecification().get("extensions");
        assertThat(String.valueOf(extensions.get("classification"))).isEqualTo("BAD_REQUEST");
    }

    /**
     * A situation naming two journeys where only one of them resolves to a pattern with geometry.
     * This is the ordinary case in production - an unfiltered situations query spans journeys the
     * planned data does not know - and it is what a single-entry fixture cannot reach: the
     * unresolvable entry contributes a null to whatever the resolver hands back, and a null is
     * exactly what a List-returning @BatchMapping may not contain.
     */
    @Test
    void aSituationMixingResolvableAndUnresolvableJourneysStillAnswers() {
        int[] geometry = new int[12];
        for (int i = 0; i < 6; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceLink(AFFECTED_GEOMETRY_LINK, geometry)
                .addJourneyPattern(AFFECTED_GEOMETRY_PATTERN, List.of(AFFECTED_GEOMETRY_LINK))
                .addServiceJourney(AFFECTED_GEOMETRY_SJ, AFFECTED_GEOMETRY_PATTERN)
                .addOperatingDay("TST:OperatingDay:mixed-geometry-probe", "2026-09-03")
                .addDatedServiceJourney(MIXED_GEOMETRY_KNOWN_DSJ, AFFECTED_GEOMETRY_SJ,
                        "TST:OperatingDay:mixed-geometry-probe")
                .build();
        when(plannedDataService.current()).thenReturn(dataset);
        // Only the first journey is known to the planned data. The second is left unstubbed, so
        // it stays a bare ref with no service journey - and therefore no pattern to cut.
        when(plannedDataService.findDatedServiceJourney(MIXED_GEOMETRY_KNOWN_DSJ))
                .thenReturn(dataset.datedServiceJourney(MIXED_GEOMETRY_KNOWN_DSJ));
        when(nsrService.getStop(AFFECTED_GEOMETRY_STOP_1))
                .thenReturn(new StopPoint(AFFECTED_GEOMETRY_STOP_1, "One", new Location(10.0, 59.001)));
        when(nsrService.getStop(AFFECTED_GEOMETRY_STOP_2))
                .thenReturn(new StopPoint(AFFECTED_GEOMETRY_STOP_2, "Two", new Location(10.0, 59.004)));

        situationRepository.add(situationAffectingTwoJourneysAtStops(
                MIXED_GEOMETRY_SITUATION, MIXED_GEOMETRY_KNOWN_DSJ, MIXED_GEOMETRY_UNKNOWN_DSJ,
                AFFECTED_GEOMETRY_STOP_1, AFFECTED_GEOMETRY_STOP_2));

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects {
                      vehicleJourneys {
                        datedServiceJourney { id }
                        affectedPointsOnLink { length points }
                      }
                    }
                  }
                }
                """.formatted(MIXED_GEOMETRY_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-mixed-geometry", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors())
                .withFailMessage("a journey with no resolvable geometry must yield a null field, "
                        + "not fail the whole query")
                .isEmpty();

        List<Map<String, Object>> journeys =
                response.field("situations[0].affects.vehicleJourneys").getValue();
        assertThat(journeys).hasSize(2);

        Map<String, Object> known = journeys.stream()
                .filter(j -> MIXED_GEOMETRY_KNOWN_DSJ.equals(
                        ((Map<String, Object>) j.get("datedServiceJourney")).get("id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> unknown = journeys.stream()
                .filter(j -> MIXED_GEOMETRY_UNKNOWN_DSJ.equals(
                        ((Map<String, Object>) j.get("datedServiceJourney")).get("id")))
                .findFirst()
                .orElseThrow();

        // length is a GraphQL Float, so it arrives as a Double.
        Number length = (Number) ((Map<String, Object>) known.get("affectedPointsOnLink")).get("length");
        assertThat(length.intValue()).isEqualTo(4);
        assertThat(unknown.get("affectedPointsOnLink"))
                .withFailMessage("the unresolvable journey keeps its own slot as null")
                .isNull();
    }

    private static PtSituationElementRecord situationAffectingTwoJourneysAtStops(String situationNumber,
                                                                                 String firstDatedServiceJourneyId,
                                                                                 String secondDatedServiceJourneyId,
                                                                                 String... stopRefs) {
        AffectsRecord affects = new AffectsRecord();
        affects.setNetworks(List.of());
        affects.setStopPoints(List.of());
        affects.setStopPlaces(List.of());
        affects.setVehicleJourneys(List.of(
                affectedVehicleJourney(firstDatedServiceJourneyId, stopRefs),
                affectedVehicleJourney(secondDatedServiceJourneyId, stopRefs)));

        PtSituationElementRecord record = new PtSituationElementRecord();
        record.setSituationNumber(situationNumber);
        record.setParticipantRef("TST");
        record.setCreationTime(ZonedDateTime.now().minusHours(1).toString());
        record.setReportType("general");
        record.setValidityPeriods(List.of());
        record.setKeywords(List.of());
        record.setSummaries(List.of());
        record.setDescriptions(List.of());
        record.setDetails(List.of());
        record.setAdvices(List.of());
        record.setInfoLinks(List.of());
        record.setAffects(affects);
        return record;
    }

    private static AffectedVehicleJourneyRecord affectedVehicleJourney(String datedServiceJourneyId,
                                                                       String... stopRefs) {
        List<AffectedStopPointRecord> stopPoints = new ArrayList<>();
        for (String stopRef : stopRefs) {
            AffectedStopPointRecord stopPoint = new AffectedStopPointRecord();
            stopPoint.setStopPointRef(stopRef);
            stopPoint.setStopPointNames(List.of());
            stopPoint.setStopConditions(List.of("startPoint"));
            stopPoints.add(stopPoint);
        }
        StopPointsRecord stops = new StopPointsRecord();
        stops.setStopPoints(stopPoints);
        AffectedRouteRecord route = new AffectedRouteRecord();
        route.setStopPoints(stops);
        route.setSections(List.of());

        AffectedVehicleJourneyRecord journey = new AffectedVehicleJourneyRecord();
        journey.setVehicleJourneyRefs(List.of());
        journey.setDatedVehicleJourneyRefs(List.of(datedServiceJourneyId));
        journey.setRoutes(List.of(route));
        return journey;
    }

    /**
     * A situation affecting a journey as a whole - the producer names the journey and nests no
     * stops under it - resolves to that journey's entire route, not to null. The empty stops
     * list is what tells a client the situation is journey-wide rather than stop-scoped.
     */
    @Test
    void aJourneyAffectedAsAWholeResolvesToItsEntireRouteThroughTheSchema() {
        int[] geometry = new int[12];
        for (int i = 0; i < 6; i++) {
            geometry[i * 2] = 59_000_000 + i * 1_000;
            geometry[i * 2 + 1] = 10_000_000;
        }
        PlannedDataset dataset = new PlannedDataset.Builder()
                .addServiceLink(AFFECTED_GEOMETRY_LINK, geometry)
                .addJourneyPattern(AFFECTED_GEOMETRY_PATTERN, List.of(AFFECTED_GEOMETRY_LINK))
                .addServiceJourney(AFFECTED_GEOMETRY_SJ, AFFECTED_GEOMETRY_PATTERN)
                .addOperatingDay("TST:OperatingDay:whole-journey-probe", "2026-09-04")
                .addDatedServiceJourney(WHOLE_JOURNEY_DSJ, AFFECTED_GEOMETRY_SJ,
                        "TST:OperatingDay:whole-journey-probe")
                .build();
        when(plannedDataService.current()).thenReturn(dataset);
        when(plannedDataService.findDatedServiceJourney(WHOLE_JOURNEY_DSJ))
                .thenReturn(dataset.datedServiceJourney(WHOLE_JOURNEY_DSJ));

        // No stop refs at all - the journey is affected as a whole.
        situationRepository.add(situationAffectingJourneyAtStops(
                WHOLE_JOURNEY_SITUATION, WHOLE_JOURNEY_DSJ));

        String document = """
                query {
                  situations(includeClosed: true, situationNumbers: ["%s"]) {
                    affects {
                      vehicleJourneys {
                        stops { stop { id } }
                        affectedPointsOnLink { length points }
                      }
                    }
                  }
                }
                """.formatted(WHOLE_JOURNEY_SITUATION);

        ExecutionGraphQlResponse response = graphQlService.execute(
                new DefaultExecutionGraphQlRequest(document, null, Map.of(), Map.of(),
                        "test-whole-journey", Locale.ENGLISH)
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isEmpty();

        List<Map<String, Object>> stops =
                response.field("situations[0].affects.vehicleJourneys[0].stops").getValue();
        assertThat(stops)
                .withFailMessage("an empty stops list is the signal that the whole journey is affected")
                .isEmpty();

        Number length = response.field(
                "situations[0].affects.vehicleJourneys[0].affectedPointsOnLink.length").getValue();
        assertThat(length)
                .withFailMessage("a wholly affected journey resolves to its full route, not to null")
                .isNotNull();
        // The pattern's full six points.
        assertThat(length.intValue()).isEqualTo(6);
    }
}
