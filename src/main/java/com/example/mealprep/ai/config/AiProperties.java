package com.example.mealprep.ai.config;

import com.example.mealprep.ai.spi.ModelTier;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the AI module — bound to the {@code mealprep.ai.*} prefix.
 *
 * <p>{@link #anthropicApiKey} and {@link #openaiApiKey} are sensitive: never logged, never echoed
 * in error messages.
 */
@ConfigurationProperties(prefix = "mealprep.ai")
public record AiProperties(
    String anthropicApiKey,
    String anthropicBaseUrl,
    String tierCheapModel,
    String tierMidModel,
    String tierHighModel,
    Integer timeoutSeconds,
    Integer maxRetries,
    String openaiApiKey,
    Embedding embedding,
    Budget budget) {

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
    if (embedding == null) {
      embedding = new Embedding(null, null, null);
    }
    if (budget == null) {
      budget = new Budget(null, null, null);
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

  /**
   * Embedding-side configuration. {@link #model} is the OpenAI model id ({@code
   * text-embedding-3-small} for v1, 1536-dim). {@link #cacheSize} caps the in-memory Caffeine
   * cache; {@link #cacheTtlHours} is the per-entry expiry.
   */
  public record Embedding(String model, Integer cacheSize, Integer cacheTtlHours) {

    public Embedding {
      if (model == null || model.isBlank()) {
        model = "text-embedding-3-small";
      }
      if (cacheSize == null || cacheSize <= 0) {
        cacheSize = 10_000;
      }
      if (cacheTtlHours == null || cacheTtlHours <= 0) {
        cacheTtlHours = 24;
      }
    }
  }

  /**
   * Per-user rolling-window cost cap. {@code enabled=false} short-circuits the {@code
   * CostBudgetGuard} entirely — useful for dev / test convenience when the call log isn't seeded.
   */
  public record Budget(Boolean enabled, Long dailyPencePerUser, Integer windowHours) {

    public Budget {
      if (enabled == null) {
        enabled = true;
      }
      if (dailyPencePerUser == null || dailyPencePerUser < 0) {
        dailyPencePerUser = 50L;
      }
      if (windowHours == null || windowHours <= 0) {
        windowHours = 24;
      }
    }

    public Duration window() {
      return Duration.ofHours(windowHours);
    }
  }
}
