package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Tier-3 substitution resolution. Per lld/grocery.md line 476. {@code decision} is {@code ACCEPTED}
 * or {@code REJECTED} only (the service rejects any other value).
 */
public record ResolveSubstitutionRequest(
    @NotNull UUID proposalId, @NotNull SubstitutionProposalStatus decision) {}
