# SIRI-SX Startup Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bootstrap the in-memory situation store from a complete REST snapshot at startup, before the Pub/Sub stream begins, so long-lived situations published before the service started are present.

**Architecture:** A `@PostConstruct` on a new `SituationSnapshotService` fetches `.../rest/sx?SIRI_VERSION=2.1` with `Accept: application/avro+json`, rewrites each situation from plain JSON into union-wrapped Avro JSON so the existing `JsonReader` can decode it, and feeds each record through the existing `SituationRepository.add(...)`. `PubSubSXSubscriber` is ordered after it with `@DependsOn`, so no streamed update can be overwritten by a late snapshot record.

**Tech Stack:** Java 21, Spring Boot, Spring WebFlux `WebClient`, Jackson, Apache Avro 1.12.1, `org.entur:siri-avro-model:2.0.4`, JUnit 5 + Mockito.

**Design spec:** `docs/superpowers/specs/2026-08-05-sx-startup-snapshot-design.md`

## Global Constraints

- Package root is `org.entur.vehicles`. New classes go in `org.entur.vehicles.service`.
- Branch is `siri_sx_snapshot`, based on `siri_sx_api`. The full SX feature (repository, mapper, filter, GraphQL) already exists and is committed — do not reimplement any of it.
- The whole feature is gated on the existing `entur.vehicle-positions.sx.enabled` flag (default `false`). No second enable flag.
- Snapshot loading must complete **before** `PubSubSXSubscriber` is created. `version` is null on 323 of 343 production situations, so the version guard in `SituationRepository` mostly cannot fire and cannot be relied on to protect ordering.
- Failures are non-fatal: a failed fetch logs ERROR and startup continues; a single bad situation logs WARN and the rest still load.
- Avro string fields are `CharSequence`, never `String`.
- **No test may perform network I/O.** Fixtures are already committed at `src/test/resources/sx/`.
- Build and test with `mvn`. Full suite: `mvn clean test`. Single class: `mvn test -Dtest=ClassName`.
- No new dependency in `pom.xml`. Jackson, Avro and WebFlux are all already present.
- No Claude/AI attribution in commit messages — match the existing terse style (`Adding design-spec for SIRI-SX startup snapshot`).

## Committed inputs

These already exist on the branch; do not regenerate them.

| File | What it is |
|---|---|
| `src/test/resources/sx/sx-snapshot-response.json` | A real 4-situation response captured from the live endpoint and trimmed. Covers null union values, populated string unions, nested records, arrays of records, empty arrays, deeply nested `affects`, one `CLOSED` situation, one open-ended situation, and two situations with multiple validity periods. All four are verified to parse end to end. |
| `src/test/resources/sx/sx-snapshot-response-one-malformed.json` | The same envelope with `RUT:SituationNumber:2026-64179-1`'s `validityPeriods` replaced by the string `"not-an-array"`, so exactly one situation fails to parse. |

Situation numbers in the fixture: `RUT:SituationNumber:823246` (3 validity periods), `RUT:SituationNumber:2026-64179-1` (CLOSED), `SKY:SituationNumber:TX1221961` (open-ended), `RUT:SituationNumber:823380` (2 validity periods).

## Task Overview

| # | Deliverable |
|---|---|
| 1 | `AvroJsonUnionWrapper` — plain JSON → union-wrapped Avro JSON |
| 2 | `SituationSnapshotService` — fetch, parse, load, degrade on failure |
| 3 | Ordering, configuration, and documentation |

---

### Task 1: AvroJsonUnionWrapper

The REST endpoint returns JSON in the Avro record *shape* but not the Avro JSON *encoding*: union-typed values appear plain (`"participantRef": "VKT"`) where Avro's `JsonDecoder` requires them wrapped by branch name (`"participantRef": {"string": "VKT"}`). Feeding the response straight to `JsonReader` fails with `AvroTypeException: Expected start-union. Got VALUE_STRING`.

This class performs that rewrite, driven by the schema, so the existing `JsonReader` can decode the result and the snapshot path shares one parser with the Pub/Sub path.

Taking the first non-null union branch is safe here: all 54 unions across the 23 record types reachable from `PtSituationElementRecord` are `["null", X]`. A schema that violated that would make this rule silently pick the wrong branch, so the code throws rather than guessing, and a test pins the assumption.

**The wrapper is strict about types, deliberately.** A lenient version — returning an empty array when the JSON holds something that is not an array — was tried and rejected: it silently swallows corruption. Since `SituationUpdate.getOpenEnded()` returns true when a situation has no validity periods, a field whose shape changed upstream would quietly blank out every situation's periods and report the **entire feed as open-ended**, corrupting precisely the signal the quality tooling exists to produce. A type mismatch therefore throws, which `SituationSnapshotService` catches per-situation so one bad record is skipped and counted rather than silently degraded. Verified: strict mode still accepts all 343 production situations.

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/AvroJsonUnionWrapper.java`
- Test: `src/test/java/org/entur/vehicles/service/AvroJsonUnionWrapperTest.java`

**Interfaces:**
- Consumes: nothing from other tasks. Uses `org.apache.avro.Schema`, Jackson `JsonNode`, and `PtSituationElementRecord.getClassSchema()`.
- Produces: `public static JsonNode wrap(JsonNode plain, Schema schema)` — pure, stateless, no I/O.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/service/AvroJsonUnionWrapperTest.java`:

```java
package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AvroJsonUnionWrapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Schema SITUATION_SCHEMA = PtSituationElementRecord.getClassSchema();

    private JsonNode fixtureSituations() throws IOException {
        try (var in = getClass().getResourceAsStream("/sx/sx-snapshot-response.json")) {
            assertNotNull(in, "fixture must be on the test classpath");
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .get("serviceDelivery")
                    .get("situationExchangeDeliveries").get(0)
                    .get("situations");
        }
    }

    @Test
    public void testEveryFixtureSituationBecomesReadableByJsonReader() throws IOException {
        JsonNode situations = fixtureSituations();
        assertEquals(4, situations.size(), "fixture is expected to hold four situations");

        for (JsonNode situation : situations) {
            JsonNode wrapped = AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA);
            PtSituationElementRecord record =
                    JsonReader.readPtSituationElement(MAPPER.writeValueAsString(wrapped));

            assertNotNull(record.getSituationNumber());
            assertNotNull(record.getParticipantRef());
        }
    }

    @Test
    public void testValuesSurviveTheRewrite() throws IOException {
        JsonNode closed = null;
        for (JsonNode situation : fixtureSituations()) {
            if ("RUT:SituationNumber:2026-64179-1".equals(situation.get("situationNumber").asText())) {
                closed = situation;
            }
        }
        assertNotNull(closed, "fixture must contain the CLOSED situation");

        PtSituationElementRecord record = JsonReader.readPtSituationElement(
                MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(closed, SITUATION_SCHEMA)));

        assertEquals("RUT:SituationNumber:2026-64179-1", record.getSituationNumber().toString());
        assertEquals("CLOSED", record.getProgress().toString());
        assertEquals("RUT", record.getParticipantRef().toString());
        assertEquals(1, record.getValidityPeriods().size());
        assertNotNull(record.getAffects(), "the nested affects record must survive");
    }

    @Test
    public void testMultipleValidityPeriodsSurvive() throws IOException {
        for (JsonNode situation : fixtureSituations()) {
            if ("RUT:SituationNumber:823246".equals(situation.get("situationNumber").asText())) {
                PtSituationElementRecord record = JsonReader.readPtSituationElement(
                        MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA)));
                assertEquals(3, record.getValidityPeriods().size());
                return;
            }
        }
        throw new AssertionError("fixture must contain RUT:SituationNumber:823246");
    }

    @Test
    public void testNullUnionValueStaysNull() throws IOException {
        for (JsonNode situation : fixtureSituations()) {
            PtSituationElementRecord record = JsonReader.readPtSituationElement(
                    MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA)));
            // `version` is null on virtually every real situation - it must round-trip as null,
            // not as a wrapper object or a zero.
            if (situation.get("version") == null || situation.get("version").isNull()) {
                assertEquals(null, record.getVersion());
                return;
            }
        }
        throw new AssertionError("fixture must contain a situation with a null version");
    }

    /**
     * A field whose shape does not match the schema must fail loudly. Quietly treating a
     * non-array as an empty array would blank out every situation's validity periods on an
     * upstream format change, and since a situation with no periods reports
     * openEnded = true, the whole feed would silently look open-ended - corrupting exactly
     * the signal the quality tooling produces.
     */
    @Test
    public void testATypeMismatchThrowsRatherThanSilentlyYieldingEmpty() throws IOException {
        JsonNode situation = fixtureSituations().get(0);
        ObjectNode corrupted = ((ObjectNode) situation.deepCopy())
                .put("validityPeriods", "not-an-array");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AvroJsonUnionWrapper.wrap(corrupted, SITUATION_SCHEMA));
        assertTrue(thrown.getMessage().contains("array"),
                "the failure should name the expected type, was: " + thrown.getMessage());
    }

    /**
     * The wrapper picks the first non-null branch of every union. That is only correct
     * while every union has exactly one. If a siri-avro-model upgrade introduces a union
     * with two non-null branches, this test fails here rather than silently mis-parsing a
     * field in production.
     */
    @Test
    public void testEveryUnionInTheSchemaHasExactlyOneNonNullBranch() {
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.List<String> offenders = new java.util.ArrayList<>();
        assertUnionsAreNullable(SITUATION_SCHEMA, "PtSituationElement", visited, offenders);
        assertTrue(offenders.isEmpty(), "unions with more than one non-null branch: " + offenders);
    }

    private void assertUnionsAreNullable(Schema schema, String path,
                                         java.util.Set<String> visited,
                                         java.util.List<String> offenders) {
        switch (schema.getType()) {
            case UNION -> {
                long nonNull = schema.getTypes().stream()
                        .filter(t -> t.getType() != Schema.Type.NULL)
                        .count();
                if (nonNull > 1) {
                    offenders.add(path);
                }
                schema.getTypes().forEach(t -> assertUnionsAreNullable(t, path, visited, offenders));
            }
            case RECORD -> {
                if (visited.add(schema.getFullName())) {
                    schema.getFields().forEach(f ->
                            assertUnionsAreNullable(f.schema(), path + "." + f.name(), visited, offenders));
                }
            }
            case ARRAY -> assertUnionsAreNullable(schema.getElementType(), path + "[]", visited, offenders);
            case MAP -> assertUnionsAreNullable(schema.getValueType(), path + "{}", visited, offenders);
            default -> { }
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AvroJsonUnionWrapperTest`
Expected: FAIL — compilation error, `AvroJsonUnionWrapper` does not exist.

- [ ] **Step 3: Implement the wrapper**

Create `src/main/java/org/entur/vehicles/service/AvroJsonUnionWrapper.java`:

```java
package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;

/**
 * Rewrites JSON that matches an Avro schema's <em>shape</em> into the Avro JSON
 * <em>encoding</em> that {@code JsonReader} requires.
 * <p>
 * The SIRI-SX REST endpoint serves union-typed values plain:
 * <pre>{"participantRef": "VKT"}</pre>
 * while Avro's {@code JsonDecoder} requires them tagged with the branch name:
 * <pre>{"participantRef": {"string": "VKT"}}</pre>
 * Without this rewrite, decoding fails with
 * {@code AvroTypeException: Expected start-union. Got VALUE_STRING}.
 */
public final class AvroJsonUnionWrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AvroJsonUnionWrapper() {
    }

    public static JsonNode wrap(JsonNode plain, Schema schema) {
        return switch (schema.getType()) {
            case UNION -> wrapUnion(plain, schema);
            case RECORD -> wrapRecord(plain, schema);
            case ARRAY -> wrapArray(plain, schema);
            case MAP -> wrapMap(plain, schema);
            default -> plain == null ? NullNode.instance : plain;
        };
    }

    private static JsonNode wrapUnion(JsonNode plain, Schema schema) {
        if (plain == null || plain.isNull()) {
            return NullNode.instance;
        }

        Schema branch = null;
        for (Schema candidate : schema.getTypes()) {
            if (candidate.getType() == Schema.Type.NULL) {
                continue;
            }
            if (branch != null) {
                // Every union in the SIRI schema is ["null", X]. Guessing between two
                // non-null branches would silently mis-parse the field, so fail loudly.
                throw new IllegalStateException(
                        "Cannot rewrite a union with more than one non-null branch: " + schema);
            }
            branch = candidate;
        }

        if (branch == null) {
            return NullNode.instance;
        }

        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set(branch.getFullName(), wrap(plain, branch));
        return wrapped;
    }

    private static JsonNode wrapRecord(JsonNode plain, Schema schema) {
        if (plain == null || plain.isNull()) {
            return NullNode.instance;
        }
        if (!plain.isObject()) {
            throw new IllegalArgumentException("Expected an object for record "
                    + schema.getFullName() + " but found " + plain.getNodeType());
        }
        ObjectNode wrapped = MAPPER.createObjectNode();
        for (Schema.Field field : schema.getFields()) {
            // Absent fields become explicit nulls - Avro's decoder requires every field present.
            wrapped.set(field.name(), wrap(plain.get(field.name()), field.schema()));
        }
        return wrapped;
    }

    private static JsonNode wrapArray(JsonNode plain, Schema schema) {
        ArrayNode wrapped = MAPPER.createArrayNode();
        if (plain == null || plain.isNull()) {
            return wrapped;
        }
        // Deliberately strict. Quietly returning an empty array here would swallow an
        // upstream format change: with no validity periods, every situation reports
        // openEnded = true, corrupting the signal the quality tooling depends on.
        if (!plain.isArray()) {
            throw new IllegalArgumentException("Expected an array but found " + plain.getNodeType());
        }
        for (JsonNode item : plain) {
            wrapped.add(wrap(item, schema.getElementType()));
        }
        return wrapped;
    }

    private static JsonNode wrapMap(JsonNode plain, Schema schema) {
        ObjectNode wrapped = MAPPER.createObjectNode();
        if (plain == null || plain.isNull()) {
            return wrapped;
        }
        if (!plain.isObject()) {
            throw new IllegalArgumentException("Expected an object for a map but found "
                    + plain.getNodeType());
        }
        plain.fields().forEachRemaining(entry ->
                wrapped.set(entry.getKey(), wrap(entry.getValue(), schema.getValueType())));
        return wrapped;
    }
}
```

Note `getFullName()`, not `getName()` — Avro tags named-type union branches with the fully-qualified name, and the SIRI records are namespaced.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=AvroJsonUnionWrapperTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/AvroJsonUnionWrapper.java \
        src/test/java/org/entur/vehicles/service/AvroJsonUnionWrapperTest.java
git commit -m "Adding Avro JSON union-wrapper for the SX REST payload"
```

---

### Task 2: SituationSnapshotService

Fetches the snapshot, parses it, and loads it into the repository. Failure is non-fatal at two levels: a failed fetch leaves the service running stream-only, and a single bad situation is skipped rather than discarding the rest.

The HTTP call needs its own `WebClient`: `JourneyPlannerGraphQLClient` caps `maxInMemorySize` at 500 KB and the dev snapshot is 9.7 MB.

**Files:**
- Create: `src/main/java/org/entur/vehicles/service/SituationSnapshotService.java`
- Test: `src/test/java/org/entur/vehicles/service/SituationSnapshotServiceTest.java`

**Interfaces:**
- Consumes: `AvroJsonUnionWrapper.wrap(JsonNode, Schema)` (Task 1); `SituationRepository.add(PtSituationElementRecord)`; `SituationRepository.getSituations(SituationFilter)` (a null filter returns everything).
- Produces:
  - `SituationSnapshotService(SituationRepository, String url, String etClientName, Duration timeout, boolean enabled)`
  - `@PostConstruct public void loadSnapshot()` — the startup hook; never throws.
  - `int load(String responseBody)` — parses a response body and loads it, returning the number of situations loaded. Package-private visibility is fine; this is the seam the tests drive so they need no network.

Splitting `load(String)` out of `loadSnapshot()` is what keeps the tests network-free. `loadSnapshot()` does the HTTP call and delegates.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/entur/vehicles/service/SituationSnapshotServiceTest.java`:

```java
package org.entur.vehicles.service;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.graphql.publishers.SituationUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.repository.AutoPurgingSituationMap;
import org.entur.vehicles.repository.SituationMapper;
import org.entur.vehicles.repository.SituationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationSnapshotServiceTest {

    private SituationRepository repository;
    private SituationSnapshotService snapshotService;

    @BeforeEach
    public void init() {
        PrometheusMetricsService metricsService =
                new PrometheusMetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        NSRService nsrService = Mockito.mock(NSRService.class);
        Mockito.when(nsrService.getStop(Mockito.anyString()))
                .thenAnswer(invocation ->
                        new org.entur.vehicles.data.model.StopPoint(invocation.getArgument(0)));

        repository = new SituationRepository(
                metricsService,
                new SituationMapper(new LineService(false), nsrService),
                new AutoPurgingSituationMap(Duration.parse("PT5S"), Duration.parse("PT5M")),
                new SituationUpdateRxPublisher()
        );

        snapshotService = new SituationSnapshotService(
                repository, "http://localhost:0/unused", "test", Duration.parse("PT5S"), true);
    }

    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/sx/" + name)) {
            assertNotNull(in, "fixture must be on the test classpath: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Set<String> storedSituationNumbers() {
        Collection<SituationUpdate> stored = repository.getSituations(null);
        return stored.stream().map(SituationUpdate::getSituationNumber).collect(Collectors.toSet());
    }

    @Test
    public void testLoadsEverySituationFromTheSnapshot() throws IOException {
        int loaded = snapshotService.load(fixture("sx-snapshot-response.json"));

        assertEquals(4, loaded);
        assertEquals(
                Set.of("RUT:SituationNumber:823246",
                        "RUT:SituationNumber:2026-64179-1",
                        "SKY:SituationNumber:TX1221961",
                        "RUT:SituationNumber:823380"),
                storedSituationNumbers());
    }

    @Test
    public void testClosedSituationsAreLoadedLikeAnyOther() throws IOException {
        snapshotService.load(fixture("sx-snapshot-response.json"));

        SituationUpdate closed = repository.getSituations(null).stream()
                .filter(s -> "RUT:SituationNumber:2026-64179-1".equals(s.getSituationNumber()))
                .findFirst()
                .orElseThrow();

        assertEquals(org.entur.vehicles.data.WorkflowStatusEnumeration.closed, closed.getProgress());
        assertNotNull(closed.getExpiration(), "a closed situation expires immediately");
    }

    @Test
    public void testOneMalformedSituationDoesNotDiscardTheRest() throws IOException {
        int loaded = snapshotService.load(fixture("sx-snapshot-response-one-malformed.json"));

        assertEquals(3, loaded, "the three well-formed situations must still load");
        assertTrue(storedSituationNumbers().contains("RUT:SituationNumber:823246"));
        assertTrue(storedSituationNumbers().contains("SKY:SituationNumber:TX1221961"));
        assertTrue(storedSituationNumbers().contains("RUT:SituationNumber:823380"));
        assertEquals(3, storedSituationNumbers().size());
    }

    @Test
    public void testAnUnparseableBodyLoadsNothingAndDoesNotThrow() {
        assertEquals(0, snapshotService.load("this is not json"));
        assertTrue(repository.getSituations(null).isEmpty());
    }

    @Test
    public void testAnEmptyDeliveryLoadsNothing() {
        assertEquals(0, snapshotService.load(
                "{\"version\":\"2.1\",\"serviceDelivery\":{\"situationExchangeDeliveries\":[]}}"));
        assertTrue(repository.getSituations(null).isEmpty());
    }

    @Test
    public void testAFailingFetchDoesNotAbortStartup() {
        // The URL points at a port nothing listens on, so the fetch fails.
        SituationSnapshotService failing = new SituationSnapshotService(
                repository, "http://localhost:1/sx", "test", Duration.parse("PT1S"), true);

        failing.loadSnapshot();

        assertTrue(repository.getSituations(null).isEmpty(),
                "a failed snapshot leaves the repository empty rather than throwing");
    }

    @Test
    public void testDisabledServiceDoesNotFetch() {
        SituationSnapshotService disabled = new SituationSnapshotService(
                repository, "http://localhost:1/sx", "test", Duration.parse("PT1S"), false);

        disabled.loadSnapshot();

        assertTrue(repository.getSituations(null).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SituationSnapshotServiceTest`
Expected: FAIL — compilation error, `SituationSnapshotService` does not exist.

- [ ] **Step 3: Implement the service**

Create `src/main/java/org/entur/vehicles/service/SituationSnapshotService.java`:

```java
package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import org.apache.avro.Schema;
import org.entur.avro.realtime.siri.helper.JsonReader;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.vehicles.repository.SituationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bootstraps the situation store from a complete REST snapshot at startup.
 * <p>
 * The Pub/Sub topic carries updates, not state: a situation published before this
 * service started is never re-sent. Situations are long-lived - many carry no validity
 * end time at all - so a stream-only service has a permanently incomplete picture.
 * <p>
 * This runs before {@code PubSubSXSubscriber} is created (see its {@code @DependsOn}).
 * The ordering matters: {@code version} is null on the large majority of real
 * situations, so {@code SituationRepository}'s version guard cannot be relied on to
 * stop a late snapshot record from overwriting fresher streamed data.
 */
@Service
public class SituationSnapshotService {

    private static final Logger LOG = LoggerFactory.getLogger(SituationSnapshotService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Schema SITUATION_SCHEMA = PtSituationElementRecord.getClassSchema();

    /** The dev snapshot is ~10 MB; the shared Journey Planner client caps at 500 KB. */
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final SituationRepository situationRepository;
    private final String url;
    private final String etClientName;
    private final Duration timeout;
    private final boolean enabled;

    public SituationSnapshotService(
            @Autowired SituationRepository situationRepository,
            @Value("${vehicle.sx.snapshot.url}") String url,
            @Value("${vehicle.journeyplanner.EtClientName}") String etClientName,
            @Value("${vehicle.sx.snapshot.timeout:PT60S}") Duration timeout,
            @Value("${entur.vehicle-positions.sx.enabled:false}") boolean enabled) {
        this.situationRepository = situationRepository;
        this.url = url;
        this.etClientName = etClientName;
        this.timeout = timeout;
        this.enabled = enabled;
    }

    @PostConstruct
    public void loadSnapshot() {
        if (!enabled) {
            LOG.info("SX is disabled - skipping situation snapshot.");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            String body = fetch();
            int loaded = load(body);
            LOG.info("Loaded {} situations from snapshot in {} ms",
                    loaded, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            // Non-fatal by design: the service still starts and serves the Pub/Sub stream,
            // it just begins with an incomplete set until producers republish.
            LOG.error("Failed to load situation snapshot from {} - continuing without it.", url, e);
        }
    }

    private String fetch() {
        int timeoutMillis = (int) timeout.toMillis();
        WebClient webClient = WebClient.builder()
                .defaultHeader("ET-Client-Name", etClientName)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                                .doOnConnected(connection -> {
                                    connection.addHandlerLast(
                                            new ReadTimeoutHandler(timeoutMillis, TimeUnit.MILLISECONDS));
                                    connection.addHandlerLast(
                                            new WriteTimeoutHandler(timeoutMillis, TimeUnit.MILLISECONDS));
                                })))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();

        return webClient.get()
                .uri(url)
                // Without this header the endpoint returns SIRI XML.
                .header("Accept", "application/avro+json")
                .retrieve()
                .bodyToMono(String.class)
                .block(timeout);
    }

    /**
     * Parses a snapshot response body and loads every situation it holds.
     *
     * @return the number of situations successfully loaded
     */
    int load(String body) {
        JsonNode deliveries;
        try {
            JsonNode serviceDelivery = MAPPER.readTree(body).path("serviceDelivery");
            deliveries = serviceDelivery.path("situationExchangeDeliveries");
        } catch (Exception e) {
            LOG.error("Could not parse situation snapshot response.", e);
            return 0;
        }

        int loaded = 0;
        int skipped = 0;
        for (JsonNode delivery : deliveries) {
            for (JsonNode situation : delivery.path("situations")) {
                if (addSituation(situation)) {
                    loaded++;
                } else {
                    skipped++;
                }
            }
        }

        if (skipped > 0) {
            LOG.warn("Skipped {} unparseable situations in the snapshot.", skipped);
        }
        return loaded;
    }

    private boolean addSituation(JsonNode situation) {
        try {
            PtSituationElementRecord record = JsonReader.readPtSituationElement(
                    MAPPER.writeValueAsString(AvroJsonUnionWrapper.wrap(situation, SITUATION_SCHEMA)));
            situationRepository.add(record);
            return true;
        } catch (Exception e) {
            // One malformed situation must not discard the rest of the snapshot.
            LOG.warn("Ignoring unparseable situation {} in snapshot.",
                    situation.path("situationNumber").asText("<unknown>"), e);
            return false;
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SituationSnapshotServiceTest`
Expected: PASS, 7 tests.

If `testOneMalformedSituationDoesNotDiscardTheRest` reports 4 loaded rather than 3, the malformed situation is being silently tolerated somewhere rather than throwing. Do not weaken the test to match — report it, since the point of the test is that a bad record is counted as skipped.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/SituationSnapshotService.java \
        src/test/java/org/entur/vehicles/service/SituationSnapshotServiceTest.java
git commit -m "Adding startup snapshot loader for situations"
```

---

### Task 3: Ordering, configuration and documentation

Wires the snapshot ahead of the Pub/Sub subscriber, adds the properties, and documents the behaviour.

**Files:**
- Modify: `src/main/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriber.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Modify: `src/main/resources/Usage.md`
- Test: `src/test/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriberOrderingTest.java`

**Interfaces:**
- Consumes: `SituationSnapshotService` (Task 2) — by bean name only, via `@DependsOn`.
- Produces: nothing other code consumes.

- [ ] **Step 1: Write the failing test**

The `@DependsOn` annotation IS the ordering guarantee, and deleting it would break startup ordering silently with no other test noticing. Pin it.

Create `src/test/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriberOrderingTest.java`:

```java
package org.entur.vehicles.service.pubsub.impl;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot must finish loading before the stream starts. `version` is null on the
 * large majority of real situations, so the repository's version guard cannot stop a late
 * snapshot record from overwriting fresher streamed data - the ordering is the protection,
 * and this annotation is what provides it.
 */
public class PubSubSXSubscriberOrderingTest {

    @Test
    public void testSubscriberIsOrderedAfterTheSnapshotService() {
        DependsOn dependsOn = PubSubSXSubscriber.class.getAnnotation(DependsOn.class);

        assertNotNull(dependsOn,
                "PubSubSXSubscriber must declare @DependsOn so the snapshot loads before the stream starts");
        assertTrue(Arrays.asList(dependsOn.value()).contains("situationSnapshotService"),
                "expected a dependency on situationSnapshotService, found " + Arrays.toString(dependsOn.value()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=PubSubSXSubscriberOrderingTest`
Expected: FAIL — `PubSubSXSubscriber must declare @DependsOn ...` (the annotation is absent, so `getAnnotation` returns null).

- [ ] **Step 3: Add the ordering annotation**

In `PubSubSXSubscriber.java`, add the import and the annotation on the class:

```java
import org.springframework.context.annotation.DependsOn;
```

```java
@Service
@DependsOn("situationSnapshotService")
public class PubSubSXSubscriber extends PubSubSubscriber {
```

Add a short comment above the annotation explaining why, since an unexplained `@DependsOn` invites deletion:

```java
// The startup snapshot must finish loading before the stream starts: `version` is null on
// most real situations, so the repository's version guard cannot stop a late snapshot
// record from overwriting fresher streamed data.
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=PubSubSXSubscriberOrderingTest`
Expected: PASS, 1 test.

- [ ] **Step 5: Add configuration**

In `src/main/resources/application.properties`, add next to the other `vehicle.*` lookup settings (near `vehicle.nsr.lookup.*`):

```properties
vehicle.sx.snapshot.url=https://api.dev.entur.io/realtime/v1/rest/sx?SIRI_VERSION=2.1
vehicle.sx.snapshot.timeout=PT60S
```

The default points at dev, matching `vehicle.journeyplanner.url`. The snapshot URL and the Pub/Sub topic project must move together per environment — pointing the snapshot at production while the stream reads `ent-anshar-dev` would merge two inconsistent datasets into one map. Add that as a comment above the property.

In `src/test/resources/application.properties`, add a URL that cannot resolve, so the `@SpringBootTest` context never attempts a real fetch. `entur.vehicle-positions.sx.enabled=false` is already set there, so the service short-circuits before the URL is used, but the placeholder must still resolve:

```properties
vehicle.sx.snapshot.url=http://localhost:0/sx
```

- [ ] **Step 6: Run the full suite**

Run: `mvn clean test`
Expected: PASS. The 81 pre-existing tests plus 14 new ones (6 + 7 + 1) — 95 total. `ApplicationGraphQlSchemaTests` must still start the context; if it now fails, the snapshot service is attempting a fetch when it should be disabled.

- [ ] **Step 7: Document the behaviour**

`src/main/resources/Usage.md` already has a `## Situations` section. Add this to the end of it:

````markdown
Situations are long-lived, and many carry no validity end time at all. Because the
real-time stream carries updates rather than state, the service loads a complete snapshot
of current situations from Entur's SIRI-SX REST endpoint at startup, before it begins
consuming the stream. A situation that was published long before the service started is
therefore available immediately, without waiting for its producer to republish.
````

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriber.java \
        src/main/resources/application.properties \
        src/test/resources/application.properties \
        src/main/resources/Usage.md \
        src/test/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriberOrderingTest.java
git commit -m "Loading the situation snapshot before the SX stream starts"
```

---

## Verification

After Task 3:

1. `mvn clean install` passes.
2. `git log --oneline siri_sx_api..HEAD` shows the spec commit, the fixture commit, and one commit per task.
3. `grep -c "DependsOn" src/main/java/org/entur/vehicles/service/pubsub/impl/PubSubSXSubscriber.java` returns at least 2 (import + annotation).
4. The 81 tests that existed before this plan still pass.

**Manual verification** (needs network; report it skipped rather than faked if unavailable):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--entur.vehicle-positions.sx.enabled=true
```

In the startup log, `Loaded N situations from snapshot in M ms` must appear **before** `Started subscriber`. Then, immediately after startup and before any Pub/Sub message could have arrived:

```graphql
query { situations(openEnded: true, minAge: "P30D") { situationNumber age } }
```

should return long-lived situations that only the snapshot could have supplied.

Do not claim the feature is complete without the output of `mvn clean install` in hand.
