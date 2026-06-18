package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.BaselineActivityLevel;
import com.example.mealprep.nutrition.domain.entity.BiologicalSex;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.LifeStage;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Demographics + goal for computing guideline-default targets (BMR calories, protein g/kg, micro DRI
 * band). Not persisted — the result is a preview the user reviews and edits before saving via the
 * normal targets PUT. {@code lifeStage} is optional (null ⇒ {@code NONE}) and only applies to a
 * reproductive-age female; the service falls back to {@code NONE} otherwise.
 */
public record ComputeTargetsRequest(
    @NotNull BiologicalSex biologicalSex,
    @NotNull @Past LocalDate dateOfBirth,
    @NotNull @DecimalMin("20.0") @DecimalMax("400.0") BigDecimal weightKg,
    @NotNull @DecimalMin("80.0") @DecimalMax("250.0") BigDecimal heightCm,
    @NotNull BaselineActivityLevel activityLevel,
    @NotNull Goal goal,
    LifeStage lifeStage) {}
