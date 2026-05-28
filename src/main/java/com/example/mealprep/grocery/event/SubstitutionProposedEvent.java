package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once per provider-proposed substitution persisted at delivery (grocery-01f). Per
 * lld/grocery.md line 829 — a Tier-3 substitution-decision event, kept SEPARATE from the sealed
 * lifecycle hierarchy ({@link GroceryOrderLifecycleEvent}) because its listeners react per
 * proposal, not per lifecycle stage. Emitted in-transaction via {@code ApplicationEventPublisher};
 * consumers listen {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so it effectively
 * fires after the delivery commit (the project-wide convention — see {@link PriceObservedEvent}).
 *
 * <p>{@code proposalStatus} is the persisted status the proposal landed in — {@code
 * PENDING_USER_REVIEW} for a parseable proposal, {@code UNPARSED} for an opaque/DOM-differs payload
 * the user resolves manually (GROC-20). No auto-accept: a proposal is only ever resolved by a later
 * user decision via {@link SubstitutionResolvedEvent}.
 */
public record SubstitutionProposedEvent(
    UUID userId,
    UUID groceryOrderId,
    UUID proposalId,
    String originalIngredientMappingKey,
    String substituteIngredientMappingKey,
    com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus proposalStatus,
    UUID traceId,
    Instant occurredAt) {}
