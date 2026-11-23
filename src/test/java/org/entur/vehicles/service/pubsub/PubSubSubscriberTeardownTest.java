package org.entur.vehicles.service.pubsub;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.rpc.ApiCallContext;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.protobuf.Empty;
import com.google.pubsub.v1.DeleteSubscriptionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A pod that does not delete its subscriptions on shutdown leaves each of them collecting the full
 * feed for 24 hours. In prd, the first delete was still running when the pod was killed, so the
 * other two were never attempted. Teardown must be bounded and must never throw.
 */
public class PubSubSubscriberTeardownTest {

    private static final String SUBSCRIPTION = "projects/test-project/subscriptions/test-sub";

    private final List<DeleteSubscriptionRequest> deletes = new CopyOnWriteArrayList<>();

    private SubscriberStub stubAnswering(Supplier<ApiFuture<Empty>> response) {
        SubscriberStub stub = Mockito.mock(SubscriberStub.class);
        when(stub.deleteSubscriptionCallable()).thenReturn(new UnaryCallable<>() {
            @Override
            public ApiFuture<Empty> futureCall(DeleteSubscriptionRequest request, ApiCallContext context) {
                deletes.add(request);
                return response.get();
            }
        });
        return stub;
    }

    private static PubSubSubscriber subscriber(SubscriberStub stub) {
        PubSubSubscriber subscriber = new PubSubSubscriber("test-project", "test-sub", "topic-project", "topic",
                1, 1, Map.of(), (message, consumer) -> consumer.ack(), false,
                SubscriptionAdminClient.create(stub)) {};
        subscriber.deleteTimeoutMillis = 200;
        return subscriber;
    }

    @Test
    public void deletesTheSubscription() {
        SubscriberStub stub = stubAnswering(() -> ApiFutures.immediateFuture(Empty.getDefaultInstance()));

        subscriber(stub).teardown();

        assertEquals(1, deletes.size());
        assertEquals(SUBSCRIPTION, deletes.get(0).getSubscription());
        verify(stub).shutdown();
    }

    @Test
    public void aHangingDeleteDoesNotBlockShutdown() {
        SubscriberStub stub = stubAnswering(SettableApiFuture::create); // never completes

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> subscriber(stub).teardown());

        assertEquals(1, deletes.size());
        verify(stub).shutdown();
    }

    @Test
    public void aFailedDeleteDoesNotThrow() {
        SubscriberStub stub = stubAnswering(() -> ApiFutures.immediateFailedFuture(new RuntimeException("unavailable")));

        subscriber(stub).teardown();

        assertEquals(1, deletes.size());
    }

    @Test
    public void teardownRunsOnce() {
        SubscriberStub stub = stubAnswering(() -> ApiFutures.immediateFuture(Empty.getDefaultInstance()));
        PubSubSubscriber subscriber = subscriber(stub);

        subscriber.teardown();
        subscriber.teardown();

        assertEquals(1, deletes.size());
    }
}
