package com.example.mealprep.grocery.exception;

import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import java.util.UUID;

/**
 * Thrown when a substitution-proposal resolve is illegal (grocery-01f): the requested {@code
 * decision} is not {@code ACCEPTED}/{@code REJECTED}, or the proposal is no longer resolvable (it
 * is not {@code PENDING_USER_REVIEW}/{@code UNPARSED} — i.e. already resolved). Maps to HTTP 409 —
 * only a {@code PENDING_USER_REVIEW} or {@code UNPARSED} proposal can be resolved, and only to
 * {@code ACCEPTED} or {@code REJECTED} (LLD line 158; auto-accept forbidden, LLD line 912).
 */
public class IllegalSubstitutionStateException extends GroceryException {

  private final UUID proposalId;
  private final SubstitutionProposalStatus attemptedDecision;

  public IllegalSubstitutionStateException(
      UUID proposalId, SubstitutionProposalStatus attemptedDecision) {
    super(
        "Illegal substitution resolve for proposal "
            + proposalId
            + ": only a PENDING_USER_REVIEW or UNPARSED proposal can be resolved to ACCEPTED or"
            + " REJECTED (attempted: "
            + attemptedDecision
            + ")");
    this.proposalId = proposalId;
    this.attemptedDecision = attemptedDecision;
  }

  public UUID proposalId() {
    return proposalId;
  }

  public SubstitutionProposalStatus attemptedDecision() {
    return attemptedDecision;
  }
}
