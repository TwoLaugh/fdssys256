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
 */
public record MacroTargetDto(
    @DecimalMin("0.0") BigDecimal targetG,
    @DecimalMin("0.0") BigDecimal floorG,
    @Size(max = 24) String enforcement,
    @NotNull EnforcementDirection direction) {}
