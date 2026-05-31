package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Per-macro target (protein / carbs / fat / fibre / saturated fat). {@code floorG} is nullable —
 * not all macros carry a separate floor. The {@code direction} field encodes whether the target is
 * an upper limit, a floor, or both-bounded.
 *
 * <p>{@code isHardFloor} (LLD lines 774-776) separately controls the planner's <b>multiplicative
 * gate</b> (binary kill if the floor is not met), distinct from {@code direction} which governs the
 * soft sub-score's asymmetric penalty. Macros default to {@code true} (hard-floor enforcement). A
 * macro only contributes to the gate when it carries BOTH a non-null {@code floorG} AND {@code
 * isHardFloor == true}.
 */
public record MacroTargetDto(
    @DecimalMin("0.0") BigDecimal targetG,
    @DecimalMin("0.0") BigDecimal floorG,
    @Size(max = 24) String enforcement,
    @NotNull EnforcementDirection direction,
    boolean isHardFloor) {}
