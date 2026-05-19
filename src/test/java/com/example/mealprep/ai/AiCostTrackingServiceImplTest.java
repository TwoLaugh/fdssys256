package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiCostTrackingService;
import com.example.mealprep.ai.spi.TaskType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@code AiCostTrackingServiceImpl}. The repository is a legitimate cross-class
 * mock; the micropence→pence rounding and the null-argument guards are pure arithmetic the baseline
 * left 10/10 uncovered. Clock is fixed so the {@code since} cutoff is deterministic.
 */
@ExtendWith(MockitoExtension.class)
class AiCostTrackingServiceImplTest {

  @Mock private AiCallLogRepository repository;

  private final Instant now = Instant.parse("2026-05-10T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private AiCostTrackingService service;

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    Class<?> impl =
        Class.forName("com.example.mealprep.ai.domain.service.internal.AiCostTrackingServiceImpl");
    var ctor = impl.getDeclaredConstructor(AiCallLogRepository.class, Clock.class);
    ctor.setAccessible(true);
    service = (AiCostTrackingService) ctor.newInstance(repository, clock);
  }

  // ---------------- pencesSpentBy ----------------

  @Test
  void pencesSpentBy_nullUser_returnsZero_withoutQuery() {
    assertThat(service.pencesSpentBy(null, Duration.ofHours(24)))
        .isEqualByComparingTo(BigDecimal.ZERO);
    verify(repository, never()).sumCostMicroPenceForUserSince(any(), any());
  }

  @Test
  void pencesSpentBy_nullWindow_returnsZero_withoutQuery() {
    assertThat(service.pencesSpentBy(UUID.randomUUID(), null))
        .isEqualByComparingTo(BigDecimal.ZERO);
    verify(repository, never()).sumCostMicroPenceForUserSince(any(), any());
  }

  @Test
  void pencesSpentBy_convertsMicropenceToPence_halfUp_andUsesWindowCutoff() {
    UUID userId = UUID.randomUUID();
    Duration window = Duration.ofHours(24);
    // 1_234_567 micropence = 1.234567 pence → HALF_UP 2dp = 1.23
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(1_234_567L);

    BigDecimal result = service.pencesSpentBy(userId, window);

    assertThat(result).isEqualByComparingTo(new BigDecimal("1.23"));
    ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
    verify(repository).sumCostMicroPenceForUserSince(eq(userId), sinceCap.capture());
    assertThat(sinceCap.getValue()).isEqualTo(now.minus(window));
  }

  @Test
  void pencesSpentBy_roundsHalfUp_atExactBoundary() {
    UUID userId = UUID.randomUUID();
    // 1_005_000 micropence = 1.005 pence → HALF_UP 2dp = 1.01 (not 1.00)
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(1_005_000L);

    assertThat(service.pencesSpentBy(userId, Duration.ofHours(1)))
        .isEqualByComparingTo(new BigDecimal("1.01"));
  }

  @Test
  void pencesSpentBy_zeroSpend_returnsZeroPence() {
    UUID userId = UUID.randomUUID();
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(0L);

    assertThat(service.pencesSpentBy(userId, Duration.ofHours(24)))
        .isEqualByComparingTo(new BigDecimal("0.00"));
  }

  // ---------------- pencesSpentByUserPerTaskType ----------------

  @Test
  void perTaskType_nullUser_returnsEmptyMap_withoutQuery() {
    assertThat(service.pencesSpentByUserPerTaskType(null, Duration.ofHours(24))).isEmpty();
    verify(repository, never()).sumCostMicroPenceForUserSinceByTaskType(any(), any());
  }

  @Test
  void perTaskType_nullWindow_returnsEmptyMap_withoutQuery() {
    assertThat(service.pencesSpentByUserPerTaskType(UUID.randomUUID(), null)).isEmpty();
    verify(repository, never()).sumCostMicroPenceForUserSinceByTaskType(any(), any());
  }

  @Test
  void perTaskType_aggregatesEachRow_convertingMicropence() {
    UUID userId = UUID.randomUUID();
    Duration window = Duration.ofHours(24);
    when(repository.sumCostMicroPenceForUserSinceByTaskType(eq(userId), any()))
        .thenReturn(
            List.of(
                new Object[] {TaskType.FEEDBACK_CLASSIFICATION, 2_500_000L},
                new Object[] {TaskType.RECIPE_ADAPTATION, 750_000L}));

    Map<TaskType, BigDecimal> result = service.pencesSpentByUserPerTaskType(userId, window);

    assertThat(result).hasSize(2);
    assertThat(result.get(TaskType.FEEDBACK_CLASSIFICATION))
        .isEqualByComparingTo(new BigDecimal("2.50"));
    assertThat(result.get(TaskType.RECIPE_ADAPTATION)).isEqualByComparingTo(new BigDecimal("0.75"));
    ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
    verify(repository).sumCostMicroPenceForUserSinceByTaskType(eq(userId), sinceCap.capture());
    assertThat(sinceCap.getValue()).isEqualTo(now.minus(window));
  }

  @Test
  void perTaskType_noRows_returnsEmptyMap() {
    UUID userId = UUID.randomUUID();
    when(repository.sumCostMicroPenceForUserSinceByTaskType(eq(userId), any()))
        .thenReturn(List.of());

    assertThat(service.pencesSpentByUserPerTaskType(userId, Duration.ofHours(24))).isEmpty();
  }

  @Test
  void perTaskType_numericRowValue_isCoercedViaNumberLongValue() {
    UUID userId = UUID.randomUUID();
    // Repository may return the SUM as Integer/BigInteger depending on the dialect; the impl
    // coerces via ((Number) row[1]).longValue() — feed an Integer to pin that cast survives.
    when(repository.sumCostMicroPenceForUserSinceByTaskType(eq(userId), any()))
        .thenReturn(
            List.<Object[]>of(new Object[] {TaskType.INTAKE_PARSE, Integer.valueOf(3_000_000)}));

    Map<TaskType, BigDecimal> result =
        service.pencesSpentByUserPerTaskType(userId, Duration.ofHours(6));

    assertThat(result.get(TaskType.INTAKE_PARSE)).isEqualByComparingTo(new BigDecimal("3.00"));
  }

  // ---------------- pencesSpentGlobalLast24h ----------------

  @Test
  void globalLast24h_usesFixed24hWindow_andConverts() {
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(99_995_000L);

    BigDecimal result = service.pencesSpentGlobalLast24h();

    // 99_995_000 micropence = 99.995 pence → HALF_UP 2dp = 100.00
    assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
    verify(repository).sumCostMicroPenceGlobalSince(sinceCap.capture());
    assertThat(sinceCap.getValue()).isEqualTo(now.minus(Duration.ofHours(24)));
  }
}
