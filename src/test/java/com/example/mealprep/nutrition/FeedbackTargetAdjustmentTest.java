package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.origin.ActorType;
import com.example.mealprep.nutrition.api.dto.FeedbackTargetAdjustment;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.api.mapper.TargetsMapperImpl;
import com.example.mealprep.nutrition.config.FeedbackAdjustmentProperties;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.AdjustmentDirection;
import com.example.mealprep.nutrition.domain.entity.AdjustmentMagnitude;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
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
import com.example.mealprep.nutrition.domain.service.internal.DivergenceDetector;
import com.example.mealprep.nutrition.domain.service.internal.FeedbackTargetResolver;
import com.example.mealprep.nutrition.domain.service.internal.IntakeAggregator;
import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import com.example.mealprep.nutrition.domain.service.internal.NutritionServiceImpl;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.exception.InvalidFeedbackAdjustmentException;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@code NutritionServiceImpl.applyFeedbackAdjustment} (nutrition-01i): macro /
 * calorie / micro / per-meal nudges, the {@code absoluteValue} precedence, the floor clamp, the
 * micro-not-opted-in no-op, and the unknown-target 422. Repositories are mocked at the module
 * boundary; the real {@link TargetsMapperImpl}, {@link FeedbackTargetResolver}, {@link
 * FeedbackAdjustmentProperties} and {@link ObjectMapper} are used (deterministic, no I/O).
 */
@ExtendWith(MockitoExtension.class)
class FeedbackTargetAdjustmentTest {

  private static final Instant NOW = Instant.parse("2026-05-22T10:00:00Z");

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;
  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private IntakeAuditRepository intakeAuditRepository;
  @Mock private DailyActivityLogRepository dailyActivityLogRepository;
  @Mock private FoodMoodJournalRepository journalRepository;
  @Mock private IngredientMappingRepository ingredientMappingRepository;
  @Mock private HealthDirectiveRepository healthDirectiveRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final FeedbackAdjustmentProperties props =
      new FeedbackAdjustmentProperties(
          new BigDecimal("0.05"), new BigDecimal("0.10"), new BigDecimal("0.20"), 1000);

  private NutritionServiceImpl service() {
    org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
        new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    DirectiveApplier directiveApplier =
        new DirectiveApplier(
            targetsRepository,
            auditRepository,
            bf.getBeanProvider(com.example.mealprep.nutrition.spi.DirectiveApplyTarget.class),
            eventPublisher,
            objectMapper,
            clock);
    IntakeAggregator intakeAggregator =
        new IntakeAggregator(intakeDayRepository, targetsRepository);
    DivergenceDetector divergenceDetector =
        new DivergenceDetector(
            intakeDayRepository,
            targetsRepository,
            org.mockito.Mockito.mock(
                com.example.mealprep.nutrition.domain.repository.NutritionDivergenceStateRepository
                    .class),
            eventPublisher,
            clock,
            new BigDecimal("0.15"),
            200);
    return new NutritionServiceImpl(
        targetsRepository,
        auditRepository,
        intakeDayRepository,
        intakeAuditRepository,
        dailyActivityLogRepository,
        journalRepository,
        ingredientMappingRepository,
        healthDirectiveRepository,
        new TargetsMapperImpl(),
        new com.example.mealprep.nutrition.api.mapper.IntakeMapperImpl(),
        new com.example.mealprep.nutrition.api.mapper.DailyActivityMapperImpl(),
        new com.example.mealprep.nutrition.api.mapper.JournalMapper() {},
        new com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper() {},
        new com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper() {},
        new IntakeKeyNormaliser(),
        new DirectiveSafetyGate(),
        directiveApplier,
        intakeAggregator,
        divergenceDetector,
        new FeedbackTargetResolver(),
        props,
        eventPublisher,
        objectMapper,
        clock);
  }

  private FeedbackTargetAdjustment adjustment(
      String target,
      AdjustmentDirection direction,
      AdjustmentMagnitude magnitude,
      BigDecimal absoluteValue) {
    return new FeedbackTargetAdjustment(
        target, direction, magnitude, absoluteValue, "feedback-" + UUID.randomUUID());
  }

  @Test
  void applyFeedbackAdjustment_missingTargets_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .applyFeedbackAdjustment(
                        userId,
                        adjustment(
                            "protein_target_g",
                            AdjustmentDirection.INCREASE,
                            AdjustmentMagnitude.MODERATE,
                            null)))
        .isInstanceOf(NutritionTargetsNotFoundException.class);
  }

  @Test
  void applyFeedbackAdjustment_macroIncreaseModerate_nudgesPlusTenPercent_writesAuditAndEvent() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate =
        NutritionTestData.targets().withUserId(userId).build(); // protein 120.0
    aggregate.setProteinTargetG(new BigDecimal("150.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(targetsRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            adjustment(
                "protein_target_g",
                AdjustmentDirection.INCREASE,
                AdjustmentMagnitude.MODERATE,
                null));

    assertThat(aggregate.getProteinTargetG()).isEqualByComparingTo("165.0"); // 150 + 10%

    ArgumentCaptor<NutritionTargetsAuditLog> audit =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getFieldPath()).isEqualTo("protein_target_g");
    assertThat(audit.getValue().getActorKind()).isEqualTo(ActorKind.FEEDBACK);
    assertThat(audit.getValue().getActorType()).isEqualTo(ActorType.AI);
    assertThat(audit.getValue().getActorUserId()).isEqualTo(userId);
    assertThat(audit.getValue().getSourceDirectiveId()).isNull();
    assertThat(audit.getValue().getOriginTrace()).startsWith("feedback-");

    ArgumentCaptor<NutritionTargetsChangedEvent> event =
        ArgumentCaptor.forClass(NutritionTargetsChangedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().changedFieldPaths()).containsExactly("protein_target_g");
  }

  @Test
  void applyFeedbackAdjustment_calorieDecreaseSmall_nudgesMinusFivePercent() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate = NutritionTestData.targets().withUserId(userId).build(); // 2000
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(targetsRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            adjustment(
                "calorie_target", AdjustmentDirection.DECREASE, AdjustmentMagnitude.SMALL, null));

    assertThat(aggregate.getDailyCalorieTarget()).isEqualTo(1900); // 2000 - 5%
  }

  @Test
  void applyFeedbackAdjustment_absoluteValue_takesPrecedenceOverMagnitude() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate = NutritionTestData.targets().withUserId(userId).build(); // 2000
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(targetsRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            adjustment(
                "calorie_target",
                AdjustmentDirection.INCREASE,
                AdjustmentMagnitude.LARGE,
                new BigDecimal("2200")));

    assertThat(aggregate.getDailyCalorieTarget()).isEqualTo(2200); // exact, ignores +20%
  }

  @Test
  void applyFeedbackAdjustment_microDecreaseModerate_nudgesExistingRow() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate =
        NutritionTestData.targets()
            .withUserId(userId)
            .withMicro("sodium_mg", new BigDecimal("2300"))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(targetsRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            adjustment(
                "micro.sodium_mg",
                AdjustmentDirection.DECREASE,
                AdjustmentMagnitude.MODERATE,
                null));

    assertThat(aggregate.getMicroTargets().get(0).getTargetValue())
        .isEqualByComparingTo("2070.0"); // 2300 - 10%
    ArgumentCaptor<NutritionTargetsAuditLog> audit =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(audit.capture());
    assertThat(audit.getValue().getFieldPath()).isEqualTo("micro.sodium_mg.target");
  }

  @Test
  void applyFeedbackAdjustment_microNotOptedIn_isNoOp_noRowNoAuditNoEvent() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));

    TargetsDto result =
        service()
            .applyFeedbackAdjustment(
                userId,
                adjustment(
                    "micro.iron_mg",
                    AdjustmentDirection.DECREASE,
                    AdjustmentMagnitude.MODERATE,
                    null));

    assertThat(result).isNotNull();
    assertThat(aggregate.getMicroTargets()).isEmpty();
    verifyNoInteractions(auditRepository);
    verify(targetsRepository, never()).saveAndFlush(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void applyFeedbackAdjustment_perMealCalorieIncrease_nudgesSlot() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate =
        NutritionTestData.targets()
            .withUserId(userId)
            .withPerMeal(MealSlot.LUNCH, 600, new BigDecimal("40.0"))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(targetsRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            adjustment(
                "per_meal.lunch.calorie_target",
                AdjustmentDirection.INCREASE,
                AdjustmentMagnitude.MODERATE,
                null));

    assertThat(aggregate.getPerMealDistribution().get(0).getCalorieTarget())
        .isEqualTo(660); // 600 + 10%
  }

  @Test
  void applyFeedbackAdjustment_unknownTarget_throwsInvalidFeedbackAdjustment() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));

    assertThatThrownBy(
            () ->
                service()
                    .applyFeedbackAdjustment(
                        userId,
                        adjustment(
                            "vibes",
                            AdjustmentDirection.INCREASE,
                            AdjustmentMagnitude.MODERATE,
                            null)))
        .isInstanceOf(InvalidFeedbackAdjustmentException.class);
    verifyNoInteractions(auditRepository);
  }

  @Test
  void applyFeedbackAdjustment_decreaseLargeBelowFloor_clampsToCalorieFloor() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate = NutritionTestData.targets().withUserId(userId).build();
    aggregate.setDailyCalorieTarget(1100); // -20% would be 880, below the 1000 floor
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(targetsRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            adjustment(
                "calorie_target", AdjustmentDirection.DECREASE, AdjustmentMagnitude.LARGE, null));

    assertThat(aggregate.getDailyCalorieTarget()).isEqualTo(1000); // clamped, never <= 0
  }

  @Test
  void applyFeedbackAdjustment_doesNotTouchEnforcementDirectionOrOverrides() {
    UUID userId = UUID.randomUUID();
    NutritionTargets aggregate = NutritionTestData.targets().withUserId(userId).build();
    aggregate.setProteinTargetG(new BigDecimal("150.0"));
    EnforcementDirection before = aggregate.getProteinDirection();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(targetsRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            adjustment(
                "protein_target_g", AdjustmentDirection.INCREASE, AdjustmentMagnitude.SMALL, null));

    assertThat(aggregate.getProteinDirection()).isEqualTo(before);
    assertThat(aggregate.getUserOverriddenDirections()).isEmpty();
  }

  @Test
  void getTargetsAuditLog_paginatesUnchanged_sanityGuardForSharedField() {
    // Guard: the new audit field path constants don't clash with the existing audit read path.
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());
    assertThat(service().getTargetsAuditLog(userId, Pageable.unpaged())).isEmpty();
  }
}
