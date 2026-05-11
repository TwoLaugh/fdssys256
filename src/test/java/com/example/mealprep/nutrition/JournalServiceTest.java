package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.api.dto.UpsertFoodMoodEntryRequest;
import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.FoodMoodJournalEntry;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.repository.DailyActivityLogRepository;
import com.example.mealprep.nutrition.domain.repository.FoodMoodJournalRepository;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeAuditRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import com.example.mealprep.nutrition.domain.service.internal.NutritionServiceImpl;
import com.example.mealprep.nutrition.event.FoodMoodEntryWrittenEvent;
import com.example.mealprep.nutrition.exception.JournalEntryNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for the food/mood journal slice of {@link NutritionServiceImpl}. Repositories +
 * publisher are mocked; the real {@link JournalMapper} default-method impl is used.
 */
@ExtendWith(MockitoExtension.class)
class JournalServiceTest {

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;
  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private IntakeAuditRepository intakeAuditRepository;
  @Mock private DailyActivityLogRepository dailyActivityLogRepository;
  @Mock private FoodMoodJournalRepository journalRepository;
  @Mock private IngredientMappingRepository ingredientMappingRepository;

  @Mock
  private com.example.mealprep.nutrition.domain.repository.HealthDirectiveRepository
      healthDirectiveRepository;

  @Mock private ApplicationEventPublisher eventPublisher;

  private final TargetsMapper targetsMapper =
      new com.example.mealprep.nutrition.api.mapper.TargetsMapperImpl();
  private final IntakeMapper intakeMapper =
      new com.example.mealprep.nutrition.api.mapper.IntakeMapperImpl();
  private final DailyActivityMapper dailyActivityMapper =
      new com.example.mealprep.nutrition.api.mapper.DailyActivityMapperImpl();
  private final JournalMapper journalMapper = new JournalMapper() {};
  private final IngredientMappingMapper ingredientMappingMapper = new IngredientMappingMapper() {};
  private final com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper
      healthDirectiveMapper =
          new com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper() {};
  private final IntakeKeyNormaliser intakeKeyNormaliser = new IntakeKeyNormaliser();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);

  private NutritionServiceImpl service() {
    org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
        new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    org.springframework.beans.factory.ObjectProvider<
            com.example.mealprep.nutrition.spi.DirectiveApplyTarget>
        emptyProvider =
            bf.getBeanProvider(com.example.mealprep.nutrition.spi.DirectiveApplyTarget.class);
    com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier directiveApplier =
        new com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier(
            targetsRepository,
            auditRepository,
            emptyProvider,
            eventPublisher,
            objectMapper,
            fixedClock);
    return new NutritionServiceImpl(
        targetsRepository,
        auditRepository,
        intakeDayRepository,
        intakeAuditRepository,
        dailyActivityLogRepository,
        journalRepository,
        ingredientMappingRepository,
        healthDirectiveRepository,
        targetsMapper,
        intakeMapper,
        dailyActivityMapper,
        journalMapper,
        ingredientMappingMapper,
        healthDirectiveMapper,
        intakeKeyNormaliser,
        new com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate(),
        directiveApplier,
        eventPublisher,
        objectMapper,
        fixedClock);
  }

  // ---------------- upsert (create) ----------------

  @Test
  void upsert_persistsRow_publishesCreatedEvent() {
    UUID userId = UUID.randomUUID();
    LocalDate onDate = LocalDate.parse("2026-05-09");
    UpsertFoodMoodEntryRequest req =
        new UpsertFoodMoodEntryRequest(
            onDate, MealSlot.LUNCH, "felt good", Instant.parse("2026-05-09T12:00:00Z"), 0L);

    when(journalRepository.saveAndFlush(any(FoodMoodJournalEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    FoodMoodEntryDto out = service().upsertJournalEntry(userId, req);

    assertThat(out.userId()).isEqualTo(userId);
    assertThat(out.onDate()).isEqualTo(onDate);
    assertThat(out.mealSlot()).isEqualTo(MealSlot.LUNCH);
    assertThat(out.journalEntry()).isEqualTo("felt good");

    ArgumentCaptor<FoodMoodEntryWrittenEvent> evt =
        ArgumentCaptor.forClass(FoodMoodEntryWrittenEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().action().name()).isEqualTo("CREATED");
    assertThat(evt.getValue().userId()).isEqualTo(userId);
  }

  // ---------------- update ----------------

  @Test
  void update_returns404_whenMissing() {
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    when(journalRepository.findById(entryId)).thenReturn(Optional.empty());
    UpsertFoodMoodEntryRequest req =
        new UpsertFoodMoodEntryRequest(
            LocalDate.parse("2026-05-09"), null, "x", Instant.parse("2026-05-09T12:00:00Z"), 0L);

    assertThatThrownBy(() -> service().updateJournalEntry(userId, entryId, req))
        .isInstanceOf(JournalEntryNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void update_returns404_whenOwnedByOtherUser() {
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID otherUser = UUID.randomUUID();
    FoodMoodJournalEntry e =
        FoodMoodJournalEntry.builder()
            .id(entryId)
            .userId(otherUser)
            .onDate(LocalDate.parse("2026-05-09"))
            .journalEntry("x")
            .loggedAt(Instant.parse("2026-05-09T12:00:00Z"))
            .optimisticVersion(0L)
            .build();
    when(journalRepository.findById(entryId)).thenReturn(Optional.of(e));
    UpsertFoodMoodEntryRequest req =
        new UpsertFoodMoodEntryRequest(
            LocalDate.parse("2026-05-09"), null, "x", Instant.parse("2026-05-09T12:00:00Z"), 0L);

    assertThatThrownBy(() -> service().updateJournalEntry(userId, entryId, req))
        .isInstanceOf(JournalEntryNotFoundException.class);
  }

  @Test
  void update_returns404_whenWrongOnDate() {
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    FoodMoodJournalEntry e =
        FoodMoodJournalEntry.builder()
            .id(entryId)
            .userId(userId)
            .onDate(LocalDate.parse("2026-05-09"))
            .journalEntry("x")
            .loggedAt(Instant.parse("2026-05-09T12:00:00Z"))
            .optimisticVersion(0L)
            .build();
    when(journalRepository.findById(entryId)).thenReturn(Optional.of(e));
    UpsertFoodMoodEntryRequest req =
        new UpsertFoodMoodEntryRequest(
            LocalDate.parse("2026-05-10"), null, "x", Instant.parse("2026-05-10T12:00:00Z"), 0L);

    assertThatThrownBy(() -> service().updateJournalEntry(userId, entryId, req))
        .isInstanceOf(JournalEntryNotFoundException.class);
  }

  @Test
  void update_returns409_whenStaleExpectedVersion() {
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    FoodMoodJournalEntry e =
        FoodMoodJournalEntry.builder()
            .id(entryId)
            .userId(userId)
            .onDate(LocalDate.parse("2026-05-09"))
            .journalEntry("x")
            .loggedAt(Instant.parse("2026-05-09T12:00:00Z"))
            .optimisticVersion(3L)
            .build();
    when(journalRepository.findById(entryId)).thenReturn(Optional.of(e));
    UpsertFoodMoodEntryRequest req =
        new UpsertFoodMoodEntryRequest(
            LocalDate.parse("2026-05-09"), null, "x", Instant.parse("2026-05-09T12:00:00Z"), 1L);

    assertThatThrownBy(() -> service().updateJournalEntry(userId, entryId, req))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void update_happyPath_publishesUpdatedEvent() {
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    FoodMoodJournalEntry existing =
        FoodMoodJournalEntry.builder()
            .id(entryId)
            .userId(userId)
            .onDate(LocalDate.parse("2026-05-09"))
            .mealSlot(MealSlot.LUNCH)
            .journalEntry("old")
            .loggedAt(Instant.parse("2026-05-09T11:00:00Z"))
            .optimisticVersion(2L)
            .build();
    when(journalRepository.findById(entryId)).thenReturn(Optional.of(existing));
    when(journalRepository.saveAndFlush(any(FoodMoodJournalEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpsertFoodMoodEntryRequest req =
        new UpsertFoodMoodEntryRequest(
            LocalDate.parse("2026-05-09"),
            MealSlot.DINNER,
            "new entry",
            Instant.parse("2026-05-09T19:00:00Z"),
            2L);

    FoodMoodEntryDto out = service().updateJournalEntry(userId, entryId, req);
    assertThat(out.journalEntry()).isEqualTo("new entry");
    assertThat(out.mealSlot()).isEqualTo(MealSlot.DINNER);

    ArgumentCaptor<FoodMoodEntryWrittenEvent> evt =
        ArgumentCaptor.forClass(FoodMoodEntryWrittenEvent.class);
    verify(eventPublisher, times(1)).publishEvent(evt.capture());
    assertThat(evt.getValue().action().name()).isEqualTo("UPDATED");
  }

  // ---------------- delete ----------------

  @Test
  void delete_returns404_whenMissing() {
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    when(journalRepository.findById(entryId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().deleteJournalEntry(userId, entryId))
        .isInstanceOf(JournalEntryNotFoundException.class);
    verify(journalRepository, never()).delete(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void delete_happyPath_publishesDeletedEvent() {
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    FoodMoodJournalEntry existing =
        FoodMoodJournalEntry.builder()
            .id(entryId)
            .userId(userId)
            .onDate(LocalDate.parse("2026-05-09"))
            .mealSlot(MealSlot.LUNCH)
            .journalEntry("x")
            .loggedAt(Instant.parse("2026-05-09T11:00:00Z"))
            .optimisticVersion(0L)
            .build();
    when(journalRepository.findById(entryId)).thenReturn(Optional.of(existing));

    service().deleteJournalEntry(userId, entryId);

    verify(journalRepository).delete(existing);
    ArgumentCaptor<FoodMoodEntryWrittenEvent> evt =
        ArgumentCaptor.forClass(FoodMoodEntryWrittenEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().action().name()).isEqualTo("DELETED");
    assertThat(evt.getValue().entryId()).isEqualTo(entryId);
  }

  // ---------------- feedback context ----------------

  @Test
  void getJournalEntriesForFeedbackContext_delegatesToTop20Query() {
    UUID userId = UUID.randomUUID();
    when(journalRepository.findTop20ByUserIdOrderByLoggedAtDesc(userId)).thenReturn(List.of());

    List<FoodMoodEntryDto> out = service().getJournalEntriesForFeedbackContext(userId);

    assertThat(out).isEmpty();
    verify(journalRepository).findTop20ByUserIdOrderByLoggedAtDesc(userId);
  }
}
