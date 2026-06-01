package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.internal.AnthropicClient;
import com.example.mealprep.ai.domain.service.internal.AnthropicResponse;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link AnthropicClient}. We hand-build a {@link RestClient} backed by a stub
 * {@code ClientHttpRequestExecution} so we can script status / body / IO failures without spinning
 * up a server.
 */
class AnthropicClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AiProperties properties =
      new AiProperties(
          "secret-key", "https://example.test", "haiku", "sonnet", "opus", 60, 3, null, null, null);

  private final Deque<RoundTrip> scripted = new ArrayDeque<>();

  private final ClientHttpRequestExecution stubExecution =
      (HttpRequest req, byte[] body) -> {
        if (scripted.isEmpty()) {
          throw new AssertionError("No more scripted responses");
        }
        RoundTrip rt = scripted.poll();
        if (rt.error != null) {
          throw rt.error;
        }
        return new StubResponse(rt.status, rt.body);
      };

  private RestClient restClient;
  private AnthropicClient client;

  @BeforeEach
  void setUp() {
    restClient =
        RestClient.builder()
            .baseUrl(properties.anthropicBaseUrl())
            .requestInterceptor((req, body, exec) -> stubExecution.execute(req, body))
            .build();
    client =
        new AnthropicClient(
            restClient, properties, objectMapper, CircuitBreakerRegistry.ofDefaults());
    // No-op sleeper so tests don't sleep in retry backoff.
    client.setSleeper(ms -> {});
  }

  @Test
  void parse_extractsTextContentAndUsage() {
    String body =
        "{\"model\":\"claude-haiku-4-5-20251001\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"Hello, world.\"}],"
            + "\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}";
    AnthropicResponse parsed = client.parse(body);
    assertThat(parsed.body()).isEqualTo("Hello, world.");
    assertThat(parsed.requestTokens()).isEqualTo(12);
    assertThat(parsed.responseTokens()).isEqualTo(4);
    assertThat(parsed.modelId()).isEqualTo("claude-haiku-4-5-20251001");
  }

  @Test
  void parse_extractsToolUseInputAheadOfText() {
    String body =
        "{\"content\":[{\"type\":\"tool_use\",\"name\":\"answer\",\"input\":{\"k\":1}},"
            + "{\"type\":\"text\",\"text\":\"ignored\"}]}";
    AnthropicResponse parsed = client.parse(body);
    assertThat(parsed.body()).isEqualTo("{\"k\":1}");
  }

  @Test
  void parse_throwsOnNonJson() {
    assertThatThrownBy(() -> client.parse("not json")).isInstanceOf(AiUnavailableException.class);
  }

  @Test
  void buildRequestBody_includesPromptVariableAndTools() throws Exception {
    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withTier(ModelTier.CHEAP)
            .withPrompt(new PromptRef("test/x", 1))
            .withVariable("prompt", "explain")
            .withTool(AiTestData.simpleTool("answer", objectMapper))
            .build();
    String wire = client.buildRequestBody(task, "claude-haiku-4-5-20251001");
    JsonNode parsed = objectMapper.readTree(wire);
    assertThat(parsed.get("model").asText()).isEqualTo("claude-haiku-4-5-20251001");
    assertThat(parsed.get("messages").get(0).get("content").asText()).isEqualTo("explain");
    assertThat(parsed.get("tools").isArray()).isTrue();
    assertThat(parsed.get("tools").get(0).get("name").asText()).isEqualTo("answer");
  }

  @Test
  void call_succeedsOnFirstAttempt() {
    String responseBody =
        "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    scripted.add(RoundTrip.ok(responseBody));
    AnthropicResponse parsed = client.call(stubTask(), "haiku");
    assertThat(parsed.body()).isEqualTo("ok");
  }

  @Test
  void chat_seam_adaptsCallResultToChatResponse_fieldForField() {
    // The ChatClient.chat seam must carry through all four fields of the underlying call() result.
    String responseBody =
        "{\"model\":\"claude-haiku-4-5-20251001\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
            + "\"usage\":{\"input_tokens\":7,\"output_tokens\":2}}";
    scripted.add(RoundTrip.ok(responseBody));
    com.example.mealprep.ai.domain.service.internal.ChatResponse chat =
        client.chat(stubTask(), "haiku");
    assertThat(chat.body()).isEqualTo("hi");
    assertThat(chat.requestTokens()).isEqualTo(7);
    assertThat(chat.responseTokens()).isEqualTo(2);
    assertThat(chat.modelId()).isEqualTo("claude-haiku-4-5-20251001");
  }

  @Test
  void call_translates4xxToInvalidRequest_andDoesNotRetry() {
    scripted.add(RoundTrip.status(HttpStatus.BAD_REQUEST, "{\"error\":\"bad\"}"));
    assertThatThrownBy(() -> client.call(stubTask(), "haiku"))
        .isInstanceOf(AiInvalidRequestException.class);
    // A second scripted entry remains => the retry would have consumed it. Confirm we did NOT.
    assertThat(scripted).isEmpty();
  }

  @Test
  void call_retries5xxThenSucceeds() {
    scripted.add(RoundTrip.status(HttpStatus.INTERNAL_SERVER_ERROR, "boom"));
    scripted.add(RoundTrip.ok("{\"content\":[{\"type\":\"text\",\"text\":\"recovered\"}]}"));
    AnthropicResponse parsed = client.call(stubTask(), "haiku");
    assertThat(parsed.body()).isEqualTo("recovered");
    assertThat(scripted).isEmpty();
  }

  @Test
  void call_retriesIoExceptionThenSucceeds() {
    scripted.add(RoundTrip.error(new java.io.IOException("connection reset")));
    scripted.add(RoundTrip.ok("{\"content\":[{\"type\":\"text\",\"text\":\"recovered\"}]}"));
    AnthropicResponse parsed = client.call(stubTask(), "haiku");
    assertThat(parsed.body()).isEqualTo("recovered");
  }

  @Test
  void call_throwsAiUnavailableAfterRetriesExhausted() {
    // maxRetries=3 ⇒ 1 initial attempt + 3 retries = 4 wire attempts (ai-9: retries, not attempts).
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    assertThatThrownBy(() -> client.call(stubTask(), "haiku"))
        .isInstanceOf(AiUnavailableException.class);
    // All four scripted attempts consumed — confirms 1 + maxRetries, not maxRetries.
    assertThat(scripted).isEmpty();
  }

  @Test
  void maxAttempts_isOnePlusConfiguredRetries() {
    // ai-9: the field is "retries"; total attempts = 1 + retries.
    AiProperties zeroRetries =
        new AiProperties(
            "k", "https://example.test", "haiku", "sonnet", "opus", 60, 0, null, null, null);
    AnthropicClient zero =
        new AnthropicClient(
            restClient, zeroRetries, objectMapper, CircuitBreakerRegistry.ofDefaults());
    assertThat(zero.maxAttempts()).isEqualTo(1);
    assertThat(client.maxAttempts()).isEqualTo(4); // default maxRetries=3
  }

  @Test
  void call_maxRetriesZero_makesExactlyOneAttempt_noRetry() {
    AiProperties zeroRetries =
        new AiProperties(
            "k", "https://example.test", "haiku", "sonnet", "opus", 60, 0, null, null, null);
    AnthropicClient zero =
        new AnthropicClient(
            restClient, zeroRetries, objectMapper, CircuitBreakerRegistry.ofDefaults());
    zero.setSleeper(ms -> {});
    // Two failures scripted; with zero retries only the first is consumed before it throws.
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    assertThatThrownBy(() -> zero.call(stubTask(), "haiku"))
        .isInstanceOf(AiUnavailableException.class);
    // The second scripted response was NOT consumed — exactly one wire attempt was made.
    assertThat(scripted).hasSize(1);
  }

  @Test
  void call_appliesJitteredBackoffBetweenRetries_boundedByExponentialCeiling() {
    // Capture the backoff durations the client sleeps for, with a fixed-seed jitter source so the
    // values are deterministic. RATE_LIMIT base is 1000ms: attempt 1 ceiling 1000 → wait in
    // [500,1000]; attempt 2 ceiling 2000 → wait in [1000,2000]; attempt 3 ceiling 4000 →
    // [2000,4000]. (maxRetries=3 ⇒ 4 attempts ⇒ 3 inter-attempt waits.)
    java.util.List<Long> sleeps = new java.util.ArrayList<>();
    client.setSleeper(sleeps::add);
    client.setJitterRandom(new java.util.Random(42));
    for (int i = 0; i < 4; i++) {
      scripted.add(RoundTrip.status(HttpStatus.TOO_MANY_REQUESTS, "rate limited"));
    }
    assertThatThrownBy(() -> client.call(stubTask(), "haiku"))
        .isInstanceOf(com.example.mealprep.ai.exception.AiRateLimitException.class);
    assertThat(sleeps).hasSize(3);
    assertThat(sleeps.get(0)).isBetween(500L, 1000L);
    assertThat(sleeps.get(1)).isBetween(1000L, 2000L);
    assertThat(sleeps.get(2)).isBetween(2000L, 4000L);
  }

  private AiTask<String> stubTask() {
    return AiTestData.task(String.class)
        .ofType(TaskType.FEEDBACK_CLASSIFICATION)
        .withTier(ModelTier.CHEAP)
        .build();
  }

  // ---- stub plumbing ----

  private record RoundTrip(HttpStatus status, String body, java.io.IOException error) {
    static RoundTrip ok(String body) {
      return new RoundTrip(HttpStatus.OK, body, null);
    }

    static RoundTrip status(HttpStatus status, String body) {
      return new RoundTrip(status, body, null);
    }

    static RoundTrip error(java.io.IOException error) {
      return new RoundTrip(null, null, error);
    }
  }

  private static final class StubResponse implements ClientHttpResponse {
    private final HttpStatus status;
    private final byte[] body;
    private final HttpHeaders headers = new HttpHeaders();

    StubResponse(HttpStatus status, String body) {
      this.status = status;
      this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public HttpStatusCode getStatusCode() {
      return status;
    }

    @Override
    public String getStatusText() {
      return status.getReasonPhrase();
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }

    @Override
    public java.io.InputStream getBody() {
      return new ByteArrayInputStream(body);
    }

    @Override
    public void close() {
      // no-op
    }
  }
}
