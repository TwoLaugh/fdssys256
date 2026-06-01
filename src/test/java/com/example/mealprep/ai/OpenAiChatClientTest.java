package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.internal.ChatResponse;
import com.example.mealprep.ai.domain.service.internal.OpenAiChatClient;
import com.example.mealprep.ai.domain.service.internal.StructuredOutputParser;
import com.example.mealprep.ai.exception.AiCircuitOpenException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiRateLimitException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionMessage;
import com.openai.models.CompletionUsage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.CompletionService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link OpenAiChatClient}. The {@link OpenAIClient} surface is mocked at the {@code
 * chat().completions()} seam — we assert structured-output request shape, response/usage parsing,
 * the {@link com.example.mealprep.ai.domain.service.internal.RetryPolicy}-classified retry, and the
 * circuit-breaker short-circuit, all without a real HTTP client or any network call.
 */
class OpenAiChatClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final StructuredOutputParser parser = new StructuredOutputParser(objectMapper);
  private final AiProperties properties =
      new AiProperties("k", null, "haiku", "sonnet", "opus", 60, 3, "openai-key", null, null);

  private final OpenAIClient openAiClient = mock(OpenAIClient.class);
  private final ChatService chatService = mock(ChatService.class);
  private final CompletionService completionService = mock(CompletionService.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<OpenAIClient> clientProvider = mock(ObjectProvider.class);

  private OpenAiChatClient client() {
    return client(CircuitBreakerRegistry.ofDefaults());
  }

  private OpenAiChatClient client(CircuitBreakerRegistry registry) {
    when(clientProvider.getIfAvailable()).thenReturn(openAiClient);
    when(openAiClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    OpenAiChatClient c =
        new OpenAiChatClient(clientProvider, properties, objectMapper, parser, registry);
    c.setSleeper(ms -> {}); // no real sleep in tests
    c.setJitterRandom(new java.util.Random(42)); // deterministic backoff
    return c;
  }

  private AiTask<String> freeTextTask() {
    return AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
  }

  private AiTask<String> structuredTask() {
    return AiTestData.task(String.class)
        .ofType(TaskType.FEEDBACK_CLASSIFICATION)
        .withTier(ModelTier.CHEAP)
        .withTool(AiTestData.simpleTool("answer", objectMapper))
        .build();
  }

  /**
   * Build a real SDK {@link ChatCompletion} whose assistant {@code content} is a plain JSON
   * <b>string</b> (the free-text / stringified shape), with the given usage. Real SDK objects (not
   * a {@code mock(ChatCompletion.class)}) so the client's raw-JSON extraction path is genuinely
   * exercised end to end.
   */
  private ChatCompletion completionWith(String content, Long promptTokens, Long completionTokens) {
    JsonValue contentValue = content == null ? null : JsonValue.from(content);
    return completion(contentValue, JsonValue.from(null), promptTokens, completionTokens);
  }

  /**
   * Build a real SDK {@link ChatCompletion} whose assistant {@code content} is a JSON <b>object</b>
   * — the exact strict structured-output shape a live {@code gpt-*} call returns (e.g. {@code
   * {"sentiment":"positive","confidence":0.99}}). This is the shape that makes the SDK's strict
   * typed {@code choice.message()} throw {@link OpenAIInvalidDataException}; the regression that
   * {@code OpenAiChatClient} now reads via the raw/untyped path.
   */
  private ChatCompletion completionWithObjectContent(
      ObjectNode content, Long promptTokens, Long completionTokens) {
    return completion(
        JsonValue.fromJsonNode(content), JsonValue.from(null), promptTokens, completionTokens);
  }

  private ChatCompletion completion(
      JsonValue content, JsonValue refusal, Long promptTokens, Long completionTokens) {
    ChatCompletionMessage.Builder messageBuilder =
        ChatCompletionMessage.builder().role(JsonValue.from("assistant")).refusal(refusal);
    if (content != null) {
      // content() is typed JsonField<String>, but JsonValue IS a JsonField, so we can plant the
      // raw object/string value the wire would carry — mirroring the real response shape.
      messageBuilder.content(content);
    } else {
      messageBuilder.content(Optional.empty());
    }
    ChatCompletion.Choice choice =
        ChatCompletion.Choice.builder()
            .index(0)
            .finishReason(ChatCompletion.Choice.FinishReason.STOP)
            .logprobs(Optional.empty())
            .message(messageBuilder.build())
            .build();
    ChatCompletion.Builder builder =
        ChatCompletion.builder()
            .id("chatcmpl-test")
            .created(1L)
            .model("gpt-test")
            .choices(List.of(choice));
    if (promptTokens != null) {
      builder.usage(
          CompletionUsage.builder()
              .promptTokens(promptTokens)
              .completionTokens(completionTokens)
              .totalTokens(promptTokens + completionTokens)
              .build());
    }
    return builder.build();
  }

  @Test
  void chat_happyPath_freeText_returnsBodyAndUsage() {
    ChatCompletion completion = completionWith("{\"x\":1}", 12L, 4L);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);

    ChatResponse response = client().chat(freeTextTask(), "gpt-cheap");

    assertThat(response.body()).isEqualTo("{\"x\":1}");
    assertThat(response.requestTokens()).isEqualTo(12);
    assertThat(response.responseTokens()).isEqualTo(4);
    assertThat(response.modelId()).isEqualTo("gpt-test");
  }

  @Test
  void chat_structuredOutput_sendsJsonSchemaResponseFormat_andValidates() {
    ChatCompletion completion = completionWith("{\"answer\":\"42\"}", 1L, 1L);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);

    ChatResponse response = client().chat(structuredTask(), "gpt-cheap");

    // Body is schema-valid and re-serialised through the parser.
    assertThat(response.body()).contains("\"answer\"").contains("42");

    // Assert the request carried a json_schema response format with the tool's schema name.
    org.mockito.ArgumentCaptor<ChatCompletionCreateParams> captor =
        org.mockito.ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
    verify(completionService).create(captor.capture());
    ChatCompletionCreateParams params = captor.getValue();
    assertThat(params.responseFormat()).isPresent();
    assertThat(params.responseFormat().get().isJsonSchema()).isTrue();
    assertThat(params.responseFormat().get().asJsonSchema().jsonSchema().name())
        .isEqualTo("answer");
    // The configured model id is carried on the request (read via toString to avoid the SDK's
    // known-vs-raw ChatModel.asString() strictness, which is irrelevant to this assertion).
    assertThat(params.model().toString()).contains("gpt-cheap");
  }

  @Test
  void chat_structuredOutput_schemaInvalidBody_throwsInvalidResponse() {
    // The simpleTool schema requires "answer" to be a string; an integer violates it.
    ChatCompletion completion = completionWith("{\"answer\":123}", 1L, 1L);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);

    assertThatThrownBy(() -> client().chat(structuredTask(), "gpt-cheap"))
        .isInstanceOf(AiInvalidResponseException.class);
  }

  @Test
  void chat_modelRefusal_throwsInvalidResponse() {
    // refusal present, no content — a real refusal response.
    ChatCompletion completion =
        completion(null, JsonValue.from("I cannot help with that"), null, null);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);

    assertThatThrownBy(() -> client().chat(freeTextTask(), "gpt-cheap"))
        .isInstanceOf(AiInvalidResponseException.class);
  }

  @Test
  void chat_structuredOutput_contentAsJsonObject_isExtractedAndValidated() {
    // REGRESSION: a strict structured-output response returns `content` as a JSON OBJECT, not a
    // string. The SDK's typed choice.message() throws OpenAIInvalidDataException on this shape;
    // OpenAiChatClient must read it via the raw/untyped path instead. Build the exact live shape.
    ObjectNode content = objectMapper.createObjectNode();
    content.put("answer", "42");
    ChatCompletion completion = completionWithObjectContent(content, 3L, 5L);

    // Guard: prove the mocked completion really reproduces the bug — the strict typed accessor
    // throws on this content shape, so a naive .message().content() client could not read it.
    assertThatThrownBy(() -> completion.choices().get(0).message().content())
        .isInstanceOf(OpenAIInvalidDataException.class);

    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);

    ChatResponse response = client().chat(structuredTask(), "gpt-cheap");

    // The object content is serialised to JSON, validated against the tool schema, and returned;
    // usage/cost extraction is preserved through the new raw-content path.
    assertThat(response.body()).contains("\"answer\"").contains("42");
    assertThat(response.requestTokens()).isEqualTo(3);
    assertThat(response.responseTokens()).isEqualTo(5);
  }

  @Test
  void chat_noChoices_throwsInvalidResponse() {
    ChatCompletion completion = mock(ChatCompletion.class);
    when(completion.choices()).thenReturn(List.of());
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);

    assertThatThrownBy(() -> client().chat(freeTextTask(), "gpt-cheap"))
        .isInstanceOf(AiInvalidResponseException.class);
  }

  @Test
  void chat_400BadRequest_translatesToInvalidRequest_andDoesNotRetry() {
    BadRequestException ex = mock(BadRequestException.class);
    when(ex.statusCode()).thenReturn(400);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenThrow(ex);

    assertThatThrownBy(() -> client().chat(freeTextTask(), "gpt-cheap"))
        .isInstanceOf(AiInvalidRequestException.class);
    verify(completionService, times(1)).create(any(ChatCompletionCreateParams.class));
  }

  @Test
  void chat_401Unauthorized_translatesToInvalidRequest_andDoesNotRetry() {
    UnauthorizedException ex = mock(UnauthorizedException.class);
    when(ex.statusCode()).thenReturn(401);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenThrow(ex);

    assertThatThrownBy(() -> client().chat(freeTextTask(), "gpt-cheap"))
        .isInstanceOf(AiInvalidRequestException.class);
    verify(completionService, times(1)).create(any(ChatCompletionCreateParams.class));
  }

  @Test
  void chat_429RateLimit_retriedThenExhausted_throwsRateLimit() {
    RateLimitException ex = mock(RateLimitException.class);
    when(ex.statusCode()).thenReturn(429);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenThrow(ex);

    assertThatThrownBy(() -> client().chat(freeTextTask(), "gpt-cheap"))
        .isInstanceOf(AiRateLimitException.class);
    // 1 + maxRetries(3) = 4 wire attempts.
    verify(completionService, times(4)).create(any(ChatCompletionCreateParams.class));
  }

  @Test
  void chat_5xxThenSuccess_retriesAndReturns() {
    InternalServerException ex = mock(InternalServerException.class);
    when(ex.statusCode()).thenReturn(503);
    ChatCompletion completion = completionWith("{\"ok\":true}", 1L, 1L);
    when(completionService.create(any(ChatCompletionCreateParams.class)))
        .thenThrow(ex)
        .thenReturn(completion);

    ChatResponse response = client().chat(freeTextTask(), "gpt-cheap");

    assertThat(response.body()).isEqualTo("{\"ok\":true}");
    verify(completionService, times(2)).create(any(ChatCompletionCreateParams.class));
  }

  @Test
  void chat_transportException_retriesAsTimeout_thenExhausts() {
    OpenAIIoException ex = mock(OpenAIIoException.class);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenThrow(ex);

    assertThatThrownBy(() -> client().chat(freeTextTask(), "gpt-cheap"))
        .isInstanceOf(AiUnavailableException.class);
    verify(completionService, times(4)).create(any(ChatCompletionCreateParams.class));
  }

  @Test
  void chat_circuitOpen_shortCircuitsWithoutWireCall() {
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    OpenAiChatClient client = client(registry);
    // Force the task-type breaker OPEN.
    AiTask<String> task = freeTextTask();
    CircuitBreaker breaker = client.breakerFor(task);
    breaker.transitionToOpenState();

    assertThatThrownBy(() -> client.chat(task, "gpt-cheap"))
        .isInstanceOf(AiCircuitOpenException.class);
    // No wire call was made.
    verify(completionService, times(0)).create(any(ChatCompletionCreateParams.class));
  }

  @Test
  void chat_noOpenAiClientBean_throwsAiUnavailable() {
    when(clientProvider.getIfAvailable()).thenReturn(null);
    OpenAiChatClient c =
        new OpenAiChatClient(
            clientProvider, properties, objectMapper, parser, CircuitBreakerRegistry.ofDefaults());
    c.setSleeper(ms -> {});

    assertThatThrownBy(() -> c.chat(freeTextTask(), "gpt-cheap"))
        .isInstanceOf(AiUnavailableException.class);
  }

  @Test
  void buildParams_freeText_hasNoResponseFormat() {
    JsonNode ignored = null; // documents that a no-tool task carries no schema
    ChatCompletionCreateParams params = client().buildParams(freeTextTask(), "gpt-cheap");
    assertThat(params.responseFormat()).isEmpty();
    assertThat(ignored).isNull();
  }
}
