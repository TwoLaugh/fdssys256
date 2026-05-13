package com.example.mealprep.discovery.api.dto;

import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.validation.ValidDiscoveryConstraints;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Inbound request shape for {@code POST /api/v1/discovery/jobs}. Per LLD lines 261-267 and ticket
 * invariants 28-30.
 *
 * <p>The {@code constraints} field carries the {@code @ValidDiscoveryConstraints} class-level
 * validator (shipped in 01b) for the within-document rules; the cross-record {@code
 * maxRecipesPerSource <= requestedCount} check lives here via {@code @AssertTrue} because the inner
 * validator only sees {@code DiscoveryConstraints}, not this parent record.
 */
public record StartDiscoveryJobRequest(
    @NotNull DiscoveryJobTrigger trigger,
    @Min(1) @Max(50) int requestedCount,
    @NotNull @Valid @ValidDiscoveryConstraints DiscoveryConstraints constraints,
    @Nullable List<@NotBlank String> sourceKeys,
    @Nullable UUID traceId) {

  /**
   * Cross-record invariant: a per-source budget exceeding the total job quota is nonsensical.
   * Returns {@code true} when the rule passes (Jakarta semantics).
   */
  @JsonIgnore
  @AssertTrue(message = "maxRecipesPerSource must be less than or equal to requestedCount")
  public boolean isMaxRecipesPerSourceWithinTotal() {
    if (constraints == null || constraints.maxRecipesPerSource() == null) {
      return true;
    }
    return constraints.maxRecipesPerSource() <= requestedCount;
  }
}
