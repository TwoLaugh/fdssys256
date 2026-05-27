package com.example.mealprep.grocery.exception;

import java.util.UUID;

/**
 * Thrown when a substitution proposal is missing or out of the caller's scope. Maps to HTTP 404.
 */
public class GrocerySubstitutionProposalNotFoundException extends GroceryException {

  private final UUID proposalId;

  public GrocerySubstitutionProposalNotFoundException(UUID proposalId) {
    super("Grocery substitution proposal " + proposalId + " not found");
    this.proposalId = proposalId;
  }

  public UUID proposalId() {
    return proposalId;
  }
}
