package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.api.mapper.LifestyleConfigMapper;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.entity.LifestyleConfig;
import com.example.mealprep.preference.domain.entity.LifestyleConfigAuditLog;
import com.example.mealprep.preference.domain.repository.LifestyleConfigAuditLogRepository;
import com.example.mealprep.preference.domain.repository.LifestyleConfigRepository;
import com.example.mealprep.preference.domain.service.internal.LifestyleConfigServiceImpl;
import com.example.mealprep.preference.event.LifestyleConfigChangedEvent;
import com.example.mealprep.preference.event.LifestyleConfigInitialisedEvent;
import com.example.mealprep.preference.exception.LifestyleConfigNotFoundException;
import com.example.mealprep.preference.testdata.LifestyleConfigTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
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
 * Unit test for {@link LifestyleConfigServiceImpl}. Repositories and event publisher are mocked at
 * the module boundary; the real {@link LifestyleConfigMapper} (MapStruct-generated implementation)
 * and a real {@link ObjectMapper} are used because they are deterministic, no-I/O, and central to
 * behaviour. Mirrors {@code HardConstraintsServiceImplTest}.
 */
@ExtendWith(MockitoExtension.class)
class LifestyleConfigServiceImplTest {

  @Mock private LifestyleConfigRepository repository;
  @Mock private LifestyleConfigAuditLogRepository auditRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final LifestyleConfigMapper mapper =
      new com.example.mealprep.preference.api.mapper.LifestyleConfigMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);

  private LifestyleConfigServiceImpl service() {
    return new LifestyleConfigServiceImpl(
        repository, auditRepository, mapper, eventPublisher, objectMapper, fixedClock);
  }

  private LifestyleConfig aggregate(UUID userId, LifestyleConfigDocument doc, long version) {
    return LifestyleConfig.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .document(doc)
        .lastReviewPromptAt(null)
        .optimisticVersion(version)
        .createdAt(Instant.parse("2026-05-01T10:00:00Z"))
        .updatedAt(Instant.parse("2026-05-01T10:00:00Z"))
        .build();
  }

  // ---------------- getLifestyleConfig ----------------

  @Test
  void getLifestyleConfig_present_returnsDto() {
    UUID userId = UUID.randomUUID();
    LifestyleConfig agg = aggregate(userId, LifestyleConfigTestData.fullDocument(), 0L);
    when(repository.findByUserId(userId)).thenReturn(Optional.of(agg));

    Optional<LifestyleConfigDto> result = service().getLifestyleConfig(userId);

    assertThat(result).isPresent();
    assertThat(result.get().userId()).isEqualTo(userId);
    assertThat(result.get().document().pantryTracking().enabled()).isTrue();
  }

  @Test
  void getLifestyleConfig_missing_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(repository.findByUserId(userId)).thenReturn(Optional.empty());
    assertThat(service().getLifestyleConfig(userId)).isEmpty();
  }

  // ---------------- initialise ----------------

  @Test
  void initialise_whenAbsent_savesAndWritesSummaryAuditRow_andPublishesInitialisedEvent() {
    UUID userId = UUID.randomUUID();
    when(repository.findByUserId(userId)).thenReturn(Optional.empty());
    when(repository.save(any(LifestyleConfig.class)))
        .thenAnswer(inv -> inv.getArgument(0, LifestyleConfig.class));

    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L);
    LifestyleConfigDto dto = service().initialise(userId, req);

    assertThat(dto.userId()).isEqualTo(userId);

    ArgumentCaptor<LifestyleConfigAuditLog> rowCap =
        ArgumentCaptor.forClass(LifestyleConfigAuditLog.class);
    verify(auditRepository).save(rowCap.capture());
    assertThat(rowCap.getValue().getFieldPath()).isEqualTo("*");

    verify(eventPublisher).publishEvent(any(LifestyleConfigInitialisedEvent.class));
  }

  @Test
  void initialise_whenPresent_isIdempotent_returnsExisting_andDoesNotWriteAuditOrEvent() {
    UUID userId = UUID.randomUUID();
    LifestyleConfig existing = aggregate(userId, LifestyleConfigTestData.fullDocument(), 3L);
    when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));

    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L);
    LifestyleConfigDto dto = service().initialise(userId, req);

    assertThat(dto.optimisticVersion()).isEqualTo(3L);
    verifyNoInteractions(auditRepository, eventPublisher);
  }

  // ---------------- update ----------------

  @Test
  void update_whenAggregateMissing_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(repository.findByUserId(userId)).thenReturn(Optional.empty());
    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L);

    assertThatThrownBy(() -> service().update(userId, req, userId))
        .isInstanceOf(LifestyleConfigNotFoundException.class);
  }

  @Test
  void update_withStaleExpectedVersion_throwsOptimisticLocking() {
    UUID userId = UUID.randomUUID();
    LifestyleConfig agg = aggregate(userId, LifestyleConfigTestData.fullDocument(), 5L);
    when(repository.findByUserId(userId)).thenReturn(Optional.of(agg));

    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L);

    assertThatThrownBy(() -> service().update(userId, req, userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void update_whenOnlyBatchCookingChanges_writesExactlyOneAuditRow_withCorrectFieldPath() {
    UUID userId = UUID.randomUUID();
    LifestyleConfigDocument before = LifestyleConfigTestData.fullDocument();
    LifestyleConfig agg = aggregate(userId, before, 0L);
    when(repository.findByUserId(userId)).thenReturn(Optional.of(agg));
    when(repository.saveAndFlush(any(LifestyleConfig.class))).thenAnswer(inv -> inv.getArgument(0));

    // Mutate only the batchCooking section.
    LifestyleConfigDocument after =
        new LifestyleConfigDocument(
            before.mealStructure(),
            before.mealTiming(),
            before.noveltyTolerance(),
            before.cookingContexts(),
            new LifestyleConfigDocument.BatchCooking(
                before.batchCooking().prepDays(),
                java.util.Map.of("default", 99),
                before.batchCooking().leftoverStrategy(),
                before.batchCooking().freezerTolerance(),
                before.batchCooking().sameProteinSameDay(),
                before.batchCooking().parallelCookingTolerance()),
            before.reheatingPreferences(),
            before.eatingContext(),
            before.seasonalPreferences(),
            before.mealTypePreferences(),
            before.accompaniments(),
            before.groceryQualityPreferences(),
            before.pantryTracking());

    UpdateLifestyleConfigRequest req = LifestyleConfigTestData.updateRequest(after, 0L);
    service().update(userId, req, userId);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LifestyleConfigAuditLog>> capt = ArgumentCaptor.forClass(List.class);
    verify(auditRepository).saveAll(capt.capture());
    List<LifestyleConfigAuditLog> rows = capt.getValue();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("batchCooking");
  }

  @Test
  void update_noOpReplacement_writesNoAuditRowsAndPublishesNoEvent() {
    UUID userId = UUID.randomUUID();
    LifestyleConfigDocument doc = LifestyleConfigTestData.fullDocument();
    LifestyleConfig agg = aggregate(userId, doc, 0L);
    when(repository.findByUserId(userId)).thenReturn(Optional.of(agg));

    UpdateLifestyleConfigRequest req = LifestyleConfigTestData.updateRequest(doc, 0L);
    service().update(userId, req, userId);

    verify(auditRepository, never()).saveAll(anyList());
    verify(eventPublisher, never()).publishEvent(any(LifestyleConfigChangedEvent.class));
  }

  @Test
  void update_changes_publishesChangedEventWithSectionsSet() {
    UUID userId = UUID.randomUUID();
    LifestyleConfigDocument before = LifestyleConfigTestData.fullDocument();
    LifestyleConfig agg = aggregate(userId, before, 0L);
    when(repository.findByUserId(userId)).thenReturn(Optional.of(agg));
    when(repository.saveAndFlush(any(LifestyleConfig.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(
            LifestyleConfigTestData.fullDocumentWithPantryDisabled(), 0L);
    service().update(userId, req, userId);

    ArgumentCaptor<LifestyleConfigChangedEvent> eventCap =
        ArgumentCaptor.forClass(LifestyleConfigChangedEvent.class);
    verify(eventPublisher).publishEvent(eventCap.capture());
    assertThat(eventCap.getValue().changedSections()).containsExactly("pantryTracking");
    assertThat(eventCap.getValue().userId()).isEqualTo(userId);
    assertThat(eventCap.getValue().scopeKind()).isEqualTo("lifestyle-config");
  }

  // ---------------- markReviewed ----------------

  @Test
  void markReviewed_whenAggregateExists_clearsLastReviewPromptAt() {
    UUID userId = UUID.randomUUID();
    LifestyleConfig agg = aggregate(userId, LifestyleConfigTestData.fullDocument(), 0L);
    agg.setLastReviewPromptAt(Instant.parse("2026-04-01T00:00:00Z"));
    when(repository.findByUserId(userId)).thenReturn(Optional.of(agg));
    when(repository.saveAndFlush(any(LifestyleConfig.class))).thenAnswer(inv -> inv.getArgument(0));

    LifestyleConfigDto dto = service().markReviewed(userId);

    assertThat(dto.lastReviewPromptAt()).isNull();
    assertThat(agg.getLastReviewPromptAt()).isNull();
  }

  @Test
  void markReviewed_whenAggregateMissing_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(repository.findByUserId(userId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().markReviewed(userId))
        .isInstanceOf(LifestyleConfigNotFoundException.class);
  }
}
