package com.example.mealprep.ai.config;

import com.example.mealprep.ai.spi.ModelTier;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat-provider selection + OpenAI model-tier configuration — bound to the {@code mealprep.ai.*}
 * prefix alongside {@link AiProperties}. Kept as its own record (rather than widening {@link
 * AiProperties}'s canonical constructor) so the new keys do not churn the ~25 existing {@code new
 * AiProperties(...)} unit-test call sites; Spring Boot binds two {@code @ConfigurationProperties}
 * records to the same prefix without conflict (each owns disjoint keys).
 *
 * <ul>
 *   <li>{@code mealprep.ai.chat-provider} — which {@link
 *       com.example.mealprep.ai.domain.service.internal.ChatClient} the dispatcher uses; one of
 *       {@link Provider#OPENAI} / {@link Provider#ANTHROPIC}. <b>Defaults to {@code openai}</b>.
 *       Flip to {@code anthropic} to route every chat task back through the Anthropic Messages API
 *       (the path is never deleted — only the wiring switches).
 *   <li>{@code mealprep.ai.openai.tier-{cheap,mid,high}-model} — the concrete OpenAI model id used
 *       per task tier, mirroring how {@link AiProperties#modelIdFor(ModelTier)} maps the Anthropic
 *       tiers. Picked by {@link com.example.mealprep.ai.domain.service.internal.OpenAiChatClient}
 *       from the task's tier.
 * </ul>
 */
@ConfigurationProperties(prefix = "mealprep.ai")
public record OpenAiChatProperties(Provider chatProvider, OpenAi openai) {

  public OpenAiChatProperties {
    if (chatProvider == null) {
      // Default provider is OpenAI per the foundational prompt-engineering migration.
      chatProvider = Provider.OPENAI;
    }
    if (openai == null) {
      openai = new OpenAi(null, null, null);
    }
  }

  /** Which chat provider the dispatcher dispatches to. */
  public enum Provider {
    OPENAI,
    ANTHROPIC
  }

  /**
   * OpenAI per-tier model ids ({@code mealprep.ai.openai.tier-*-model}).
   *
   * <p>NOTE: the default model-id strings below are <b>placeholders validated by the opt-in live
   * smoke</b> ({@code OpenAiChatClientLiveIT}, {@code @Tag("live")}) and may be corrected in config
   * once that one-call smoke confirms the exact ids the account can reach. They live ONLY here in
   * config — no model id is hardcoded anywhere in the client code. Mapping intent mirrors the
   * Anthropic tiers: cheap / high-volume → a mini model; mid + high (complex) → a stronger model.
   */
  public record OpenAi(String tierCheapModel, String tierMidModel, String tierHighModel) {

    public OpenAi {
      if (tierCheapModel == null || tierCheapModel.isBlank()) {
        // High-volume / cheap tier — a mini model. PLACEHOLDER: validated by the live smoke.
        tierCheapModel = "gpt-5.4-mini";
      }
      if (tierMidModel == null || tierMidModel.isBlank()) {
        // Complex tier — a stronger model. PLACEHOLDER: validated by the live smoke.
        tierMidModel = "gpt-5.5";
      }
      if (tierHighModel == null || tierHighModel.isBlank()) {
        // Complex tier — a stronger model. PLACEHOLDER: validated by the live smoke.
        tierHighModel = "gpt-5.5";
      }
    }

    /** Resolve the OpenAI model id for a tier. Throws if unmapped (defensive — all three set). */
    public String modelIdFor(ModelTier tier) {
      Map<ModelTier, String> map = new EnumMap<>(ModelTier.class);
      map.put(ModelTier.CHEAP, tierCheapModel);
      map.put(ModelTier.MID, tierMidModel);
      map.put(ModelTier.HIGH, tierHighModel);
      String id = map.get(tier);
      if (id == null || id.isBlank()) {
        throw new IllegalStateException("No OpenAI model id configured for tier " + tier);
      }
      return id;
    }
  }
}
