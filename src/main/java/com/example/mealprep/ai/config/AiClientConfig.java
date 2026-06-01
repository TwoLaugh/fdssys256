package com.example.mealprep.ai.config;

import com.example.mealprep.ai.domain.service.internal.AnthropicClient;
import com.example.mealprep.ai.domain.service.internal.ChatClient;
import com.example.mealprep.ai.domain.service.internal.OpenAiChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects which {@link ChatClient} the dispatcher ({@code AiServiceImpl}) dispatches to, per {@code
 * mealprep.ai.chat-provider} (default {@code openai}; see {@link OpenAiChatProperties}).
 *
 * <p>Both provider clients remain registered {@code @Component}s — {@link AnthropicClient} is
 * always present (its {@code RestClient} bean needs no key), and {@link OpenAiChatClient} is
 * present too (its underlying {@link com.openai.client.OpenAIClient} bean is the only key-gated
 * piece, mirroring the embedding client). Because both implement {@link ChatClient}, an unqualified
 * injection would be ambiguous; this factory resolves the ambiguity by exposing a single
 * {@code @Primary ChatClient} chosen at startup from the configured provider. Flipping {@code
 * chat-provider} to {@code anthropic} routes every chat task back through Anthropic with no code
 * change — the Anthropic path is selected, never deleted.
 *
 * <p>Each provider bean is injected via an {@link ObjectProvider} so the non-selected one need not
 * be eagerly resolved; the selected one is fetched and returned as the primary dispatch target.
 */
@Configuration
public class AiClientConfig {

  private static final Logger log = LoggerFactory.getLogger(AiClientConfig.class);

  @Bean
  @Primary
  public ChatClient chatClient(
      OpenAiChatProperties chatProperties,
      ObjectProvider<OpenAiChatClient> openAiChatClient,
      ObjectProvider<AnthropicClient> anthropicClient) {
    OpenAiChatProperties.Provider provider = chatProperties.chatProvider();
    log.info("ai chat provider selected: {}", provider);
    return switch (provider) {
      case OPENAI -> openAiChatClient.getObject();
      case ANTHROPIC -> anthropicClient.getObject();
    };
  }
}
