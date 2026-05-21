package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * In-process payload for the AI delta application path. NOT exposed via REST in 01c — the feedback
 * module's bridge is the only caller and the consumer {@code TasteProfileDeltaApplier} is currently
 * a no-op stub that throws {@link UnsupportedOperationException} (the real impl ships in the
 * deferred {@code 01c-delta-applier} ticket).
 *
 * <p>The shape ships now so the feedback bridge in {@code tickets/feedback/01g} can compile against
 * a stable record.
 */
public record ApplyTasteProfileDeltasRequest(
    @NotNull @Size(max = 50) List<@Valid TasteProfileDelta> deltas,
    @NotNull TasteProfileTrigger trigger,
    @Nullable @Size(max = 64) String feedbackRangeStart,
    @Nullable @Size(max = 64) String feedbackRangeEnd,
    @Nullable @Size(max = 16) String modelTierUsed) {}
