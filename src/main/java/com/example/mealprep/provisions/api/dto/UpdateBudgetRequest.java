package com.example.mealprep.provisions.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body for {@code PUT /api/v1/provisions/budget}. {@code expectedVersion} is required on
 * update (matched against the persisted {@code @Version}) and treated as 0 / ignored on insert (no
 * row to lock against). {@code enabled} is a boxed {@link Boolean} with a {@code nullable: true}
 * OpenAPI schema; the service treats {@code null} as the field's documented default ({@code true}).
 */
public record UpdateBudgetRequest(
    @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 6, fraction = 2)
        BigDecimal weeklyTarget,
    @NotNull @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @DecimalMin("0.0") @Digits(integer = 6, fraction = 2) BigDecimal toleranceOver,
    @NotNull PriceSensitivity priceSensitivity,
    Boolean enabled,
    Long expectedVersion) {

  /** Resolved {@code enabled} flag — {@link Boolean#TRUE} when the request omits the field. */
  public boolean enabledOrDefault() {
    return enabled == null ? true : enabled;
  }

  /** Resolved {@code toleranceOver} — {@link BigDecimal#ZERO} when the request omits the field. */
  public BigDecimal toleranceOverOrZero() {
    return toleranceOver == null ? BigDecimal.ZERO : toleranceOver;
  }

  /** Resolved {@code expectedVersion} — {@code 0L} when the request omits the field. */
  public long expectedVersionOrZero() {
    return expectedVersion == null ? 0L : expectedVersion;
  }
}
