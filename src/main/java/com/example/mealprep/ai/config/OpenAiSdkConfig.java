package com.example.mealprep.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the {@link OpenAIClient} used by the embedding stack. We use the official {@code
 * openai-java} SDK rather than rolling another {@link org.springframework.web.client.RestClient};
 * the SDK already handles request signing, retries on its own internal layer, and the embeddings
 * response shape (which is structurally heavier than Anthropic's Messages API).
 *
 * <p>The bean is gated on {@code mealprep.ai.openai-api-key} being set. In the test profile {@code
 * TestAiService} replaces the dispatcher entirely so this bean is never exercised; we still
 * conditionally create it so a missing key in test does not fail context startup.
 */
@Configuration
public class OpenAiSdkConfig {

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "mealprep.ai.openai-api-key")
  public OpenAIClient openAiClient(AiProperties properties) {
    return OpenAIOkHttpClient.builder().apiKey(properties.openaiApiKey()).build();
  }
}
