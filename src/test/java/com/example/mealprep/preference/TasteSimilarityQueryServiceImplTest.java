package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.service.internal.TasteSimilarityQueryServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for {@link TasteSimilarityQueryServiceImpl} (preference-5): the cosine math + the
 * {@code [-1,1] → [0,1]} planner-convention mapping, the EMBEDDED-only read gate, and the
 * empty/neutral results when a vector is missing. The native nearest query is exercised in the IT.
 */
@ExtendWith(MockitoExtension.class)
class TasteSimilarityQueryServiceImplTest {

  @Mock private TasteProfileRepository repository;

  private TasteSimilarityQueryServiceImpl service() {
    return new TasteSimilarityQueryServiceImpl(repository);
  }

  private static TasteProfile profile(UUID userId, TasteVectorStatus status, float[] vector) {
    return TasteProfile.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .tasteVectorStatus(status)
        .tasteVector(vector)
        .build();
  }

  @Test
  void getTasteVector_embedded_returnsVector() {
    UUID userId = UUID.randomUUID();
    float[] v = {0.1f, 0.2f};
    when(repository.findByUserId(userId))
        .thenReturn(Optional.of(profile(userId, TasteVectorStatus.EMBEDDED, v)));
    assertThat(service().getTasteVector(userId))
        .hasValueSatisfying(r -> assertThat(r).isEqualTo(v));
  }

  @Test
  void getTasteVector_pending_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(repository.findByUserId(userId))
        .thenReturn(
            Optional.of(profile(userId, TasteVectorStatus.PENDING, new float[] {0.1f, 0.2f})));
    assertThat(service().getTasteVector(userId)).isEmpty();
  }

  @Test
  void getTasteVector_noProfile_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(repository.findByUserId(userId)).thenReturn(Optional.empty());
    assertThat(service().getTasteVector(userId)).isEmpty();
  }

  @Test
  void cosineSimilarity_identicalVectors_isOne() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    float[] v = {1f, 2f, 3f};
    when(repository.findByUserId(a))
        .thenReturn(Optional.of(profile(a, TasteVectorStatus.EMBEDDED, v)));
    when(repository.findByUserId(b))
        .thenReturn(Optional.of(profile(b, TasteVectorStatus.EMBEDDED, v.clone())));
    // cos = 1 → (1+1)/2 = 1.0
    OptionalDouble sim = service().cosineSimilarity(a, b);
    assertThat(sim).isPresent();
    assertThat(sim.getAsDouble()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  void cosineSimilarity_oppositeVectors_isZero() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(repository.findByUserId(a))
        .thenReturn(Optional.of(profile(a, TasteVectorStatus.EMBEDDED, new float[] {1f, 0f})));
    when(repository.findByUserId(b))
        .thenReturn(Optional.of(profile(b, TasteVectorStatus.EMBEDDED, new float[] {-1f, 0f})));
    // cos = -1 → (-1+1)/2 = 0.0
    assertThat(service().cosineSimilarity(a, b).getAsDouble())
        .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  void cosineSimilarity_orthogonalVectors_isHalf() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(repository.findByUserId(a))
        .thenReturn(Optional.of(profile(a, TasteVectorStatus.EMBEDDED, new float[] {1f, 0f})));
    when(repository.findByUserId(b))
        .thenReturn(Optional.of(profile(b, TasteVectorStatus.EMBEDDED, new float[] {0f, 1f})));
    // cos = 0 → (0+1)/2 = 0.5
    assertThat(service().cosineSimilarity(a, b).getAsDouble())
        .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  void cosineSimilarity_missingOneVector_returnsEmpty() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(repository.findByUserId(a))
        .thenReturn(Optional.of(profile(a, TasteVectorStatus.EMBEDDED, new float[] {1f, 0f})));
    when(repository.findByUserId(b))
        .thenReturn(Optional.of(profile(b, TasteVectorStatus.PENDING, new float[] {0f, 1f})));
    assertThat(service().cosineSimilarity(a, b)).isEmpty();
  }

  @Test
  void findSimilarUsers_noQueryVector_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(repository.findByUserId(userId))
        .thenReturn(Optional.of(profile(userId, TasteVectorStatus.PENDING, null)));
    assertThat(service().findSimilarUsers(userId, 5)).isEmpty();
  }

  @Test
  void findSimilarUsers_mapsCosineDistanceToSimilarity() {
    UUID userId = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    when(repository.findByUserId(userId))
        .thenReturn(Optional.of(profile(userId, TasteVectorStatus.EMBEDDED, new float[] {1f, 0f})));
    // distance 0.25 → similarity 0.75
    List<Object[]> nearest = java.util.Collections.singletonList(new Object[] {other, 0.25d});
    lenient()
        .when(
            repository.findNearestUsersByTasteVector(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(3)))
        .thenReturn(nearest);

    var results = service().findSimilarUsers(userId, 3);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).userId()).isEqualTo(other);
    assertThat(results.get(0).similarity())
        .isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void findSimilarUsers_nonPositiveLimit_returnsEmpty() {
    assertThat(service().findSimilarUsers(UUID.randomUUID(), 0)).isEmpty();
  }
}
