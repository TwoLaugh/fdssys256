package com.example.mealprep.adaptation.event;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an adaptation job finishes successfully. Carries the
 * outcome-kind + target so subscribers (notably the catalogue's {@code RecipeWriteApi} dispatcher
 * and the notification module's pending-change inbox) can route without joining on the trace.
 *
 * <p>{@code RecipeAdaptedEvent} is published separately by the catalogue side per LLD line 686 —
 * subscribers needing both join via {@link #traceId()}.
 *
 * <p>Per LLD §Events lines 660-662.
 */
public record AdaptationJobCompletedEvent(
    UUID jobId,
    UUID recipeId,
    OutcomeKind outcomeKind,
    @Nullable UUID outcomeTargetId,
    AdaptationClassification classification,
    BigDecimal confidence,
    UUID traceId,
    Instant occurredAt)
    implements AdaptationEvent {

  @Override
  public String scopeKind() {
    return "recipe";
  }

  @Override
  public UUID scopeId() {
    return recipeId;
  }
}
