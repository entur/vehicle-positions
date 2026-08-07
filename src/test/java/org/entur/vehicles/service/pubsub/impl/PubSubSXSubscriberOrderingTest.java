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
