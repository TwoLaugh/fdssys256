package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.config.OpenAiChatProperties;
import com.example.mealprep.ai.spi.ModelTier;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link OpenAiChatProperties} — the chat-provider selection + OpenAI model-tier
 * binding. Asserts the compact-constructor defaults (provider {@code openai}; the placeholder model
 * ids) and the per-tier resolution mirror of {@code AiProperties.modelIdFor}.
 */
class OpenAiChatPropertiesTest {

  @Test
  void defaults_providerOpenAi_andPlaceholderModelIds() {
    OpenAiChatProperties props = new OpenAiChatProperties(null, null);
    assertThat(props.chatProvider()).isEqualTo(OpenAiChatProperties.Provider.OPENAI);
    assertThat(props.openai().tierCheapModel()).isEqualTo("gpt-5.4-mini");
    assertThat(props.openai().tierMidModel()).isEqualTo("gpt-5.5");
    assertThat(props.openai().tierHighModel()).isEqualTo("gpt-5.5");
  }

  @Test
  void explicitProvider_isHonoured() {
    OpenAiChatProperties props =
        new OpenAiChatProperties(OpenAiChatProperties.Provider.ANTHROPIC, null);
    assertThat(props.chatProvider()).isEqualTo(OpenAiChatProperties.Provider.ANTHROPIC);
  }

  @Test
  void blankModelIds_fallBackToPlaceholders() {
    OpenAiChatProperties.OpenAi openai = new OpenAiChatProperties.OpenAi("  ", "", null);
    assertThat(openai.tierCheapModel()).isEqualTo("gpt-5.4-mini");
    assertThat(openai.tierMidModel()).isEqualTo("gpt-5.5");
    assertThat(openai.tierHighModel()).isEqualTo("gpt-5.5");
  }

  @Test
  void modelIdFor_resolvesEachTier() {
    OpenAiChatProperties.OpenAi openai =
        new OpenAiChatProperties.OpenAi("cheap-m", "mid-m", "high-m");
    assertThat(openai.modelIdFor(ModelTier.CHEAP)).isEqualTo("cheap-m");
    assertThat(openai.modelIdFor(ModelTier.MID)).isEqualTo("mid-m");
    assertThat(openai.modelIdFor(ModelTier.HIGH)).isEqualTo("high-m");
  }

  @Test
  void customModelIds_areHonoured() {
    OpenAiChatProperties props =
        new OpenAiChatProperties(
            OpenAiChatProperties.Provider.OPENAI,
            new OpenAiChatProperties.OpenAi("gpt-x-mini", "gpt-x", "gpt-x-pro"));
    assertThat(props.openai().tierCheapModel()).isEqualTo("gpt-x-mini");
    assertThat(props.openai().modelIdFor(ModelTier.HIGH)).isEqualTo("gpt-x-pro");
  }

  @Test
  void modelIdFor_unmappedTier_throws() {
    // Defensive guard mirrors AiProperties.modelIdFor — a null tier resolves nothing.
    OpenAiChatProperties.OpenAi openai =
        new OpenAiChatProperties.OpenAi("cheap-m", "mid-m", "high-m");
    assertThatThrownBy(() -> openai.modelIdFor(null)).isInstanceOf(IllegalStateException.class);
  }
}
