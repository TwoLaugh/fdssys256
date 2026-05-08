package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.internal.OpenAiEmbeddingClient;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.openai.client.OpenAIClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.CreateEmbeddingResponse;
import com.openai.models.Embedding;
import com.openai.models.EmbeddingCreateParams;
import com.openai.services.blocking.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link OpenAiEmbeddingClient}. The {@link OpenAIClient} surface is mocked at the
 * {@code embeddings()} seam — we assert retry shape and error mapping without a real HTTP client.
 */
class OpenAiEmbeddingClientTest {

  private final AiProperties properties =
      new AiProperties("k", null, "haiku", "sonnet", "opus", 60, 3, "openai-key", null, null);

  private final OpenAIClient openAiClient = mock(OpenAIClient.class);
  private final EmbeddingService embeddingService = mock(EmbeddingService.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<OpenAIClient> clientProvider = mock(ObjectProvider.class);

  private OpenAiEmbeddingClient client() {
    when(clientProvider.getIfAvailable()).thenReturn(openAiClient);
    when(openAiClient.embeddings()).thenReturn(embeddingService);
    OpenAiEmbeddingClient c = new OpenAiEmbeddingClient(clientProvider, properties);
    c.setSleeper(ms -> {}); // no real sleep in tests
    return c;
  }

  @Test
  void embed_happyPath_returnsVectorAndTokens() {
    CreateEmbeddingResponse resp = mock(CreateEmbeddingResponse.class);
    Embedding embedding = mock(Embedding.class);
    CreateEmbeddingResponse.Usage usage = mock(CreateEmbeddingResponse.Usage.class);
    when(resp.data()).thenReturn(List.of(embedding));
    when(embedding.embedding()).thenReturn(List.of(0.1, 0.2, 0.3));
    when(resp.usage()).thenReturn(usage);
    when(usage.promptTokens()).thenReturn(7L);
    when(embeddingService.create(any(EmbeddingCreateParams.class))).thenReturn(resp);

    OpenAiEmbeddingClient.EmbeddingResult result =
        client().embed("hello", "text-embedding-3-small");

    assertThat(result.vector()).hasSize(3);
    assertThat(result.vector()[0]).isEqualTo(0.1f);
    assertThat(result.vector()[1]).isEqualTo(0.2f);
    assertThat(result.inputTokens()).isEqualTo(7);
  }

  @Test
  void embed_4xxBadRequest_translatesToInvalidRequest_andDoesNotRetry() {
    when(embeddingService.create(any(EmbeddingCreateParams.class)))
        .thenThrow(mock(BadRequestException.class));

    assertThatThrownBy(() -> client().embed("hello", "text-embedding-3-small"))
        .isInstanceOf(AiInvalidRequestException.class);
    verify(embeddingService, times(1)).create(any(EmbeddingCreateParams.class));
  }

  @Test
  void embed_unauthorized_translatesToInvalidRequest_andDoesNotRetry() {
    when(embeddingService.create(any(EmbeddingCreateParams.class)))
        .thenThrow(mock(UnauthorizedException.class));

    assertThatThrownBy(() -> client().embed("hello", "text-embedding-3-small"))
        .isInstanceOf(AiInvalidRequestException.class);
    verify(embeddingService, times(1)).create(any(EmbeddingCreateParams.class));
  }

  @Test
  void embed_5xxThenSuccess_retriesAndReturns() {
    CreateEmbeddingResponse resp = mock(CreateEmbeddingResponse.class);
    Embedding embedding = mock(Embedding.class);
    when(resp.data()).thenReturn(List.of(embedding));
    when(embedding.embedding()).thenReturn(List.of(1.0, 2.0));
    when(resp.usage()).thenReturn(null);
    when(embeddingService.create(any(EmbeddingCreateParams.class)))
        .thenThrow(mock(InternalServerException.class))
        .thenReturn(resp);

    OpenAiEmbeddingClient.EmbeddingResult result = client().embed("x", "text-embedding-3-small");

    assertThat(result.vector()).hasSize(2);
    assertThat(result.inputTokens()).isNull();
    verify(embeddingService, times(2)).create(any(EmbeddingCreateParams.class));
  }

  @Test
  void embed_allAttemptsFail_throwsAiUnavailable() {
    when(embeddingService.create(any(EmbeddingCreateParams.class)))
        .thenThrow(mock(InternalServerException.class));

    assertThatThrownBy(() -> client().embed("x", "text-embedding-3-small"))
        .isInstanceOf(AiUnavailableException.class);
    verify(embeddingService, times(3)).create(any(EmbeddingCreateParams.class));
  }

  @Test
  void embed_emptyDataArray_throwsInvalidResponse() {
    CreateEmbeddingResponse resp = mock(CreateEmbeddingResponse.class);
    when(resp.data()).thenReturn(List.of());
    when(embeddingService.create(any(EmbeddingCreateParams.class))).thenReturn(resp);

    assertThatThrownBy(() -> client().embed("x", "text-embedding-3-small"))
        .isInstanceOf(AiInvalidResponseException.class);
  }

  @Test
  void embed_emptyVector_throwsInvalidResponse() {
    CreateEmbeddingResponse resp = mock(CreateEmbeddingResponse.class);
    Embedding embedding = mock(Embedding.class);
    when(resp.data()).thenReturn(List.of(embedding));
    when(embedding.embedding()).thenReturn(List.of());
    when(embeddingService.create(any(EmbeddingCreateParams.class))).thenReturn(resp);

    assertThatThrownBy(() -> client().embed("x", "text-embedding-3-small"))
        .isInstanceOf(AiInvalidResponseException.class);
  }

  @Test
  void embed_noOpenAiClientBean_throwsAiUnavailable() {
    when(clientProvider.getIfAvailable()).thenReturn(null);
    OpenAiEmbeddingClient c = new OpenAiEmbeddingClient(clientProvider, properties);
    c.setSleeper(ms -> {});

    assertThatThrownBy(() -> c.embed("x", "text-embedding-3-small"))
        .isInstanceOf(AiUnavailableException.class);
  }
}
