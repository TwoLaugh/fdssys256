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
      budget = Budget.ofDaily(null, null, null);
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
   * Two-scope rolling-window cost cap (lld/ai.md Flow 1 step 5 + Decisions §7). {@code
   * enabled=false} short-circuits the {@code CostBudgetGuard} entirely — useful for dev / test
   * convenience when the call log isn't seeded.
   *
   * <p>Two independent scopes are evaluated per dispatch:
   *
   * <ul>
   *   <li><b>DAILY_USER</b> — per-user spend over {@link #windowHours} (default 24h). {@link
   *       #dailyHardBlock} defaults {@code false}: crossing it publishes a {@code
   *       CostBudgetExceededEvent} and logs, but the call still proceeds (soft alert). Set {@code
   *       true} to turn the daily cap into a per-user hard block.
   *   <li><b>MONTHLY_TOTAL</b> — system-wide spend across all users over {@link
   *       #monthlyWindowHours} (default 720h ≈ 30 days). {@link #monthlyHardBlock} defaults {@code
   *       true}: crossing it throws {@link
   *       com.example.mealprep.ai.exception.AiCostBudgetExceededException} — the runaway-spend kill
   *       switch. The monthly scope has no {@code userId} (it bills the system, not a person).
   * </ul>
   *
   * <p>{@code dailyPencePerUser} / {@code windowHours} retain their original names for config
   * backward-compatibility.
   */
  public record Budget(
      Boolean enabled,
      Long dailyPencePerUser,
      Integer windowHours,
      Boolean dailyHardBlock,
      Long monthlyPenceTotal,
      Integer monthlyWindowHours,
      Boolean monthlyHardBlock) {

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
      if (dailyHardBlock == null) {
        // Daily cap is soft by default (alert-and-proceed); see Decisions §7.
        dailyHardBlock = false;
      }
      if (monthlyPenceTotal == null || monthlyPenceTotal < 0) {
        // £200/month system-wide default — the runaway-spend ceiling, not a per-user limit.
        monthlyPenceTotal = 20_000L;
      }
      if (monthlyWindowHours == null || monthlyWindowHours <= 0) {
        monthlyWindowHours = 24 * 30;
      }
      if (monthlyHardBlock == null) {
        // Monthly cap is a hard block by default (Decisions §7).
        monthlyHardBlock = true;
      }
    }

    /**
     * Backward-compatible 3-arg factory — daily values set, daily soft, monthly scope at defaults.
     * A static factory (not a second constructor) keeps the record's <em>single</em> canonical
     * constructor unambiguous for Spring's {@code @ConfigurationProperties} constructor binding.
     */
    public static Budget ofDaily(Boolean enabled, Long dailyPencePerUser, Integer windowHours) {
      return new Budget(enabled, dailyPencePerUser, windowHours, null, null, null, null);
    }

    /** Rolling window for the per-user DAILY_USER scope. */
    public Duration window() {
      return Duration.ofHours(windowHours);
    }

    /** Rolling window for the system-wide MONTHLY_TOTAL scope. */
    public Duration monthlyWindow() {
      return Duration.ofHours(monthlyWindowHours);
    }
  }
}
