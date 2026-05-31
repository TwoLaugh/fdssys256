package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.api.dto.TasteSimilarUserDto;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.domain.service.TasteSimilarityQueryService;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * pgvector end-to-end IT for the taste-vector embedding pipeline (preference-5). Boots the full
 * context against the pgvector-enabled Postgres Testcontainer and exercises the column + partial
 * HNSW index + cosine query through the real SPI — with NO live embedding key (vectors are supplied
 * directly to {@code storeTasteVector}, the same path the async listener uses after {@code
 * AiService.embed} returns; the {@code TestAiService} stub covers the embed leg).
 *
 * <p>Verifies: the native pgvector UPDATE stores + casts the vector and flips status to EMBEDDED;
 * the {@code TasteVectorConverter} reads it back exactly; the freshness guard no-ops a stale
 * docVersion; {@code markTasteVectorFailed} flips FAILED without a vector; and the cosine
 * nearest-neighbour + pairwise similarity queries run over the index.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
// The general test profile gates TasteProfileEmbeddingListener OFF (application-test.properties) so
// its @Async AFTER_COMMIT ai_call_log write can't pollute cross-IT statement/row counts. This IT is
// the dedicated taste-vector pipeline IT, so re-enable the listener here to keep the embedding path
// (TasteProfileChangedEvent -> AiService.embed -> storeTasteVector) registered and tested
// end-to-end.
@TestPropertySource(properties = "mealprep.preference.embedding.listener-enabled=true")
class TasteVectorPipelineIT {

  @Autowired private TasteProfileRepository repository;
  @Autowired private TasteProfileUpdateService updateService;
  @Autowired private TasteSimilarityQueryService similarity;

  private static final String MODEL_ID = "openai:text-embedding-3-small";

  @AfterEach
  void cleanup() {
    repository.deleteAll();
  }

  /** Seed a profile row at documentVersion 1 with PENDING vector status. */
  private TasteProfile seedProfile(UUID userId) {
    return repository.save(TasteProfileTestData.aggregate(userId));
  }

  private static float[] unitVector(int dim, int axis) {
    float[] v = new float[dim];
    v[axis % dim] = 1f;
    return v;
  }

  @Test
  void storeTasteVector_persistsCastsAndFlipsEmbedded_andReadsBackExactly() {
    UUID userId = UUID.randomUUID();
    seedProfile(userId);

    float[] vector = new float[1536];
    long state = 5L;
    for (int i = 0; i < vector.length; i++) {
      state ^= state << 13;
      state ^= state >>> 7;
      state ^= state << 17;
      vector[i] = ((state & 0xFFFF) / 65535.0f) * 2f - 1f;
    }

    updateService.storeTasteVector(userId, vector, MODEL_ID, 1);

    TasteProfile reloaded = repository.findByUserId(userId).orElseThrow();
    assertThat(reloaded.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.EMBEDDED);
    assertThat(reloaded.getTasteVectorModelId()).isEqualTo(MODEL_ID);
    assertThat(reloaded.getTasteVectorDocVersion()).isEqualTo(1);
    assertThat(reloaded.getTasteVectorEmbeddedAt()).isNotNull();
    assertThat(reloaded.getTasteVector()).containsExactly(vector); // round-trip exact via converter

    assertThat(similarity.getTasteVector(userId)).isPresent();
  }

  @Test
  void storeTasteVector_staleDocVersion_isNoOp() {
    UUID userId = UUID.randomUUID();
    seedProfile(userId); // documentVersion = 1

    // An embed computed from an OLD version (0) lands after the doc moved on — must NOT clobber.
    updateService.storeTasteVector(userId, unitVector(1536, 0), MODEL_ID, 0);

    TasteProfile reloaded = repository.findByUserId(userId).orElseThrow();
    assertThat(reloaded.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);
    assertThat(reloaded.getTasteVector()).isNull();
    assertThat(similarity.getTasteVector(userId)).isEmpty();
  }

  @Test
  void markTasteVectorFailed_flipsFailed_leavesVectorNull() {
    UUID userId = UUID.randomUUID();
    seedProfile(userId);

    updateService.markTasteVectorFailed(userId, 1);

    TasteProfile reloaded = repository.findByUserId(userId).orElseThrow();
    assertThat(reloaded.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.FAILED);
    assertThat(reloaded.getTasteVector()).isNull();
    assertThat(similarity.getTasteVector(userId)).isEmpty();
  }

  @Test
  void findSimilarUsers_ordersByCosineDistanceOverIndex() {
    UUID query = UUID.randomUUID();
    UUID near = UUID.randomUUID();
    UUID far = UUID.randomUUID();
    seedProfile(query);
    seedProfile(near);
    seedProfile(far);

    int dim = 1536;
    // query points along axis 0; near is mostly axis 0 (small axis-1 component); far is axis 1.
    float[] queryVec = unitVector(dim, 0);
    float[] nearVec = new float[dim];
    nearVec[0] = 1f;
    nearVec[1] = 0.1f;
    float[] farVec = unitVector(dim, 1);

    updateService.storeTasteVector(query, queryVec, MODEL_ID, 1);
    updateService.storeTasteVector(near, nearVec, MODEL_ID, 1);
    updateService.storeTasteVector(far, farVec, MODEL_ID, 1);

    List<TasteSimilarUserDto> results = similarity.findSimilarUsers(query, 10);

    // Excludes the query user; near must rank ahead of far; similarities in [0,1].
    assertThat(results).extracting(TasteSimilarUserDto::userId).doesNotContain(query);
    assertThat(results).extracting(TasteSimilarUserDto::userId).containsExactly(near, far);
    assertThat(results.get(0).similarity()).isGreaterThan(results.get(1).similarity());
    assertThat(results).allSatisfy(r -> assertThat(r.similarity()).isBetween(0.0, 1.0));

    // Pairwise: query vs near is highly similar (≈1), query vs far is orthogonal (≈0.5 after
    // remap).
    assertThat(similarity.cosineSimilarity(query, near).getAsDouble()).isGreaterThan(0.9);
    assertThat(similarity.cosineSimilarity(query, far).getAsDouble())
        .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
  }
}
