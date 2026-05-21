package com.example.mealprep.core.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the dev-profile CORS bean — bound to the {@code mealprep.cors.*}
 * prefix.
 *
 * <p>Defaults match the Vite dev-server convention ({@code http://localhost:5173}, one-hour
 * preflight cache). The record is only registered as a {@code @ConfigurationProperties} bean by
 * {@link DevCorsConfiguration}, which itself is gated to the {@code dev} Spring profile — meaning a
 * stray {@code mealprep.cors.*} entry in a prod yaml is inert, not weaponised.
 *
 * <p>Validation lives in the compact constructor: every field falls back to its documented default
 * when the property is absent, and {@link NotBlank} on {@code allowedOrigin} catches an explicit
 * empty override.
 */
@Validated
@ConfigurationProperties(prefix = "mealprep.cors")
public record CorsProperties(@NotBlank String allowedOrigin, Duration preflightMaxAge) {

  public CorsProperties {
    if (allowedOrigin == null || allowedOrigin.isBlank()) {
      allowedOrigin = "http://localhost:5173";
    }
    if (preflightMaxAge == null) {
      preflightMaxAge = Duration.ofHours(1);
    }
  }
}
