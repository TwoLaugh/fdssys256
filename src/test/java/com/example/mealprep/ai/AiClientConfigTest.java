package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiClientConfig;
import com.example.mealprep.ai.config.OpenAiChatProperties;
import com.example.mealprep.ai.domain.service.internal.AnthropicClient;
import com.example.mealprep.ai.domain.service.internal.ChatClient;
import com.example.mealprep.ai.domain.service.internal.OpenAiChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit coverage for {@link AiClientConfig}'s provider-selection factory — the {@code
 * mealprep.ai.chat-provider} switch that picks which {@link ChatClient} the dispatcher uses.
 */
class AiClientConfigTest {

  private final AiClientConfig config = new AiClientConfig();
  private final OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
  private final AnthropicClient anthropicClient = mock(AnthropicClient.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<OpenAiChatClient> openAiProvider = mock(ObjectProvider.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<AnthropicClient> anthropicProvider = mock(ObjectProvider.class);

  @Test
  void selectsOpenAi_whenProviderIsOpenAi() {
    when(openAiProvider.getObject()).thenReturn(openAiChatClient);
    OpenAiChatProperties props =
        new OpenAiChatProperties(OpenAiChatProperties.Provider.OPENAI, null);

    ChatClient selected = config.chatClient(props, openAiProvider, anthropicProvider);

    assertThat(selected).isSameAs(openAiChatClient);
  }

  @Test
  void selectsAnthropic_whenProviderIsAnthropic() {
    when(anthropicProvider.getObject()).thenReturn(anthropicClient);
    OpenAiChatProperties props =
        new OpenAiChatProperties(OpenAiChatProperties.Provider.ANTHROPIC, null);

    ChatClient selected = config.chatClient(props, openAiProvider, anthropicProvider);

    assertThat(selected).isSameAs(anthropicClient);
  }

  @Test
  void defaultProvider_selectsOpenAi() {
    when(openAiProvider.getObject()).thenReturn(openAiChatClient);
    // null chat-provider → OpenAiChatProperties defaults to OPENAI.
    OpenAiChatProperties props = new OpenAiChatProperties(null, null);

    ChatClient selected = config.chatClient(props, openAiProvider, anthropicProvider);

    assertThat(selected).isSameAs(openAiChatClient);
  }
}
