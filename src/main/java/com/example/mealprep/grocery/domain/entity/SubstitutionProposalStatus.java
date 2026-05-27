package com.example.mealprep.grocery.domain.entity;

/**
 * Lifecycle of a {@link GrocerySubstitutionProposal}. Per lld/grocery.md line 374. {@code UNPARSED}
 * covers the "provider DOM differs from expected" case — the raw payload is captured and surfaced
 * for manual user resolution. Reconciliation is blocked while any proposal is {@code
 * PENDING_USER_REVIEW}.
 */
public enum SubstitutionProposalStatus {
  PENDING_USER_REVIEW,
  ACCEPTED,
  REJECTED,
  UNPARSED
}
