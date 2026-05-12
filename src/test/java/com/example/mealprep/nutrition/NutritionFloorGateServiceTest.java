package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.DailyActivityLogRepository;
import com.example.mealprep.nutrition.domain.repository.FoodMoodJournalRepository;
import com.example.mealprep.nutrition.domain.repository.HealthDirectiveRepository;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeAuditRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate;
import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import com.example.mealprep.nutrition.domain.service.internal.NutritionServiceImpl;
import com.example.mealprep.nutrition.exception.InvalidPlanRollupException;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit-level coverage of {@link NutritionServiceImpl}'s {@code NutritionFloorGateService} surface.
 * The repository is mocked; tests assert pass/fail bit, violation list shape, summary string, and
 * household-batch ordering.
 */
@ExtendWith(MockitoExtension.class)
class NutritionFloorGateServiceTest {

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;
  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private IntakeAuditRepository intakeAuditRepository;
  @Mock private DailyActivityLogRepository dailyActivityLogRepository;
  @Mock private FoodMoodJournalRepository journalRepository;
  @Mock private IngredientMappingRepository ingredientMappingRepository;
  @Mock private HealthDirectiveRepository healthDirectiveRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final TargetsMapper mapper =
      new com.example.mealprep.nutrition.api.mapper.TargetsMapperImpl();
  private final IntakeMapper intakeMapper =
      new com.example.mealprep.nutrition.api.mapper.IntakeMapperImpl();
  private final DailyActivityMapper dailyActivityMapper =
      new com.example.mealprep.nutrition.api.mapper.DailyActivityMapperImpl();
  private final JournalMapper journalMapper = new JournalMapper() {};
  private final IngredientMappingMapper ingredientMappingMapper = new IngredientMappingMapper() {};
  private final HealthDirectiveMapper healthDirectiveMapper = new HealthDirectiveMapper() {};
  private final IntakeKeyNormaliser intakeKeyNormaliser = new IntakeKeyNormaliser();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

  private NutritionServiceImpl service;

  @BeforeEach
  void setUp() {
    org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
        new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    org.springframework.beans.factory.ObjectProvider<
            com.example.mealprep.nutrition.spi.DirectiveApplyTarget>
        emptyProvider =
            bf.getBeanProvider(com.example.mealprep.nutrition.spi.DirectiveApplyTarget.class);
    DirectiveApplier directiveApplier =
        new DirectiveApplier(
            targetsRepository,
            auditRepository,
            emptyProvider,
            eventPublisher,
            objectMapper,
            fixedClock);
    service =
        new NutritionServiceImpl(
            targetsRepository,
            auditRepository,
            intakeDayRepository,
            intakeAuditRepository,
            dailyActivityLogRepository,
            journalRepository,
            ingredientMappingRepository,
            healthDirectiveRepository,
            mapper,
            intakeMapper,
            dailyActivityMapper,
            journalMapper,
            ingredientMappingMapper,
            healthDirectiveMapper,
            intakeKeyNormaliser,
            new DirectiveSafetyGate(),
            directiveApplier,
            eventPublisher,
            objectMapper,
            fixedClock);
  }

  @Test
  void evaluate_noTargetsConfigured_returnsPassedWithDefaultSummary() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());
    CandidatePlanRollupDto rollup =
        NutritionTestData.planRollup(
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 9))));

    FloorGateResultDto result = service.evaluate(userId, rollup);

    assertThat(result.passed()).isTrue();
    assertThat(result.violations()).isEmpty();
    assertThat(result.summary()).isEqualTo("No targets configured — gate passes by default");
  }

  @Test
  void evaluate_macroFloorMet_returnsPassed() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    CandidatePlanRollupDto rollup =
        NutritionTestData.planRollup(
            List.of(
                NutritionTestData.dailyRollup(
                    LocalDate.of(2026, 5, 9),
                    BigDecimal.valueOf(150.0),
                    BigDecimal.valueOf(260.0),
                    BigDecimal.valueOf(80.0),
                    BigDecimal.valueOf(35.0))));

    FloorGateResultDto result = service.evaluate(userId, rollup);

    assertThat(result.passed()).isTrue();
    assertThat(result.violations()).isEmpty();
    assertThat(result.summary()).isEqualTo("Plan passes all hard floors across 1 day(s)");
  }

  @Test
  void evaluate_macroFloorBreached_onOneDay_returnsOneViolation() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    CandidateDailyRollupDto under =
        NutritionTestData.dailyRollup(
            LocalDate.of(2026, 5, 9),
            BigDecimal.valueOf(80.0),
            BigDecimal.valueOf(260.0),
            BigDecimal.valueOf(80.0),
            BigDecimal.valueOf(35.0));
    CandidatePlanRollupDto rollup = NutritionTestData.planRollup(List.of(under));

    FloorGateResultDto result = service.evaluate(userId, rollup);

    assertThat(result.passed()).isFalse();
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().get(0).macroOrMicro()).isEqualTo("protein");
    assertThat(result.violations().get(0).floor()).isEqualTo(BigDecimal.valueOf(100.0));
    assertThat(result.violations().get(0).actual()).isEqualTo(BigDecimal.valueOf(80.0));
    assertThat(result.violations().get(0).date()).isEqualTo(LocalDate.of(2026, 5, 9));
    assertThat(result.summary()).isEqualTo("Plan fails 1 hard floor(s) across 1 day(s)");
  }

  @Test
  void evaluate_multipleMacrosBreachedAcrossDays_summaryAggregates() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .withFibreFloor(BigDecimal.valueOf(25.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    CandidateDailyRollupDto day1 =
        NutritionTestData.dailyRollup(
            LocalDate.of(2026, 5, 9),
            BigDecimal.valueOf(80.0), // protein under
            BigDecimal.valueOf(260.0),
            BigDecimal.valueOf(80.0),
            BigDecimal.valueOf(10.0)); // fibre under
    CandidateDailyRollupDto day2 =
        NutritionTestData.dailyRollup(
            LocalDate.of(2026, 5, 10),
            BigDecimal.valueOf(150.0),
            BigDecimal.valueOf(260.0),
            BigDecimal.valueOf(80.0),
            BigDecimal.valueOf(15.0)); // fibre under
    CandidatePlanRollupDto rollup = NutritionTestData.planRollup(List.of(day1, day2));

    FloorGateResultDto result = service.evaluate(userId, rollup);

    assertThat(result.passed()).isFalse();
    assertThat(result.violations()).hasSize(3);
    assertThat(result.summary()).isEqualTo("Plan fails 3 hard floor(s) across 2 day(s)");
  }

  @Test
  void evaluate_macroFloorNull_neverProducesViolation() {
    UUID userId = UUID.randomUUID();
    // No floor set anywhere — no violations regardless of how low actuals are.
    NutritionTargets targets = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    CandidatePlanRollupDto rollup =
        NutritionTestData.planRollup(
            List.of(
                NutritionTestData.dailyRollup(
                    LocalDate.of(2026, 5, 9),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)));

    FloorGateResultDto result = service.evaluate(userId, rollup);

    assertThat(result.passed()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void evaluate_invalidRollup_endDateBeforeStartDate_throws400() {
    UUID userId = UUID.randomUUID();
    CandidatePlanRollupDto rollup =
        new CandidatePlanRollupDto(
            LocalDate.of(2026, 5, 10),
            LocalDate.of(2026, 5, 9),
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 10))));

    assertThatThrownBy(() -> service.evaluate(userId, rollup))
        .isInstanceOf(InvalidPlanRollupException.class)
        .hasMessageContaining("endDate");
  }

  @Test
  void evaluate_dayDateOutsideRange_throws400() {
    UUID userId = UUID.randomUUID();
    CandidatePlanRollupDto rollup =
        new CandidatePlanRollupDto(
            LocalDate.of(2026, 5, 9),
            LocalDate.of(2026, 5, 10),
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 12))));

    assertThatThrownBy(() -> service.evaluate(userId, rollup))
        .isInstanceOf(InvalidPlanRollupException.class)
        .hasMessageContaining("perDay date");
  }

  @Test
  void evaluateForHousehold_preservesInputOrder() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID carol = UUID.randomUUID();
    when(targetsRepository.findByUserId(alice)).thenReturn(Optional.empty());
    when(targetsRepository.findByUserId(bob)).thenReturn(Optional.empty());
    when(targetsRepository.findByUserId(carol)).thenReturn(Optional.empty());

    CandidatePlanRollupDto rollup =
        NutritionTestData.planRollup(
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 9))));

    Map<UUID, FloorGateResultDto> out =
        service.evaluateForHousehold(List.of(alice, bob, carol), rollup);

    assertThat(out).hasSize(3);
    assertThat(out.keySet()).containsExactly(alice, bob, carol);
    out.values().forEach(v -> assertThat(v.passed()).isTrue());
  }

  @Test
  void evaluateForHousehold_emptyIds_returnsEmptyMap() {
    CandidatePlanRollupDto rollup =
        NutritionTestData.planRollup(
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 9))));

    Map<UUID, FloorGateResultDto> out = service.evaluateForHousehold(List.of(), rollup);

    assertThat(out).isEmpty();
  }
}
