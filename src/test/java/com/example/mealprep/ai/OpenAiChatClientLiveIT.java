package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.internal.ChatResponse;
import com.example.mealprep.ai.domain.service.internal.CostCalculator;
import com.example.mealprep.ai.domain.service.internal.OpenAiChatClient;
import com.example.mealprep.ai.domain.service.internal.StructuredOutputParser;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Opt-in LIVE smoke for {@link OpenAiChatClient} — EXCLUDED from the blocking gate.
 *
 * <p>When run with a real {@code OPENAI_API_KEY} it makes <b>exactly ONE</b> cheap-tier chat call
 * that classifies a fixed string into a trivial 2-field structured-output schema, and asserts the
 * model returned schema-valid JSON and that token usage was reported (so the cost calculator
 * produces a non-zero cost — i.e. usage/cost recording is wired). It is the manual / nightly "does
 * OpenAI structured output actually work against the configured model id" check; the deterministic
 * regression lock is {@code OpenAiChatClientTest} (mocked SDK seam).
 *
 * <p><b>Why it is gate-excluded.</b> Tagged {@link Tag @Tag("live")}; the Surefire / Failsafe
 * config in {@code pom.xml} sets {@code <excludedGroups>${test.excludedGroups}</excludedGroups>}
 * (default {@code live}), so {@code ./mvnw test} / {@code ./mvnw verify} never runs it. It would
 * otherwise cost a real API call and depend on live model availability.
 *
 * <p><b>How to run it on demand (the orchestrator runs this once, not the build agent):</b>
 *
 * <pre>{@code OPENAI_API_KEY=sk-... \
 *   ./mvnw verify -Dgroups=live -Dtest.excludedGroups= \
 *     -Dit.test=OpenAiChatClientLiveIT}</pre>
 *
 * It self-skips via an assumption when {@code OPENAI_API_KEY} is unset, so a CI run that
 * accidentally selects the group on a key-less machine does not hard-fail. The model id comes from
 * {@code OPENAI_CHEAP_MODEL} (else the {@code mealprep.ai.openai.tier-cheap-model} placeholder) so
 * the runner can pin the validated id without a code change.
 */
@Tag("live")
class OpenAiChatClientLiveIT {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void liveCheapTierChat_returnsSchemaValidOutput_andReportsUsage() {
    String apiKey = System.getenv("OPENAI_API_KEY");
    Assumptions.assumeTrue(
        apiKey != null && !apiKey.isBlank(),
        "OPENAI_API_KEY unset — skipping the live OpenAI chat smoke");

    // Model id: env override, else the cheap-tier placeholder default from
    // OpenAiChatProperties.OpenAi.
    String cheapModel = System.getenv("OPENAI_CHEAP_MODEL");
    if (cheapModel == null || cheapModel.isBlank()) {
      cheapModel =
          new com.example.mealprep.ai.config.OpenAiChatProperties(null, null)
              .openai()
              .tierCheapModel();
    }

    OpenAIClient openAiClient = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
    AiProperties properties =
        new AiProperties(null, null, null, null, null, 60, 2, apiKey, null, null);
    StructuredOutputParser parser = new StructuredOutputParser(objectMapper);
    OpenAiChatClient client =
        new OpenAiChatClient(
            singleProvider(openAiClient),
            properties,
            objectMapper,
            parser,
            CircuitBreakerRegistry.ofDefaults());

    AiTask<JsonNode> task = classifyTask();

    // OpenAiChatClient.chat reads the assistant content via the SDK's raw/untyped JSON path
    // (choice._message().asObject()), so a strict structured-output response — whose `content`
    // comes back as a JSON OBJECT, not a string — no longer throws OpenAIInvalidDataException out
    // of the typed choice.message() accessor (the original failure at this line). We then assert on
    // the parsed structured output below, never touching the throwing typed accessor.
    ChatResponse response = client.chat(task, cheapModel);

    // Schema-valid: parsing through the same StructuredOutputParser the dispatcher uses must not
    // throw, and the body must carry both required fields (sentiment + confidence).
    JsonNode parsed = parser.parse(response.body(), schema(), JsonNode.class);
    assertThat(parsed.has("sentiment")).isTrue();
    assertThat(parsed.get("sentiment").asText()).isIn("positive", "negative", "neutral");
    assertThat(parsed.has("confidence")).isTrue();

    // Usage reported → cost calculator yields a non-zero cost (usage/cost recording is wired).
    assertThat(response.requestTokens()).isNotNull().isPositive();
    assertThat(response.responseTokens()).isNotNull();
    long costMicroPence =
        new CostCalculator()
            .compute(
                response.modelId() != null ? response.modelId() : cheapModel,
                response.requestTokens(),
                response.responseTokens() == null ? 0 : response.responseTokens());
    assertThat(costMicroPence).isPositive();
  }

  /**
   * Minimal {@link ObjectProvider} that always yields the single real client (the only seam {@link
   * OpenAiChatClient} uses is {@code getIfAvailable()}).
   */
  private static ObjectProvider<OpenAIClient> singleProvider(OpenAIClient client) {
    return new ObjectProvider<>() {
      @Override
      public OpenAIClient getObject() {
        return client;
      }

      @Override
      public OpenAIClient getObject(Object... args) {
        return client;
      }

      @Override
      public OpenAIClient getIfAvailable() {
        return client;
      }

      @Override
      public OpenAIClient getIfUnique() {
        return client;
      }
    };
  }

  /** A trivial fixed classification task with a strict 2-field structured-output schema. */
  private AiTask<JsonNode> classifyTask() {
    JsonNode schema = schema();
    ToolDefinition tool =
        new ToolDefinition(
            "sentiment_classification", "Classify the sentiment of the input text", schema);
    return new AiTask<>() {
      @Override
      public TaskType type() {
        return TaskType.FEEDBACK_CLASSIFICATION;
      }

      @Override
      public ModelTier tier() {
        return ModelTier.CHEAP;
      }

      @Override
      public PromptRef prompt() {
        return new PromptRef("live/sentiment", 1);
      }

      @Override
      @SuppressWarnings("unchecked")
      public Class<JsonNode> outputType() {
        return (Class<JsonNode>) (Class<?>) JsonNode.class;
      }

      @Override
      public Map<String, Object> variables() {
        return Map.of(
            "prompt",
            "Classify the sentiment of this text and respond with the JSON schema. "
                + "Text: \"This meal prep app saved me so much time, I love it!\"");
      }

      @Override
      public Optional<List<ToolDefinition>> tools() {
        return Optional.of(List.of(tool));
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

  /** 2-field strict object schema: {@code sentiment} (enum) + {@code confidence} (number). */
  private JsonNode schema() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "object");
    ObjectNode props = root.putObject("properties");
    ObjectNode sentiment = props.putObject("sentiment");
    sentiment.put("type", "string");
    sentiment.putArray("enum").add("positive").add("negative").add("neutral");
    props.putObject("confidence").put("type", "number");
    root.putArray("required").add("sentiment").add("confidence");
    root.put("additionalProperties", false);
    return root;
  }
}
