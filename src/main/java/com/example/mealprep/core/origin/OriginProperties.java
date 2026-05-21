package com.example.mealprep.core.origin;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the origin-tracking filter — confidence floor, rate-limit configuration per origin,
 * defence-in-depth toggle for the unannotated-controller check.
 *
 * <p>Bound from {@code application.properties} under the {@code mealprep.origin} prefix. Per
 * tickets/core/02b-origin-tracking-foundation.md §Configuration.
 *
 * @param aiConfidenceFloor below this value, AI_FEEDBACK / AI_ADAPTATION calls are rejected 422
 * @param rateLimitWindow window over which {@link RateLimitConfig#limit()} applies
 * @param rateLimits per-origin rate limits; absent map entries mean "no limit"
 * @param rejectOriginOnNonAnnotatedController if true (default), non-USER origin on a controller
 *     method lacking {@link OriginAware} returns 403
 */
@ConfigurationProperties(prefix = "mealprep.origin")
@Validated
public record OriginProperties(
    @NotNull BigDecimal aiConfidenceFloor,
    @NotNull Duration rateLimitWindow,
    @NotNull Map<Origin, RateLimitConfig> rateLimits,
    boolean rejectOriginOnNonAnnotatedController) {

  /** Rate-limit configuration for one origin kind. */
  public record RateLimitConfig(int limit, Scope scope) {}

  /** Per-user (one bucket per (origin, userId)) vs. global (one bucket per origin). */
  public enum Scope {
    PER_USER,
    GLOBAL
  }
}
