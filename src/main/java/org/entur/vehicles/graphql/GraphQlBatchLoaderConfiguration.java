package org.entur.vehicles.graphql;

import org.dataloader.DataLoaderOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.DefaultBatchLoaderRegistry;

/**
 * Overrides Spring Boot's autoconfigured {@link BatchLoaderRegistry} (which backs off via
 * {@code @ConditionalOnMissingBean} once this bean is present) to disable DataLoader's
 * per-key result cache.
 * <p>
 * {@code SituationJoinController}'s two {@code @BatchMapping} methods are keyed on
 * {@code EstimatedTimetableUpdate} and {@code Call} - mutable snapshot objects with value
 * equality, not stable identifiers. Spring GraphQL's {@code AnnotatedControllerConfigurer}
 * calls {@code dataLoader.load(source)} per field resolution, and with DataLoader's default
 * options ({@code cachingEnabled = true}) that memoizes the *key* via equals/hashCode before
 * the batch method ever runs - upstream of anything a resolver can do about it.
 * {@code AbstractUpdate.equals}/{@code hashCode} (shared with VM, out of bounds to change on
 * this branch) ignore {@code datedServiceJourney}, and the {@code DatedVehicleJourneyRef}
 * ingest path never populates the plain {@code serviceJourney} field at all, so two distinct
 * journeys on the same line/operator/codespace/mode compare equal. With caching on, one
 * journey's situations would silently answer for another's, and - because the registry is
 * shared for a subscription's whole lifetime - a journey republished after a situation
 * appears would keep returning its first-ever (now stale) answer.
 * <p>
 * Disabling the cache keeps batching (every key requested within one tick still coalesces
 * into a single call to the batch method) and drops only the per-key memoization, which is
 * what was wrong here: the key is a value snapshot, not an identity to remember.
 * <p>
 * This bean is registry-wide - {@link BatchLoaderRegistry} has no per-registration caching
 * option in this Spring GraphQL version. That is acceptable only because
 * {@code SituationJoinController}'s two resolvers are the only batch loaders in this
 * application; a future batch loader keyed by a stable id would still be correct without
 * caching, just not optimally so within a single request.
 */
@Configuration
public class GraphQlBatchLoaderConfiguration {

    @Bean
    public BatchLoaderRegistry batchLoaderRegistry() {
        return new DefaultBatchLoaderRegistry(
                () -> DataLoaderOptions.newOptions().setCachingEnabled(false).build());
    }
}
