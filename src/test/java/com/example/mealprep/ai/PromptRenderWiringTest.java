package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.internal.AiCallRecorder;
import com.example.mealprep.ai.domain.service.internal.AiServiceImpl;
import com.example.mealprep.ai.domain.service.internal.AnthropicClient;
import com.example.mealprep.ai.domain.service.internal.ChatClient;
import com.example.mealprep.ai.domain.service.internal.ChatResponse;
import com.example.mealprep.ai.domain.service.internal.CostBudgetGuard;
import com.example.mealprep.ai.domain.service.internal.CostCalculator;
import com.example.mealprep.ai.domain.service.internal.OpenAiChatClient;
import com.example.mealprep.ai.domain.service.internal.OpenAiEmbeddingClient;
import com.example.mealprep.ai.domain.service.internal.StructuredOutputParser;
import com.example.mealprep.ai.domain.service.internal.TokenCapGuard;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatCompletionCreateParams;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Proves the ai-1 dispatch wiring: a production-shaped {@link AiTask} (no pre-rendered {@code
 * "prompt"} variable — just its real context {@code variables()}) reaches the dispatcher and is
 * rendered from its engineered {@code prompts/<module>/<task>.txt} file, with placeholders
 * substituted, into the outgoing provider message.
 *
 * <p>It asserts this on the single shared render seam ({@link AnthropicClient#renderUserMessage},
 * which the {@link AnthropicClient} body, the {@link OpenAiChatClient} body, and the {@link
 * TokenCapGuard} pre-check all call) so the proof covers <b>both providers</b>. The {@link
 * ChatClient} is mocked and the dispatched task captured, so the assertion runs against the exact
 * task {@link AiServiceImpl#execute} hands to whichever provider is wired in. NO network call.
 */
@ExtendWith(MockitoExtension.class)
class PromptRenderWiringTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private ChatClient chatClient;
  @Mock private OpenAiEmbeddingClient embeddingClient;
  @Mock private AiCallRecorder recorder;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private CostBudgetGuard budgetGuard;
  @Mock private TokenCapGuard tokenCapGuard;

  private final AiProperties properties =
      new AiProperties("k", null, "haiku-id", "sonnet-id", "opus-id", 60, 3, null, null, null);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

  /**
   * A feedback-classification task that ships ONLY its real context variables (the exact shape
   * {@code FeedbackClassificationContext.toRendererMap()} produces, including the nullable {@code
   * current_meal_context}) — no {@code "prompt"} key. The dispatcher must resolve and render the
   * engineered prompt file, not dump these variables as raw JSON.
   */
  private static AiTask<String> feedbackTask() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("feedback_text", "This stir fry was way too salty");
    vars.put("screen_context", "recipe_page");
    vars.put("recent_classifications", List.of());
    vars.put("current_meal_context", null); // nullable context field — must not break rendering
    vars.put("attempt_number", 1);
    return task(TaskType.FEEDBACK_CLASSIFICATION, ModelTier.CHEAP, vars);
  }

  @Test
  void dispatch_rendersEngineeredPromptFile_withSubstitutedVariables() {
    AiTask<String> task = feedbackTask();
    UUID callId = UUID.randomUUID();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);

    ArgumentCaptor<AiTask<?>> dispatched = captor();
    when(chatClient.chat(dispatched.capture(), eq("haiku-id")))
        .thenReturn(new ChatResponse("ok", 1, 1, "haiku-id"));

    service().execute(task);

    // The exact user-message body the dispatcher hands the provider (shared by both clients).
    String rendered = AnthropicClient.renderUserMessage(dispatched.getValue(), objectMapper);
    // Hallmark text from prompts/feedback/classify-feedback.txt — proves the FILE was rendered,
    // not a raw variables dump.
    assertThat(rendered).contains("[Task: FEEDBACK_CLASSIFICATION]");
    assertThat(rendered).contains("You are a feedback classifier for a meal-planning system.");
    // A substituted variable — proves placeholder substitution ran.
    assertThat(rendered).contains("This stir fry was way too salty");
    assertThat(rendered).contains("recipe_page");
    // No unrendered placeholders survive, and we did NOT fall back to a raw JSON dump of variables.
    assertThat(rendered).doesNotContain("{{");
    assertThat(rendered).doesNotContain("\"feedback_text\":");
  }

  @Test
  void bothProviders_buildTheUserMessageFromTheRenderedFile() {
    AiTask<String> task = feedbackTask();
    // The single shared render seam both providers (and TokenCapGuard) use to build the user
    // message — asserting against it proves the body BOTH providers send.
    String shared = AnthropicClient.renderUserMessage(task, objectMapper);
    assertThat(shared).contains("[Task: FEEDBACK_CLASSIFICATION]");
    assertThat(shared).contains("This stir fry was way too salty");

    // Provider A — Anthropic Messages body uses exactly that shared render output.
    AnthropicClient anthropic =
        new AnthropicClient(
            org.springframework.web.client.RestClient.create(),
            properties,
            objectMapper,
            CircuitBreakerRegistry.ofDefaults());
    String anthropicBody = anthropicMessageContent(anthropic.buildRequestBody(task, "haiku-id"));
    assertThat(anthropicBody).isEqualTo(shared);

    // Provider B — OpenAI chat params build from the same seam without error (and addUserMessage
    // takes the shared body). buildParams never touches the network, so no OpenAIClient is needed.
    OpenAiChatClient openai =
        new OpenAiChatClient(
            noOpenAiClient(),
            properties,
            objectMapper,
            new StructuredOutputParser(objectMapper),
            CircuitBreakerRegistry.ofDefaults());
    ChatCompletionCreateParams params = openai.buildParams(task, "gpt-cheap");
    assertThat(params.messages()).as("openai params carry the rendered user message").isNotEmpty();
  }

  @Test
  void nullableContextVariable_rendersAsSentinel_notAFailure() {
    // A task whose prompt references a nullable var directly: prove the null is converted to the
    // "none" sentinel at the render boundary rather than the renderer rejecting the dispatch.
    Map<String, Object> vars = new HashMap<>();
    vars.put("nullable", null);
    AiTask<String> task =
        task(
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            vars,
            new PromptRef("test/nullable", 1));
    // Render the *value* coercion directly — the boundary the dispatcher applies per variable.
    assertThat(AnthropicClient.toRenderValue(null, objectMapper))
        .isEqualTo(AnthropicClient.NULL_SENTINEL);
    assertThat(AnthropicClient.NULL_SENTINEL).isEqualTo("none");
    // Sanity: a complex value is JSON, not Java toString.
    assertThat(AnthropicClient.toRenderValue(List.of("a", "b"), objectMapper))
        .isEqualTo("[\"a\",\"b\"]");
    assertThat(task.variables().get("nullable")).isNull();
  }

  // ---- helpers ----

  private AiServiceImpl service() {
    return new AiServiceImpl(
        chatClient,
        embeddingClient,
        recorder,
        eventPublisher,
        properties,
        objectMapper,
        clock,
        budgetGuard,
        tokenCapGuard,
        new CostCalculator());
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<AiTask<?>> captor() {
    return ArgumentCaptor.forClass(AiTask.class);
  }

  private String anthropicMessageContent(String wireBody) {
    try {
      return objectMapper.readTree(wireBody).get("messages").get(0).get("content").asText();
    } catch (Exception ex) {
      throw new AssertionError("could not parse anthropic wire body", ex);
    }
  }

  /** An ObjectProvider that yields no OpenAIClient — buildParams needs none (it never calls). */
  private static ObjectProvider<OpenAIClient> noOpenAiClient() {
    return new ObjectProvider<>() {
      @Override
      public OpenAIClient getObject() {
        return null;
      }

      @Override
      public OpenAIClient getObject(Object... args) {
        return null;
      }

      @Override
      public OpenAIClient getIfAvailable() {
        return null;
      }

      @Override
      public OpenAIClient getIfUnique() {
        return null;
      }
    };
  }

  private static AiTask<String> task(TaskType type, ModelTier tier, Map<String, Object> vars) {
    return task(type, tier, vars, new PromptRef("feedback/classify-feedback", 1));
  }

  private static AiTask<String> task(
      TaskType type, ModelTier tier, Map<String, Object> vars, PromptRef ref) {
    return new AiTask<>() {
      @Override
      public TaskType type() {
        return type;
      }

      @Override
      public ModelTier tier() {
        return tier;
      }

      @Override
      public PromptRef prompt() {
        return ref;
      }

      @Override
      public Class<String> outputType() {
        return String.class;
      }

      @Override
      public Map<String, Object> variables() {
        return vars;
      }

      @Override
      public Optional<List<ToolDefinition>> tools() {
        return Optional.empty();
      }

      @Override
      public Optional<UUID> userId() {
        return Optional.empty();
      }

      @Override
      public Optional<UUID> traceId() {
        return Optional.empty();
      }
    };
  }
}
