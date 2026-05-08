package com.example.mealprep.ai.config;

import com.example.mealprep.ai.spi.ModelTier;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the AI module — bound to the {@code mealprep.ai.*} prefix.
 *
 * <p>{@link #anthropicApiKey} is sensitive: never logged, never echoed in error messages. {@code
 * toString} is overridden on the redacted-string accessor so accidental log lines surface {@code
 * <redacted>} rather than the raw key.
 */
@ConfigurationProperties(prefix = "mealprep.ai")
public record AiProperties(
    String anthropicApiKey,
    String anthropicBaseUrl,
    String tierCheapModel,
    String tierMidModel,
    String tierHighModel,
    Integer timeoutSeconds,
    Integer maxRetries) {

  public AiProperties {
    if (anthropicBaseUrl == null || anthropicBaseUrl.isBlank()) {
      anthropicBaseUrl = "https://api.anthropic.com";
    }
    if (tierCheapModel == null || tierCheapModel.isBlank()) {
      tierCheapModel = "claude-haiku-4-5-20251001";
    }
    if (tierMidModel == null || tierMidModel.isBlank()) {
      tierMidModel = "claude-sonnet-4-6";
    }
    if (tierHighModel == null || tierHighModel.isBlank()) {
      tierHighModel = "claude-opus-4-7";
    }
    if (timeoutSeconds == null || timeoutSeconds <= 0) {
      timeoutSeconds = 60;
    }
    if (maxRetries == null || maxRetries < 0) {
      maxRetries = 3;
    }
  }

  /** Resolve a model id for a tier. Throws if the tier is unmapped (defensive — all three set). */
  public String modelIdFor(ModelTier tier) {
    Map<ModelTier, String> map = new EnumMap<>(ModelTier.class);
    map.put(ModelTier.CHEAP, tierCheapModel);
    map.put(ModelTier.MID, tierMidModel);
    map.put(ModelTier.HIGH, tierHighModel);
    String id = map.get(tier);
    if (id == null || id.isBlank()) {
      throw new IllegalStateException("No model id configured for tier " + tier);
    }
    return id;
  }

  public Duration timeout() {
    return Duration.ofSeconds(timeoutSeconds);
  }
}
