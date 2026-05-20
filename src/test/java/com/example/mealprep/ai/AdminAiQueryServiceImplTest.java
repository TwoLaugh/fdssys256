package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.api.dto.AiCallLogDto;
import com.example.mealprep.ai.api.dto.CostSummaryDto;
import com.example.mealprep.ai.api.mapper.AiCallLogMapper;
import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AdminAiQueryService;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@code AdminAiQueryServiceImpl}. The repository is a legitimate cross-class mock;
 * the impl is constructed reflectively. Targets all four branches of {@code getCallLog} (with /
 * without taskType, with / without userId) plus the windowHours clamp.
 */
@ExtendWith(MockitoExtension.class)
class AdminAiQueryServiceImplTest {

  @Mock private AiCallLogRepository repository;
  @Mock private AiCallLogMapper mapper;

  private final Instant now = Instant.parse("2026-05-08T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private AdminAiQueryService service;

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    Class<?> impl =
        Class.forName("com.example.mealprep.ai.domain.service.internal.AdminAiQueryServiceImpl");
    Constructor<?> ctor =
        impl.getDeclaredConstructor(AiCallLogRepository.class, AiCallLogMapper.class, Clock.class);
    ctor.setAccessible(true);
    service = (AdminAiQueryService) ctor.newInstance(repository, mapper, clock);
  }

  // ---------------- getCostSummary ----------------

  @Test
  void getCostSummary_clampsWindow_toMinimumOneHour() {
    // windowHours=0 should clamp to 1
    when(repository.countSince(any())).thenReturn(0L);
    when(repository.sumCostSince(any())).thenReturn(0L);
    when(repository.findTopUsersByCostSince(any(), any())).thenReturn(List.of());

    CostSummaryDto dto = service.getCostSummary(0);

    assertThat(dto.windowHours()).isEqualTo(1);
    ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
    verify(repository).countSince(sinceCap.capture());
    assertThat(sinceCap.getValue()).isEqualTo(now.minus(Duration.ofHours(1)));
  }

  @Test
  void getCostSummary_clampsWindow_toMaxThirtyDays() {
    when(repository.countSince(any())).thenReturn(0L);
    when(repository.sumCostSince(any())).thenReturn(0L);
    when(repository.findTopUsersByCostSince(any(), any())).thenReturn(List.of());

    CostSummaryDto dto = service.getCostSummary(99999);

    assertThat(dto.windowHours()).isEqualTo(24 * 30);
  }

  @Test
  void getCostSummary_passThroughWindow_inRange() {
    when(repository.countSince(any())).thenReturn(0L);
    when(repository.sumCostSince(any())).thenReturn(0L);
    when(repository.findTopUsersByCostSince(any(), any())).thenReturn(List.of());

    CostSummaryDto dto = service.getCostSummary(72);
    assertThat(dto.windowHours()).isEqualTo(72);
  }

  @Test
  void getCostSummary_aggregatesTopUsers_andCallsRepoOnce() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    when(repository.countSince(any())).thenReturn(15L);
    when(repository.sumCostSince(any())).thenReturn(2_500_000L);
    when(repository.findTopUsersByCostSince(any(), any()))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {userA, 7L, 1_500_000L}, new Object[] {userB, 8L, 1_000_000L}));

    CostSummaryDto dto = service.getCostSummary(24);

    assertThat(dto.windowHours()).isEqualTo(24);
    assertThat(dto.totalCalls()).isEqualTo(15L);
    assertThat(dto.totalMicroPence()).isEqualTo(2_500_000L);
    assertThat(dto.topUsers()).hasSize(2);
    assertThat(dto.topUsers().get(0).userId()).isEqualTo(userA);
    assertThat(dto.topUsers().get(0).calls()).isEqualTo(7L);
    assertThat(dto.topUsers().get(0).costMicroPence()).isEqualTo(1_500_000L);
    assertThat(dto.topUsers().get(1).userId()).isEqualTo(userB);
  }

  // ---------------- getCallLog ----------------

  /** Kills {@code getCallLog:62,64,66} NegateConditionals — branch matrix over both filters. */
  @Test
  void getCallLog_noFilters_callsFindAllPaged() {
    Pageable pageable = PageRequest.of(0, 20);
    Page<AiCallLog> empty = new PageImpl<>(List.of());
    when(repository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(empty);

    Page<AiCallLogDto> result = service.getCallLog(null, null, pageable);

    assertThat(result.getContent()).isEmpty();
    verify(repository).findAllByOrderByCreatedAtDesc(pageable);
    verify(repository, never()).findByTaskTypeOrderByCreatedAtDesc(any(), any());
    verify(repository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    verify(repository, never()).findByTaskTypeAndUserIdOrderByCreatedAtDesc(any(), any(), any());
  }

  @Test
  void getCallLog_taskTypeOnly_callsTaskTypeFinder() {
    Pageable pageable = PageRequest.of(0, 20);
    AiCallLog row = sampleRow();
    when(repository.findByTaskTypeOrderByCreatedAtDesc(
            eq(TaskType.FEEDBACK_CLASSIFICATION), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(row)));
    AiCallLogDto dto = sampleDto();
    when(mapper.toDto(row)).thenReturn(dto);

    Page<AiCallLogDto> result =
        service.getCallLog(TaskType.FEEDBACK_CLASSIFICATION, null, pageable);

    assertThat(result.getContent()).containsExactly(dto);
    verify(repository)
        .findByTaskTypeOrderByCreatedAtDesc(TaskType.FEEDBACK_CLASSIFICATION, pageable);
    verify(repository, never()).findAllByOrderByCreatedAtDesc(any());
    verify(repository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
  }

  @Test
  void getCallLog_userIdOnly_callsUserFinder() {
    Pageable pageable = PageRequest.of(0, 20);
    UUID userId = UUID.randomUUID();
    AiCallLog row = sampleRow();
    when(repository.findByUserIdOrderByCreatedAtDesc(eq(userId), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(row)));
    AiCallLogDto dto = sampleDto();
    when(mapper.toDto(row)).thenReturn(dto);

    Page<AiCallLogDto> result = service.getCallLog(null, userId, pageable);

    assertThat(result.getContent()).containsExactly(dto);
    verify(repository).findByUserIdOrderByCreatedAtDesc(userId, pageable);
    verify(repository, never()).findByTaskTypeOrderByCreatedAtDesc(any(), any());
    verify(repository, never()).findByTaskTypeAndUserIdOrderByCreatedAtDesc(any(), any(), any());
  }

  @Test
  void getCallLog_bothFilters_callsCompoundFinder() {
    Pageable pageable = PageRequest.of(0, 20);
    UUID userId = UUID.randomUUID();
    AiCallLog row = sampleRow();
    when(repository.findByTaskTypeAndUserIdOrderByCreatedAtDesc(
            eq(TaskType.RECIPE_ADAPTATION), eq(userId), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(row)));
    AiCallLogDto dto = sampleDto();
    when(mapper.toDto(row)).thenReturn(dto);

    Page<AiCallLogDto> result = service.getCallLog(TaskType.RECIPE_ADAPTATION, userId, pageable);

    assertThat(result.getContent()).containsExactly(dto);
    verify(repository)
        .findByTaskTypeAndUserIdOrderByCreatedAtDesc(TaskType.RECIPE_ADAPTATION, userId, pageable);
    verify(repository, never()).findAllByOrderByCreatedAtDesc(any());
    verify(repository, never()).findByTaskTypeOrderByCreatedAtDesc(any(), any());
    verify(repository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
  }

  // ---------------- helpers ----------------

  private static AiCallLog sampleRow() {
    return new AiCallLog(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        TaskType.FEEDBACK_CLASSIFICATION,
        ModelTier.CHEAP,
        "haiku",
        "p",
        1,
        CallStatus.SUCCEEDED);
  }

  private static AiCallLogDto sampleDto() {
    return new AiCallLogDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        TaskType.FEEDBACK_CLASSIFICATION,
        ModelTier.CHEAP,
        "haiku",
        "p",
        1,
        0,
        0,
        0L,
        CallStatus.SUCCEEDED,
        null,
        0,
        Instant.now(),
        null);
  }
}
