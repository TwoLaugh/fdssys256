package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.internal.AiCallRecorder;
import com.example.mealprep.ai.domain.service.internal.AiServiceImpl;
import com.example.mealprep.ai.domain.service.internal.AnthropicClient;
import com.example.mealprep.ai.domain.service.internal.AnthropicResponse;
import com.example.mealprep.ai.domain.service.internal.CostBudgetGuard;
import com.example.mealprep.ai.domain.service.internal.CostCalculator;
import com.example.mealprep.ai.domain.service.internal.OpenAiEmbeddingClient;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.models.CreateEmbeddingResponse;
import com.openai.models.Embedding;
import com.openai.models.EmbeddingCreateParams;
import com.openai.services.blocking.EmbeddingService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Central pitest-survivor kill battery for the {@code ai} module. Each test names the surviving
 * mutator(s) it targets in its method comment; tests are unit-only and mock the HTTP / repository
 * seams so the suite stays under 5 s.
 */
class AiMutationKillsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  // ============================================================================================
  // AnthropicClient survivors
  // ============================================================================================

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code call:71} ConditionalsBoundary (changing {@code attempt<=maxAttempts} to {@code <}
   *       skips the final attempt, so a 2-failure → 3rd-success sequence yields an Unavailable
   *       instead of a recovered body).
   *   <li>{@code call:83} Math (shift-left → shift-right; subtraction → addition) — both alter the
   *       backoff series. We feed an asserting sleeper that requires {@code 200, 400} for the two
   *       inter-retry delays.
   *   <li>{@code call:90} VoidMethodCall (removed {@code sleepQuietly}) — same asserting sleeper:
   *       if the call is removed, no delay is recorded and the assertion fails.
   *   <li>{@code sleepQuietly:235} VoidMethodCall (removed {@code sleeper.sleep}) — the sleeper
   *       stub asserts it was invoked.
   * </ul>
   */
  @Test
  void anthropic_call_retries_backoffSeriesIs200_400_msAndSleeperIsActuallyCalled() {
    AiProperties properties =
        new AiProperties(
            "k", "https://example.test", "haiku", "sonnet", "opus", 60, 3, null, null, null);

    java.util.Deque<RoundTrip> scripted = new java.util.ArrayDeque<>();
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    scripted.add(RoundTrip.status(HttpStatus.BAD_GATEWAY, "x"));
    scripted.add(RoundTrip.ok("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));

    ClientHttpRequestExecution exec =
        (HttpRequest req, byte[] body) -> {
          RoundTrip rt = scripted.poll();
          if (rt == null) throw new AssertionError("ran out of scripted responses");
          if (rt.error != null) throw rt.error;
          return new StubResponse(rt.status, rt.body);
        };
    RestClient rest =
        RestClient.builder()
            .baseUrl(properties.anthropicBaseUrl())
            .requestInterceptor((req, body, e) -> exec.execute(req, body))
            .build();
    AnthropicClient client = new AnthropicClient(rest, properties, objectMapper);
    List<Long> sleeps = new ArrayList<>();
    client.setSleeper(sleeps::add);

    AnthropicResponse parsed =
        client.call(
            AiTestData.task(String.class)
                .ofType(TaskType.FEEDBACK_CLASSIFICATION)
                .withTier(ModelTier.CHEAP)
                .build(),
            "haiku");

    assertThat(parsed.body()).isEqualTo("ok");
    // Exactly 2 sleeps with exponential backoff: 200 << 0 = 200, 200 << 1 = 400.
    assertThat(sleeps).containsExactly(200L, 400L);
  }

  /**
   * Kills {@code excerpt:219} NegateConditionals (the {@code body == null} guard): a {@code null}
   * body must surface as the empty string {@link AiInvalidRequestException} message rather than an
   * NPE.
   */
  @Test
  void anthropic_excerpt_nullBodyYieldsEmptyExcerpt_inErrorMessage() {
    AiProperties properties =
        new AiProperties(
            "k", "https://example.test", "haiku", "sonnet", "opus", 60, 1, null, null, null);

    ClientHttpRequestExecution exec =
        (HttpRequest req, byte[] body) -> new StubResponse(HttpStatus.BAD_REQUEST, null);
    RestClient rest =
        RestClient.builder()
            .baseUrl(properties.anthropicBaseUrl())
            .requestInterceptor((req, body, e) -> exec.execute(req, body))
            .build();
    AnthropicClient client = new AnthropicClient(rest, properties, objectMapper);
    client.setSleeper(ms -> {});

    assertThatThrownBy(
            () ->
                client.call(
                    AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build(),
                    "haiku"))
        .isInstanceOf(AiInvalidRequestException.class)
        // The empty-body excerpt should produce "Anthropic 4xx (400): " (trailing space, no NPE).
        .hasMessageMatching("Anthropic 4xx \\(400\\):\\s*");
  }

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code excerpt:222} ConditionalsBoundary (changes {@code length() <= 256} to {@code <
   *       256} so a 256-byte body wrongly tips into truncation).
   *   <li>{@code excerpt:222} EmptyObjectReturnVals (replaces non-empty return with "" — short
   *       bodies must round-trip verbatim).
   * </ul>
   */
  @Test
  void anthropic_excerpt_truncationBoundary_at256() throws Exception {
    Method m = AnthropicClient.class.getDeclaredMethod("excerpt", String.class);
    m.setAccessible(true);
    String s256 = "x".repeat(256);
    String s257 = "x".repeat(257);
    String r256 = (String) m.invoke(null, s256);
    String r257 = (String) m.invoke(null, s257);
    String rShort = (String) m.invoke(null, "tiny");
    assertThat(r256).isEqualTo(s256); // boundary case — equal length, no truncation
    assertThat(r256).doesNotEndWith("..."); // empty-string mutation would also break this
    assertThat(r257).hasSize(259).endsWith("..."); // truncated to 256 + "..."
    assertThat(rShort).isEqualTo("tiny"); // not blank — kills empty-return mutation
  }

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code lambda$buildRequestBody$1:140} BooleanTrueReturnVals (the {@code !t.isEmpty()}
   *       filter must distinguish empty vs non-empty tool lists).
   * </ul>
   *
   * Without this distinction, an empty tools list would emit a {@code "tools":[]} array — we assert
   * the {@code tools} key is absent.
   */
  @Test
  void anthropic_buildRequestBody_emptyToolsList_omitsToolsArray() throws Exception {
    AiProperties properties =
        new AiProperties("k", "https://x", "haiku", "sonnet", "opus", 60, 3, null, null, null);
    AnthropicClient client = new AnthropicClient(mock(RestClient.class), properties, objectMapper);
    // Custom AiTask with explicit non-empty Optional containing empty list (impossible via
    // AiTestData which collapses empties to Optional.empty()) — we use a hand-rolled stub.
    AiTask<String> task = explicitlyEmptyToolsListTask();
    String wire = client.buildRequestBody(task, "haiku-id");
    JsonNode parsed = objectMapper.readTree(wire);
    // The mutator replacing the predicate with `true` would still build a tools array; the
    // production predicate gates on isEmpty() and so should NOT include tools.
    assertThat(parsed.has("tools")).isFalse();
  }

  /**
   * Kills {@code lambda$buildRequestBody$2:147} NegateConditionals — the {@code description !=
   * null} guard. With description==null the tool must NOT carry a description field.
   */
  @Test
  void anthropic_buildRequestBody_toolDescriptionNull_omitsDescriptionField() throws Exception {
    AiProperties properties =
        new AiProperties("k", "https://x", "haiku", "sonnet", "opus", 60, 3, null, null, null);
    AnthropicClient client = new AnthropicClient(mock(RestClient.class), properties, objectMapper);
    com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    com.example.mealprep.ai.spi.ToolDefinition toolNoDesc =
        new com.example.mealprep.ai.spi.ToolDefinition("emit", null, schema);
    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withTier(ModelTier.CHEAP)
            .withTool(toolNoDesc)
            .build();
    JsonNode wire = objectMapper.readTree(client.buildRequestBody(task, "haiku-id"));
    JsonNode tool0 = wire.get("tools").get(0);
    assertThat(tool0.has("description")).isFalse();
    assertThat(tool0.get("name").asText()).isEqualTo("emit");
  }

  /**
   * Kills {@code lambda$post$0:109} ConditionalsBoundary — the {@code code >= 200 && code < 300}
   * success window. A 199 must NOT be success, a 300 must NOT be success.
   *
   * <p>We send valid-JSON bodies so the only signal for "treated as success" is whether the client
   * returns the parsed body vs throwing AiUnavailable. The 199/300 paths must throw.
   */
  @Test
  void anthropic_postResponse_status199and300_areNotSuccess() {
    AiProperties properties =
        new AiProperties(
            "k", "https://example.test", "haiku", "sonnet", "opus", 60, 1, null, null, null);
    AtomicInteger status = new AtomicInteger();
    // Valid JSON body — if the boundary mutation lets 199 or 300 through as "success", the parse
    // succeeds and the client returns normally. Production must throw on both.
    String validJsonBody = "{\"content\":[{\"type\":\"text\",\"text\":\"escape\"}]}";
    ClientHttpRequestExecution exec =
        (HttpRequest req, byte[] body) ->
            new StubResponse(HttpStatusCode.valueOf(status.get()), validJsonBody);
    RestClient rest =
        RestClient.builder()
            .baseUrl(properties.anthropicBaseUrl())
            .requestInterceptor((req, body, e) -> exec.execute(req, body))
            .build();
    AnthropicClient client = new AnthropicClient(rest, properties, objectMapper);
    client.setSleeper(ms -> {});

    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();

    // 199 should fall into the 5xx-style branch (treated as unavailable, not success).
    status.set(199);
    assertThatThrownBy(() -> client.call(task, "haiku"))
        .isInstanceOf(AiUnavailableException.class)
        // Must NOT be a "response was not JSON" message — that would mean parse failed instead.
        .satisfies(
            ex -> assertThat(ex.getMessage()).doesNotContain("Anthropic response was not JSON"));
    // 300 — same: must throw, not succeed.
    status.set(300);
    assertThatThrownBy(() -> client.call(task, "haiku"))
        .isInstanceOf(AiUnavailableException.class)
        .satisfies(
            ex -> assertThat(ex.getMessage()).doesNotContain("Anthropic response was not JSON"));
  }

  /**
   * Kills {@code renderUserMessage:166,173} EmptyObjectReturnVals — two distinct branches: (a)
   * empty variables map falls back to {@code task.prompt().name()}; (b) non-empty vars without a
   * "prompt" string entry serialise the whole map as JSON.
   */
  @Test
  void anthropic_buildRequestBody_emptyVars_usesPromptName_andMapVars_serialisesJson()
      throws Exception {
    AiProperties properties =
        new AiProperties("k", "https://x", "haiku", "sonnet", "opus", 60, 3, null, null, null);
    AnthropicClient client = new AnthropicClient(mock(RestClient.class), properties, objectMapper);

    // (a) empty vars — content = prompt.name()
    AiTask<String> emptyVars = emptyVariablesTask();
    JsonNode wire = objectMapper.readTree(client.buildRequestBody(emptyVars, "haiku-id"));
    assertThat(wire.get("messages").get(0).get("content").asText()).isEqualTo("test/echo");

    // (b) non-empty vars without "prompt" entry — JSON serialisation of the whole map
    AiTask<String> jsonVars =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    // AiTestData inserts a default "prompt" — wrap a task with a non-"prompt" var only.
    AiTask<String> custom = nonPromptVariableTask("foo", "bar");
    JsonNode wire2 = objectMapper.readTree(client.buildRequestBody(custom, "haiku-id"));
    String content = wire2.get("messages").get(0).get("content").asText();
    // The "prompt" key has been swapped out for "foo"; should JSON-serialise the map.
    assertThat(content).contains("\"foo\"").contains("\"bar\"").startsWith("{").endsWith("}");
  }

  /**
   * Kills {@code parse:197,204} — the empty/non-array content path that returns an empty
   * AnthropicResponse, and the {@code "tool_use"} type guard.
   */
  @Test
  void anthropic_parse_emptyContent_returnsResponseWithEmptyBody() {
    AiProperties properties =
        new AiProperties("k", "https://x", "haiku", "sonnet", "opus", 60, 3, null, null, null);
    AnthropicClient client = new AnthropicClient(mock(RestClient.class), properties, objectMapper);

    AnthropicResponse parsed =
        client.parse(
            "{\"model\":\"haiku\",\"content\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}");
    assertThat(parsed.body()).isEqualTo("");
    assertThat(parsed.requestTokens()).isEqualTo(3);
    assertThat(parsed.responseTokens()).isEqualTo(1);
    assertThat(parsed.modelId()).isEqualTo("haiku");

    // Missing content key (not an array) — same behaviour, returns empty body but ALSO carries
    // a null modelId / null usage to exercise the optionalInt non-isInt fallback.
    AnthropicResponse parsed2 = client.parse("{\"foo\":\"bar\"}");
    assertThat(parsed2.body()).isEqualTo("");
    assertThat(parsed2.requestTokens()).isNull();
    assertThat(parsed2.modelId()).isNull();
  }

  /**
   * Kills {@code parse:204} NegateConditionals on {@code "tool_use".equals(type)} branch — a
   * tool_use block whose input is a scalar (neither object nor array) should fall through and NOT
   * short-circuit.
   */
  @Test
  void anthropic_parse_toolUseScalarInput_fallsThroughToText() {
    AiProperties properties =
        new AiProperties("k", "https://x", "haiku", "sonnet", "opus", 60, 3, null, null, null);
    AnthropicClient client = new AnthropicClient(mock(RestClient.class), properties, objectMapper);

    // tool_use block with string input — should be ignored, text block wins.
    AnthropicResponse parsed =
        client.parse(
            "{\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"x\",\"input\":\"just-a-string\"},"
                + "{\"type\":\"text\",\"text\":\"the-text\"}]}");
    assertThat(parsed.body()).isEqualTo("the-text");
  }

  /**
   * Kills {@code readBody:229} EmptyObjectReturnVals — IO-on-read fallback returns the descriptive
   * {@code "<unreadable: ...>"} string, not empty.
   */
  @Test
  void anthropic_readBody_ioExceptionFallback_returnsDescriptiveMessage() throws Exception {
    // We reach readBody via the post() exchange — script an IOException on getBody().
    AiProperties properties =
        new AiProperties(
            "k", "https://example.test", "haiku", "sonnet", "opus", 60, 1, null, null, null);
    ClientHttpRequestExecution exec =
        (HttpRequest req, byte[] body) ->
            new ThrowingStubResponse(HttpStatus.INTERNAL_SERVER_ERROR);
    RestClient rest =
        RestClient.builder()
            .baseUrl(properties.anthropicBaseUrl())
            .requestInterceptor((req, body, e) -> exec.execute(req, body))
            .build();
    AnthropicClient client = new AnthropicClient(rest, properties, objectMapper);
    client.setSleeper(ms -> {});

    assertThatThrownBy(
            () ->
                client.call(
                    AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build(),
                    "haiku"))
        .isInstanceOf(AiUnavailableException.class)
        // The readBody fallback message lands on the cause's message ("Anthropic 5xx (500):
        // <unreadable: ...>"),
        // not on the wrapped retries-exhausted top-level message. Drill into the cause.
        .satisfies(
            ex -> {
              Throwable cause = ex.getCause();
              assertThat(cause).isNotNull();
              assertThat(cause.getMessage()).contains("unreadable");
            });
  }

  /**
   * Kills {@code sleepQuietly:237} VoidMethodCall ({@code Thread.currentThread().interrupt()}).
   *
   * <p>When the sleeper throws InterruptedException the dispatcher must (a) re-set the interrupt
   * flag on the thread and (b) surface an AiUnavailableException. Both signals are verified.
   */
  @Test
  void anthropic_sleepQuietly_interruptIsRestoredAndUnavailableSurfaces() {
    AiProperties properties =
        new AiProperties(
            "k", "https://example.test", "haiku", "sonnet", "opus", 60, 3, null, null, null);
    ClientHttpRequestExecution exec =
        (HttpRequest req, byte[] body) -> new StubResponse(HttpStatus.INTERNAL_SERVER_ERROR, "x");
    RestClient rest =
        RestClient.builder()
            .baseUrl(properties.anthropicBaseUrl())
            .requestInterceptor((req, body, e) -> exec.execute(req, body))
            .build();
    AnthropicClient client = new AnthropicClient(rest, properties, objectMapper);
    client.setSleeper(
        ms -> {
          throw new InterruptedException("simulated");
        });

    // Clear any prior interrupt and ensure a fresh state.
    Thread.interrupted();
    try {
      assertThatThrownBy(
              () ->
                  client.call(
                      AiTestData.task(String.class)
                          .ofType(TaskType.FEEDBACK_CLASSIFICATION)
                          .build(),
                      "haiku"))
          .isInstanceOf(AiUnavailableException.class)
          .hasMessageContaining("Interrupted");
      // Interrupt flag must have been restored by sleepQuietly's catch block.
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  // ============================================================================================
  // OpenAiEmbeddingClient survivors
  // ============================================================================================

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code embed:97} Math (shift-left → shift-right; subtraction → addition).
   *   <li>{@code embed:104} VoidMethodCall (removed sleepQuietly).
   *   <li>{@code sleepQuietly:145} VoidMethodCall (removed sleeper.sleep).
   * </ul>
   *
   * Same shape as the Anthropic counterpart — confirm the exact backoff series 200, 400.
   */
  @Test
  void openAiEmbedding_retries_backoffSeriesIs200_400_ms() {
    AiProperties properties =
        new AiProperties(
            "k",
            "https://example.test",
            "haiku",
            "sonnet",
            "opus",
            60,
            3,
            "openai-key",
            null,
            null);
    EmbeddingService svc = mock(EmbeddingService.class);
    OpenAIClient openAi = mock(OpenAIClient.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<OpenAIClient> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(openAi);
    when(openAi.embeddings()).thenReturn(svc);

    CreateEmbeddingResponse resp = mock(CreateEmbeddingResponse.class);
    Embedding embedding = mock(Embedding.class);
    when(resp.data()).thenReturn(List.of(embedding));
    when(embedding.embedding()).thenReturn(List.of(0.1));
    when(resp.usage()).thenReturn(null);
    when(svc.create(any(EmbeddingCreateParams.class)))
        .thenThrow(mock(InternalServerException.class))
        .thenThrow(mock(InternalServerException.class))
        .thenReturn(resp);

    OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(provider, properties);
    List<Long> sleeps = new ArrayList<>();
    client.setSleeper(sleeps::add);

    OpenAiEmbeddingClient.EmbeddingResult result = client.embed("x", "text-embedding-3-small");
    assertThat(result.vector()).hasSize(1);
    assertThat(sleeps).containsExactly(200L, 400L);
  }

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code safeMessage:138} ConditionalsBoundary, NegateConditionals, EmptyObjectReturnVals.
   * </ul>
   *
   * The message-length boundary is 256: equal-length is NOT truncated, 257 IS truncated to {@code
   * substring(0,256) + "..."}.
   */
  @Test
  void openAiEmbedding_safeMessage_boundaryAt256() throws Exception {
    Method m = OpenAiEmbeddingClient.class.getDeclaredMethod("safeMessage", OpenAIException.class);
    m.setAccessible(true);
    OpenAIException short256 = mock(OpenAIException.class);
    when(short256.getMessage()).thenReturn("x".repeat(256));
    OpenAIException long257 = mock(OpenAIException.class);
    when(long257.getMessage()).thenReturn("x".repeat(257));
    OpenAIException nullMsg = mock(OpenAIException.class);
    when(nullMsg.getMessage()).thenReturn(null);

    assertThat((String) m.invoke(null, short256)).hasSize(256).doesNotEndWith("...");
    assertThat((String) m.invoke(null, long257)).hasSize(259).endsWith("...");
    // Null message → class simple name, NOT empty.
    String fallback = (String) m.invoke(null, nullMsg);
    assertThat(fallback).isNotBlank().isNotEqualTo("");
  }

  // ============================================================================================
  // AiServiceImpl survivors
  // ============================================================================================

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code cacheKey:328,329} VoidMethodCall (removing the {@code digest.update(text)} or
   *       {@code digest.update(model)} silently lets the hash collapse across inputs/models).
   *   <li>{@code cacheKey:331} Math (multiplication → division on StringBuilder pre-size — value
   *       still produces same bytes but a mutator-changed length might break the format).
   * </ul>
   *
   * Verify distinctness across inputs AND models, and stability.
   */
  @Test
  void aiServiceImpl_cacheKey_isDistinctPerInputAndPerModel_andStable() throws Exception {
    Method m = AiServiceImpl.class.getDeclaredMethod("cacheKey", String.class, String.class);
    m.setAccessible(true);
    String a = (String) m.invoke(null, "alpha", "text-embedding-3-small");
    String b = (String) m.invoke(null, "beta", "text-embedding-3-small");
    String c = (String) m.invoke(null, "alpha", "text-embedding-3-large");
    String aAgain = (String) m.invoke(null, "alpha", "text-embedding-3-small");
    assertThat(a).hasSize(64); // 32-byte SHA-256 in hex
    assertThat(a).isEqualTo(aAgain);
    assertThat(a).isNotEqualTo(b); // distinct input
    assertThat(a).isNotEqualTo(c); // distinct model
    // The byte-0 separator means concatenating the inputs without it produces a different hash:
    // sha256("alpha\0small") != sha256("alphasmall"). Verify by computing the un-separated hash.
    String concatHash =
        sha256Hex(("alpha" + "text-embedding-3-small").getBytes(StandardCharsets.UTF_8));
    assertThat(a).isNotEqualTo(concatHash);
  }

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code elapsedMs:342} Math (long subtraction → addition).
   *   <li>{@code elapsedMs:343} Math (division → multiplication).
   *   <li>{@code elapsedMs:344} PrimitiveReturns (return 0).
   * </ul>
   */
  @Test
  void aiServiceImpl_elapsedMs_returnsPositiveMillisecondCount() throws Exception {
    Method m = AiServiceImpl.class.getDeclaredMethod("elapsedMs", long.class);
    m.setAccessible(true);
    // Use a startNanos in the past — the elapsed should be > 0 ms.
    long start = System.nanoTime() - 5_000_000L; // 5ms ago
    int result = (int) m.invoke(null, start);
    assertThat(result).isGreaterThanOrEqualTo(5); // 5ms or so
    assertThat(result).isLessThan(10_000); // sanity
    // "Now" startNanos should produce ~0 — proves the value isn't a stuck constant.
    int near = (int) m.invoke(null, System.nanoTime());
    assertThat(near).isLessThan(100);
  }

  /**
   * Kills {@code execute:112} NegateConditionals — the {@code response.modelId() != null ?
   * response.modelId() : modelId} ternary. The configured fallback is haiku-id (CHEAP tier); the
   * response carries a SONNET-style model id (MID tier). Production should price as MID; the
   * negated mutation would price as CHEAP — distinct cost.
   */
  @Test
  void aiServiceImpl_execute_usesResponseModelIdWhenPresent_notFallback() {
    AnthropicClient anthropic = mock(AnthropicClient.class);
    AiCallRecorder recorder = mock(AiCallRecorder.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    CostBudgetGuard guard = mock(CostBudgetGuard.class);
    OpenAiEmbeddingClient embedding = mock(OpenAiEmbeddingClient.class);
    AiProperties props =
        new AiProperties("k", null, "haiku-id", "sonnet-id", "opus-id", 60, 3, null, null, null);
    CostCalculator calc = new CostCalculator();
    Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);

    UUID callId = UUID.randomUUID();
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    // Sonnet model id on response — should be used for pricing (MID tier), not haiku-id.
    when(anthropic.call(any(), eq("haiku-id")))
        .thenReturn(new AnthropicResponse("ok", 1, 1, "claude-sonnet-4-6"));

    AiServiceImpl svc =
        new AiServiceImpl(
            anthropic, embedding, recorder, publisher, props, objectMapper, clock, guard, calc);
    String result = svc.execute(task);
    assertThat(result).isEqualTo("ok");

    // Cost = 1 in * 237p/MTok + 1 out * 1185p/MTok = 237 + 1185 = 1422 — matches Sonnet (MID).
    // If the conditional is negated, fallback "haiku-id" would price as CHEAP = 79 + 395 = 474.
    verify(recorder).recordSuccess(eq(callId), eq(1), eq(1), anyInt(), eq(237L + 1185L));
  }

  // ============================================================================================
  // CostBudgetGuard survivors
  // ============================================================================================

  /**
   * Kills {@code checkOrThrow:76} ConditionalsBoundary — the {@code spent+estimate < limit} guard.
   * Switching to {@code <=} would let an exactly-at-cap case slip through (the cap is exclusive).
   * We arrange spent+estimate==limit and verify the exception fires.
   */
  @Test
  void costBudgetGuard_exactlyAtLimit_isExclusive_throws() {
    AiCallLogRepository repo = mock(AiCallLogRepository.class);
    CostCalculator calc = new CostCalculator();
    AiProperties props =
        new AiProperties(
            "k",
            null,
            "haiku-id",
            "sonnet-id",
            "opus-id",
            60,
            3,
            null,
            null,
            new AiProperties.Budget(true, 50L, 24));
    Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);
    CostBudgetGuard guard = new CostBudgetGuard(repo, calc, props, clock);

    UUID userId = UUID.randomUUID();
    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withTier(ModelTier.CHEAP)
            .withUserId(userId)
            .build();
    // CHEAP estimate = 4_000 * 79 + 2_000 * 395 = 1_106_000 micropence. Set spent so total is
    // exactly the 50p (50_000_000 micropence) limit.
    long estimate = 1_106_000L;
    long limit = 50_000_000L;
    when(repo.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(limit - estimate);
    when(repo.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(succeededRow(clock.instant().minus(Duration.ofHours(1)))));

    assertThatThrownBy(() -> guard.checkOrThrow(task))
        .isInstanceOf(com.example.mealprep.ai.exception.AiCostBudgetExceededException.class);
  }

  /**
   * Kills {@code clampToOneSecond:146} ConditionalsBoundary — the {@code seconds < 1} branch on
   * sub-second durations. Reflectively invoke clampToOneSecond and check that 500ms rounds UP to 1s
   * (the nanos fractional part path).
   */
  @Test
  void costBudgetGuard_clampToOneSecond_subSecondDurationRoundsUp() throws Exception {
    Method m = CostBudgetGuard.class.getDeclaredMethod("clampToOneSecond", Duration.class);
    m.setAccessible(true);
    // 500ms — toSeconds() = 0; the fractional remainder triggers +1; final clamp to >=1.
    Duration result = (Duration) m.invoke(null, Duration.ofMillis(500));
    assertThat(result).isEqualTo(Duration.ofSeconds(1));
    // 1500ms — toSeconds() = 1; fractional nanos → seconds becomes 2.
    Duration result1500 = (Duration) m.invoke(null, Duration.ofMillis(1500));
    assertThat(result1500).isEqualTo(Duration.ofSeconds(2));
    // Zero → 1s.
    Duration zero = (Duration) m.invoke(null, Duration.ZERO);
    assertThat(zero).isEqualTo(Duration.ofSeconds(1));
    // Negative → 1s.
    Duration neg = (Duration) m.invoke(null, Duration.ofSeconds(-30));
    assertThat(neg).isEqualTo(Duration.ofSeconds(1));
    // Null → 1s.
    Duration nullCase = (Duration) m.invoke(null, (Duration) null);
    assertThat(nullCase).isEqualTo(Duration.ofSeconds(1));
  }

  /**
   * Kills {@code retryAfterFor:131} NullReturnVals on the {@code rows.get(0).getCreatedAt()} null
   * branch — when the row has a null createdAt, the retry must defer to the window length.
   */
  @Test
  void costBudgetGuard_retryAfterFor_rowWithNullCreatedAt_defersToWindow() {
    AiCallLogRepository repo = mock(AiCallLogRepository.class);
    CostCalculator calc = new CostCalculator();
    AiProperties props =
        new AiProperties(
            "k",
            null,
            "haiku-id",
            "sonnet-id",
            "opus-id",
            60,
            3,
            null,
            null,
            new AiProperties.Budget(true, 50L, 24));
    Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);
    CostBudgetGuard guard = new CostBudgetGuard(repo, calc, props, clock);

    UUID userId = UUID.randomUUID();
    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withTier(ModelTier.HIGH)
            .withUserId(userId)
            .build();
    when(repo.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(100_000_000L);
    // Single row with null createdAt — defensive path returns window length clamped.
    AiCallLog rowWithNullCreatedAt =
        new AiCallLog(
            UUID.randomUUID(),
            userId,
            null,
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            "haiku-id",
            "test/x",
            1,
            CallStatus.SUCCEEDED);
    when(repo.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(rowWithNullCreatedAt));

    assertThatThrownBy(() -> guard.checkOrThrow(task))
        .isInstanceOf(com.example.mealprep.ai.exception.AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              Duration retry =
                  ((com.example.mealprep.ai.exception.AiCostBudgetExceededException) ex)
                      .retryAfter();
              assertThat(retry).isEqualTo(Duration.ofHours(24));
            });
  }

  // ============================================================================================
  // PromptTemplateLoader survivors
  // ============================================================================================

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code normalisePattern:146} EmptyObjectReturnVals (a {@code .md}-suffixed pattern must
   *       round-trip verbatim).
   *   <li>{@code normalisePattern:148} NegateConditionals (paths without {@code /} or {@code .md}
   *       must have {@code /*.md} appended).
   * </ul>
   */
  @Test
  void promptTemplateLoader_normalisePattern_dotMdPassesThrough_andSlashIsAppended()
      throws Exception {
    Method m =
        com.example.mealprep.ai.domain.service.internal.PromptTemplateLoader.class
            .getDeclaredMethod("normalisePattern", String.class);
    m.setAccessible(true);
    // .md suffix passes through verbatim
    assertThat((String) m.invoke(null, "classpath:prompts/foo.md"))
        .isEqualTo("classpath:prompts/foo.md");
    // Trailing slash → append *.md only
    assertThat((String) m.invoke(null, "classpath:prompts/")).isEqualTo("classpath:prompts/*.md");
    // No trailing slash → add slash + *.md
    assertThat((String) m.invoke(null, "classpath:prompts")).isEqualTo("classpath:prompts/*.md");
    // Leading/trailing whitespace trimmed
    assertThat((String) m.invoke(null, "  classpath:prompts  "))
        .isEqualTo("classpath:prompts/*.md");
  }

  /**
   * Kills {@code sha256Hex:274} Math — multiplication → division on StringBuilder pre-sizing.
   * Verify the resulting hex string is 64 chars and matches a known SHA-256.
   */
  @Test
  void promptTemplateLoader_sha256Hex_isExactly64HexChars_andMatchesKnownVector() throws Exception {
    Method m =
        com.example.mealprep.ai.domain.service.internal.PromptTemplateLoader.class
            .getDeclaredMethod("sha256Hex", byte[].class);
    m.setAccessible(true);
    // SHA-256("") = e3b0c442…b855
    String empty = (String) m.invoke(null, (Object) new byte[0]);
    assertThat(empty)
        .hasSize(64)
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    // SHA-256("abc") = ba7816bf…f20015ad
    String abc = (String) m.invoke(null, (Object) "abc".getBytes(StandardCharsets.UTF_8));
    assertThat(abc)
        .hasSize(64)
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  // ============================================================================================
  // ToolUseInvoker survivors
  // ============================================================================================

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code invoke:71} NegateConditionals — the {@code !handlers.containsKey(toolName)} log
   *       guard distinguishes registered vs unregistered tools. We register a sentinel that returns
   *       a known value for the registered case to prove the fallback isn't always invoked.
   *   <li>{@code lambda$new$0:40} NegateConditionals — the {@code ctx == null} branch in the no-op
   *       handler's log statement.
   * </ul>
   */
  @Test
  void toolUseInvoker_registeredTool_invokesItsHandler_notNoOp() {
    com.example.mealprep.ai.domain.service.internal.ToolUseInvoker invoker =
        new com.example.mealprep.ai.domain.service.internal.ToolUseInvoker();
    JsonNode expected = objectMapper.createObjectNode().put("ok", true);
    invoker.register("real_tool", (input, ctx) -> expected);
    JsonNode actual = invoker.invoke("real_tool", objectMapper.createObjectNode(), Map.of());
    assertThat(actual).isEqualTo(expected);
    // No-op path: passing null context exercises the lambda$new$0 null branch.
    assertThat(invoker.invoke("never_registered_tool", objectMapper.createObjectNode(), null))
        .isNull();
  }

  /**
   * Kills {@code AiServiceImpl.embed:196} VoidMethodCall — the AiInvalidResponseException catch
   * branch must run finalizeEmbeddingFailure. The existing test only covers AiUnavailable, so this
   * specifically exercises the InvalidResponse path.
   */
  @Test
  void aiServiceImpl_embed_invalidResponse_finalizesAndPublishesFailedEvent() {
    AnthropicClient anthropic = mock(AnthropicClient.class);
    AiCallRecorder recorder = mock(AiCallRecorder.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    CostBudgetGuard guard = mock(CostBudgetGuard.class);
    OpenAiEmbeddingClient embedding = mock(OpenAiEmbeddingClient.class);
    AiProperties props =
        new AiProperties("k", null, "haiku-id", "sonnet-id", "opus-id", 60, 3, null, null, null);
    CostCalculator calc = new CostCalculator();
    Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);

    UUID callId = UUID.randomUUID();
    when(recorder.recordEmbeddingPending(any(), any(), any(), any(), any())).thenReturn(callId);
    when(embedding.embed(any(), any()))
        .thenThrow(new com.example.mealprep.ai.exception.AiInvalidResponseException("bad shape"));

    AiServiceImpl svc =
        new AiServiceImpl(
            anthropic, embedding, recorder, publisher, props, objectMapper, clock, guard, calc);
    assertThatThrownBy(() -> svc.embed(stubEmbedTask("x")))
        .isInstanceOf(com.example.mealprep.ai.exception.AiInvalidResponseException.class);
    verify(recorder)
        .recordFailure(
            eq(callId),
            eq(com.example.mealprep.ai.domain.entity.CallErrorKind.INVALID_RESPONSE),
            anyInt());
    verify(publisher).publishEvent(any(com.example.mealprep.ai.event.AiCallFailedEvent.class));
  }

  // ============================================================================================
  // OpenAiEmbeddingClient remaining survivors
  // ============================================================================================

  /**
   * Kills {@code OpenAiEmbeddingClient.sleepQuietly:145} VoidMethodCall — Thread.interrupt is
   * restored on InterruptedException, and an AiUnavailableException is surfaced.
   */
  @Test
  void openAiEmbedding_sleepQuietly_interruptRestored_andUnavailableSurfaces() {
    AiProperties properties =
        new AiProperties(
            "k", "https://x", "haiku", "sonnet", "opus", 60, 3, "openai-key", null, null);
    EmbeddingService svc = mock(EmbeddingService.class);
    OpenAIClient openAi = mock(OpenAIClient.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<OpenAIClient> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(openAi);
    when(openAi.embeddings()).thenReturn(svc);
    // Always throw transient — triggers retry → sleeper → interrupt restoration.
    when(svc.create(any(EmbeddingCreateParams.class)))
        .thenThrow(mock(InternalServerException.class));

    OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(provider, properties);
    client.setSleeper(
        ms -> {
          throw new InterruptedException("simulated");
        });

    Thread.interrupted(); // clear any prior interrupt
    try {
      assertThatThrownBy(() -> client.embed("x", "text-embedding-3-small"))
          .isInstanceOf(AiUnavailableException.class)
          .hasMessageContaining("Interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  // ============================================================================================
  // ToolUseInvoker
  // ============================================================================================

  /**
   * Kills {@code ToolUseInvoker.invoke:71} NegateConditionals — the registered-vs-unregistered log
   * branch. We register a sentinel for one name and confirm: (a) the registered handler is invoked
   * (not the no-op); (b) an unregistered name returns the no-op's null without throwing. The
   * mutation flipping the conditional would skip the log call but more importantly would change the
   * lookup semantics — we ensure both registered and unregistered paths return their proper values.
   */
  @Test
  void toolUseInvoker_invoke_registeredTool_invokesItsHandler_andUnregisteredFallsToNoop() {
    com.example.mealprep.ai.domain.service.internal.ToolUseInvoker invoker =
        new com.example.mealprep.ai.domain.service.internal.ToolUseInvoker();
    JsonNode registeredOutput = objectMapper.createObjectNode().put("custom", "value");
    invoker.register("registered_tool", (input, ctx) -> registeredOutput);

    // The registered handler returns its specific node — not null.
    JsonNode result = invoker.invoke("registered_tool", objectMapper.createObjectNode(), Map.of());
    assertThat(result).isSameAs(registeredOutput);
    assertThat(result).isNotNull();

    // An unregistered tool name falls back to the no-op (returns null).
    JsonNode unregisteredResult =
        invoker.invoke("never_registered", objectMapper.createObjectNode(), Map.of());
    assertThat(unregisteredResult).isNull();
  }

  // ============================================================================================
  // AiCostTrackingServiceImpl survivors
  // ============================================================================================

  /**
   * Kills {@code pencesSpentByUserPerTaskType:52} EmptyObjectReturnVals — a non-empty repository
   * row list must NOT collapse to Collections.emptyMap(). The existing test asserts size==2 but the
   * mutator may still survive if it returns emptyMap-empty before the loop runs. We assert a
   * specific entry value to pin behaviour.
   */
  @Test
  void aiCostTrackingImpl_perTaskType_returnsAggregatedMap_notEmptyMap() throws Exception {
    AiCallLogRepository repo = mock(AiCallLogRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);
    Class<?> impl =
        Class.forName("com.example.mealprep.ai.domain.service.internal.AiCostTrackingServiceImpl");
    var ctor = impl.getDeclaredConstructor(AiCallLogRepository.class, Clock.class);
    ctor.setAccessible(true);
    com.example.mealprep.ai.domain.service.AiCostTrackingService svc =
        (com.example.mealprep.ai.domain.service.AiCostTrackingService)
            ctor.newInstance(repo, clock);

    UUID userId = UUID.randomUUID();
    when(repo.sumCostMicroPenceForUserSinceByTaskType(eq(userId), any()))
        .thenReturn(List.<Object[]>of(new Object[] {TaskType.FEEDBACK_CLASSIFICATION, 5_000_000L}));

    Map<TaskType, java.math.BigDecimal> map =
        svc.pencesSpentByUserPerTaskType(userId, Duration.ofHours(24));

    assertThat(map).isNotEmpty().hasSize(1);
    assertThat(map.get(TaskType.FEEDBACK_CLASSIFICATION))
        .isEqualByComparingTo(new java.math.BigDecimal("5.00"));
  }

  // ============================================================================================
  // Helpers
  // ============================================================================================

  private static com.example.mealprep.ai.spi.EmbeddingTask stubEmbedTask(String text) {
    return new com.example.mealprep.ai.spi.EmbeddingTask() {
      @Override
      public com.example.mealprep.ai.spi.EmbeddingTaskType type() {
        return com.example.mealprep.ai.spi.EmbeddingTaskType.PREFERENCE_TASTE_VECTOR;
      }

      @Override
      public String inputText() {
        return text;
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

  private static String sha256Hex(byte[] bytes) throws Exception {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    byte[] hashed = md.digest(bytes);
    StringBuilder sb = new StringBuilder(hashed.length * 2);
    for (byte b : hashed) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static AiCallLog succeededRow(Instant createdAt) {
    AiCallLog row =
        new AiCallLog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            "haiku-id",
            "test/echo",
            1,
            CallStatus.SUCCEEDED);
    try {
      java.lang.reflect.Field f = AiCallLog.class.getDeclaredField("createdAt");
      f.setAccessible(true);
      f.set(row, createdAt);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
    return row;
  }

  private AiTask<String> explicitlyEmptyToolsListTask() {
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
      public com.example.mealprep.ai.spi.PromptRef prompt() {
        return new com.example.mealprep.ai.spi.PromptRef("test/echo", 1);
      }

      @Override
      public Class<String> outputType() {
        return String.class;
      }

      @Override
      public Map<String, Object> variables() {
        return Map.of("prompt", "hi");
      }

      @Override
      public Optional<List<com.example.mealprep.ai.spi.ToolDefinition>> tools() {
        return Optional.of(List.of()); // explicit empty
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

  private AiTask<String> emptyVariablesTask() {
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
      public com.example.mealprep.ai.spi.PromptRef prompt() {
        return new com.example.mealprep.ai.spi.PromptRef("test/echo", 1);
      }

      @Override
      public Class<String> outputType() {
        return String.class;
      }

      @Override
      public Map<String, Object> variables() {
        return Map.of();
      }

      @Override
      public Optional<List<com.example.mealprep.ai.spi.ToolDefinition>> tools() {
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

  private AiTask<String> nonPromptVariableTask(String key, Object value) {
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
      public com.example.mealprep.ai.spi.PromptRef prompt() {
        return new com.example.mealprep.ai.spi.PromptRef("test/echo", 1);
      }

      @Override
      public Class<String> outputType() {
        return String.class;
      }

      @Override
      public Map<String, Object> variables() {
        return Map.of(key, value);
      }

      @Override
      public Optional<List<com.example.mealprep.ai.spi.ToolDefinition>> tools() {
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

  private record RoundTrip(HttpStatus status, String body, java.io.IOException error) {
    static RoundTrip ok(String body) {
      return new RoundTrip(HttpStatus.OK, body, null);
    }

    static RoundTrip status(HttpStatus status, String body) {
      return new RoundTrip(status, body, null);
    }
  }

  private static final class StubResponse implements ClientHttpResponse {
    private final HttpStatusCode status;
    private final byte[] body;
    private final HttpHeaders headers = new HttpHeaders();

    StubResponse(HttpStatusCode status, String body) {
      this.status = status;
      this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public HttpStatusCode getStatusCode() {
      return status;
    }

    @Override
    public String getStatusText() {
      return status instanceof HttpStatus h ? h.getReasonPhrase() : String.valueOf(status.value());
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
    public void close() {}
  }

  /** ClientHttpResponse whose body stream raises IO on read — exercises the readBody catch. */
  private static final class ThrowingStubResponse implements ClientHttpResponse {
    private final HttpStatus status;
    private final HttpHeaders headers = new HttpHeaders();

    ThrowingStubResponse(HttpStatus status) {
      this.status = status;
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
      return new java.io.InputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("stream broken");
        }
      };
    }

    @Override
    public void close() {}
  }
}
