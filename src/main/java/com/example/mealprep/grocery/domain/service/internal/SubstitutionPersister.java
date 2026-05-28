package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.SubstitutionProposal;
import com.example.mealprep.grocery.event.SubstitutionProposedEvent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Tier-3 substitution persister (grocery-01f, LLD line 49 / 1018). Maps each provider {@link
 * SubstitutionProposal} surfaced at delivery to a {@code grocery_substitution_proposals} row and
 * publishes one {@link SubstitutionProposedEvent} per persisted proposal. Invoked from {@code
 * markDelivered} (the order-lifecycle {@code applyDelivered} path).
 *
 * <p><b>Parseable vs opaque.</b> A proposal the automation could read off the provider's DOM is
 * persisted {@code PENDING_USER_REVIEW} with the original/substitute product + mapping keys. A
 * proposal whose payload is opaque / "DOM differs from expected" (no substitute product id
 * readable) is persisted {@code UNPARSED} with {@code raw_payload} populated so the user can
 * resolve it manually (GROC-20). The resolve endpoint accepts an {@code UNPARSED →
 * ACCEPTED|REJECTED} transition either way (LLD line 921).
 *
 * <p><b>No auto-accept.</b> Every proposal lands in a NOT-yet-resolved status; nothing here ever
 * sets {@code ACCEPTED}/{@code REJECTED} — only a later user decision in {@code
 * resolveSubstitution} does. Auto-accept is structurally impossible (HLD safety contract, LLD line
 * 912).
 *
 * <p>Runs INSIDE the delivery transaction (events fire AFTER_COMMIT via the project-wide
 * {@code @TransactionalEventListener} convention). Package-private internal plumbing.
 */
@Component
class SubstitutionPersister {

  private final GroceryOrderDataGateway dataGateway;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  SubstitutionPersister(
      GroceryOrderDataGateway dataGateway, ApplicationEventPublisher eventPublisher, Clock clock) {
    this.dataGateway = dataGateway;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /**
   * Persist all provider-proposed substitutions for a delivered order and publish one {@link
   * SubstitutionProposedEvent} each. Returns the persisted entities (count == number persisted). A
   * null / empty input persists nothing and returns an empty list (the no-substitution path).
   */
  List<GrocerySubstitutionProposal> persistAll(
      GroceryOrder order, List<SubstitutionProposal> proposals) {
    if (proposals == null || proposals.isEmpty()) {
      return List.of();
    }
    List<GrocerySubstitutionProposal> saved = new ArrayList<>(proposals.size());
    for (SubstitutionProposal proposal : proposals) {
      saved.add(persistOne(order, proposal));
    }
    return saved;
  }

  private GrocerySubstitutionProposal persistOne(
      GroceryOrder order, SubstitutionProposal proposal) {
    GroceryOrderLine matchedLine = matchLine(order, proposal.originalProductId());
    boolean parseable = isParseable(proposal);
    SubstitutionProposalStatus status =
        parseable
            ? SubstitutionProposalStatus.PENDING_USER_REVIEW
            : SubstitutionProposalStatus.UNPARSED;

    GrocerySubstitutionProposal entity =
        GrocerySubstitutionProposal.builder()
            .id(UUID.randomUUID())
            .groceryOrderId(order.getId())
            .groceryOrderLineId(matchedLine != null ? matchedLine.getId() : null)
            .originalProductId(defaultIfBlank(proposal.originalProductId(), "unknown"))
            .originalDisplayName(
                defaultIfBlank(
                    proposal.originalDisplayName(),
                    matchedLine != null ? matchedLine.getDisplayName() : "unknown"))
            .originalIngredientMappingKey(
                matchedLine != null ? matchedLine.getIngredientMappingKey() : null)
            .substituteProductId(defaultIfBlank(proposal.substituteProductId(), "unknown"))
            .substituteDisplayName(defaultIfBlank(proposal.substituteDisplayName(), "unknown"))
            // The substitute's mapping key is not known at provider-read time (no cross-module
            // lookup here); v1 leaves it null and the consume side resolves it. UNPARSED never has
            // a substitute key.
            .substituteIngredientMappingKey(null)
            .substituteQuantity(proposal.substituteQuantity())
            .substituteUnit(proposal.substituteUnit())
            .substituteUnitPence(proposal.substituteUnitPence())
            .reason(proposal.reason())
            .proposalStatus(status)
            .rawPayload(proposal.rawPayload())
            .build();

    GrocerySubstitutionProposal persisted = dataGateway.saveProposal(entity);

    eventPublisher.publishEvent(
        new SubstitutionProposedEvent(
            order.getUserId(),
            order.getId(),
            persisted.getId(),
            persisted.getOriginalIngredientMappingKey(),
            persisted.getSubstituteIngredientMappingKey(),
            persisted.getProposalStatus(),
            order.getTraceId(),
            clock.instant()));

    return persisted;
  }

  /**
   * A proposal is parseable when the automation read a concrete substitute product off the DOM. An
   * opaque payload (no substitute product id) is the "DOM differs" UNPARSED case (GROC-20).
   */
  private static boolean isParseable(SubstitutionProposal proposal) {
    return proposal.substituteProductId() != null && !proposal.substituteProductId().isBlank();
  }

  /** Match the proposal to its originating order line by provider product id (best-effort). */
  private static GroceryOrderLine matchLine(GroceryOrder order, String originalProductId) {
    if (originalProductId == null || order.getLines() == null) {
      return null;
    }
    for (GroceryOrderLine line : order.getLines()) {
      if (originalProductId.equals(line.getProviderProductId())) {
        return line;
      }
    }
    return null;
  }

  private static String defaultIfBlank(String value, String fallback) {
    return value != null && !value.isBlank() ? value : fallback;
  }
}
