package com.example.mealprep.grocery.event;

import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Published once per user-resolved substitution proposal (grocery-01f). Per lld/grocery.md line 829
 * — a Tier-3 substitution-decision event, kept SEPARATE from the sealed lifecycle hierarchy because
 * its listeners react per proposal, not per lifecycle stage.
 *
 * <p><b>The GG-resolving seam (owner RESOLVED — distinct names).</b> Grocery publishes this single,
 * decision-carrying {@code grocery.event.SubstitutionResolvedEvent} for the user decision (accepted
 * vs rejected); provisions keeps its existing {@code
 * provisions.event.ProvisionChangedEvent.SubstitutionAcceptedEvent} state-change. There is
 * therefore NO {@code grocery.event.SubstitutionAcceptedEvent} / {@code SubstitutionRejectedEvent}
 * pair — the distinct names avoid the cross-module collision with provisions' own event and leave
 * the planner's {@code ProvisionChangedEvent.SubstitutionAcceptedEvent} listener unaffected.
 *
 * <p>{@code decision} is {@code ACCEPTED} or {@code REJECTED}. On accept, {@code
 * substituteIngredientMappingKey} is the relevant mapping key (provisions adds the substitute on
 * its consume side); on reject, {@code originalIngredientMappingKey} is the key the planner may
 * re-optimise (provisions skips the substitute). Both keys are carried so a single record covers
 * either decision.
 *
 * <p>Emitted in-transaction via {@code ApplicationEventPublisher}; consumers listen
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so it effectively fires after the
 * resolve commit (the project-wide convention).
 */
public record SubstitutionResolvedEvent(
    UUID userId,
    UUID groceryOrderId,
    UUID proposalId,
    SubstitutionProposalStatus decision,
    String originalIngredientMappingKey,
    String substituteIngredientMappingKey,
    UUID traceId,
    Instant occurredAt) {}
