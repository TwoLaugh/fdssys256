package com.example.mealprep.nutrition.config;

import com.example.mealprep.nutrition.domain.entity.AdjustmentMagnitude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for single-field feedback target adjustment (nutrition-01i) — bound to
 * {@code mealprep.nutrition.feedback-adjustment.*}. The three {@code *Pct} keys map an {@link
 * AdjustmentMagnitude} step onto a fraction of the current value ({@code 0.05} == 5%); {@code
 * calorieFloor} is the sanity floor an adjusted daily-calorie target is clamped to (never driven to
 * zero or negative).
 *
 * <p>Spring Boot 3.x record-shaped {@code @ConfigurationProperties} are auto-{@code
 * ConstructorBinding}; defaults live in {@code application.properties} (and the test file shadowing
 * it). {@code @Validated} runs the Jakarta constraints at context-load — a bad override crashes
 * startup with a clear bind-validation message. Registered via
 * {@code @EnableConfigurationProperties} on {@code NutritionModule} (the project has no
 * {@code @ConfigurationPropertiesScan}).
 *
 * <p><b>GOTCHA</b>: a {@code @WebMvcTest} slice that loads a bean depending on this record must add
 * {@code @EnableConfigurationProperties(FeedbackAdjustmentProperties.class)}; a full
 * {@code @SpringBootTest} picks it up through {@code NutritionModule}.
 */
@ConfigurationProperties(prefix = "mealprep.nutrition.feedback-adjustment")
@Validated
public record FeedbackAdjustmentProperties(
    @NotNull @DecimalMin("0.0") BigDecimal smallPct,
    @NotNull @DecimalMin("0.0") BigDecimal moderatePct,
    @NotNull @DecimalMin("0.0") BigDecimal largePct,
    @Min(0) int calorieFloor) {

  /** The fraction of the current value to nudge for {@code magnitude}. */
  public BigDecimal pctFor(AdjustmentMagnitude magnitude) {
    return switch (magnitude) {
      case SMALL -> smallPct;
      case MODERATE -> moderatePct;
      case LARGE -> largePct;
    };
  }
}
