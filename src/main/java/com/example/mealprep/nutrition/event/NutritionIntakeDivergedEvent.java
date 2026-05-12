package com.example.mealprep.nutrition.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.nutrition.api.dto.DivergenceSummaryDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when the {@code DivergenceDetector} observes a change in the set
 * of macros currently breaching the variance threshold on a given day — either a fresh / changed
 * divergence, or a previously-diverged set newly resolving to empty.
 *
 * <p>{@code divergedMacros} is the new set after the change. An empty set means the previous
 * divergence has resolved (planner consumers can clear the corresponding re-opt offer).
 *
 * <p>{@code scopeKind = "nutrition-intake"}, {@code scopeId = userId} — the closest scope id we
 * carry for nutrition-intake (the {@code (userId, onDate)} composite key is split across two
 * fields).
 */
public record NutritionIntakeDivergedEvent(
    UUID userId,
    LocalDate onDate,
    Set<String> divergedMacros,
    DivergenceSummaryDto summary,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "nutrition-intake";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
