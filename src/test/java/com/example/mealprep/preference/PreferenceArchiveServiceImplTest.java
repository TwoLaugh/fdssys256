package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.dto.ArchiveItemRequest;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.api.mapper.PreferenceArchiveMapper;
import com.example.mealprep.preference.api.mapper.PreferenceArchiveMapperImpl;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.entity.PreferenceArchiveEntry;
import com.example.mealprep.preference.domain.repository.PreferenceArchiveRepository;
import com.example.mealprep.preference.domain.service.internal.PreferenceArchiveServiceImpl;
import com.example.mealprep.preference.event.PreferenceArchivedEvent;
import com.example.mealprep.preference.event.PreferenceRePromotedEvent;
import com.example.mealprep.preference.exception.PreferenceArchiveEntryNotFoundException;
import com.example.mealprep.preference.testdata.PreferenceArchiveTestData;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Pure-Mockito unit tests for {@link PreferenceArchiveServiceImpl}. Uses the real MapStruct mapper
 * and a real Jakarta {@link Validator}; the repository, event publisher and clock are mocked. Pins
 * the archive-insert / re-promote / validation / not-found / query paths and the AFTER-write event
 * publication.
 */
@ExtendWith(MockitoExtension.class)
class PreferenceArchiveServiceImplTest {

  private static ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void initValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    validatorFactory.close();
  }

  @Mock private PreferenceArchiveRepository archiveRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final PreferenceArchiveMapper mapper = new PreferenceArchiveMapperImpl();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-22T10:00:00Z"), ZoneOffset.UTC);

  private PreferenceArchiveServiceImpl service() {
    return new PreferenceArchiveServiceImpl(
        archiveRepository, mapper, eventPublisher, validator, fixedClock);
  }

  // ---------------- archiveItem ----------------

  @Test
  void archiveItem_insertsNewRow_withRePromotedAtNull_andPublishesEvent() {
    UUID userId = UUID.randomUUID();
    ArchiveItemRequest request = PreferenceArchiveTestData.archiveRequest();
    when(archiveRepository.save(any(PreferenceArchiveEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    PreferenceArchiveEntryDto dto = service().archiveItem(userId, request);

    ArgumentCaptor<PreferenceArchiveEntry> captor =
        ArgumentCaptor.forClass(PreferenceArchiveEntry.class);
    verify(archiveRepository).save(captor.capture());
    PreferenceArchiveEntry saved = captor.getValue();
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getFieldPath()).isEqualTo(PreferenceArchiveTestData.DEFAULT_FIELD_PATH);
    assertThat(saved.getItemKey()).isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
    assertThat(saved.getEvidenceCount()).isEqualTo(4);
    assertThat(saved.getLastSignalAt()).isEqualTo(LocalDate.parse("2026-05-01"));
    assertThat(saved.getArchivedAt()).isEqualTo(Instant.parse("2026-05-22T10:00:00Z"));
    assertThat(saved.getArchivedReason()).isEqualTo(ArchiveReason.LOW_EVIDENCE);
    assertThat(saved.getRePromotedAt()).isNull();
    assertThat(saved.getItemPayload().get("item").asText())
        .isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);

    assertThat(dto.id()).isEqualTo(saved.getId());
    assertThat(dto.rePromotedAt()).isNull();
    assertThat(dto.archivedReason()).isEqualTo(ArchiveReason.LOW_EVIDENCE);

    ArgumentCaptor<PreferenceArchivedEvent> evCaptor =
        ArgumentCaptor.forClass(PreferenceArchivedEvent.class);
    verify(eventPublisher).publishEvent(evCaptor.capture());
    PreferenceArchivedEvent ev = evCaptor.getValue();
    assertThat(ev.userId()).isEqualTo(userId);
    assertThat(ev.archiveEntryId()).isEqualTo(saved.getId());
    assertThat(ev.fieldPath()).isEqualTo(PreferenceArchiveTestData.DEFAULT_FIELD_PATH);
    assertThat(ev.itemKey()).isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
    assertThat(ev.reason()).isEqualTo(ArchiveReason.LOW_EVIDENCE);
    assertThat(ev.scopeKind()).isEqualTo("taste-profile-archive");
    assertThat(ev.scopeId()).isEqualTo(userId);
    assertThat(ev.occurredAt()).isEqualTo(Instant.parse("2026-05-22T10:00:00Z"));
    assertThat(ev.traceId()).isNotNull();
  }

  @Test
  void archiveItem_blankItemKey_failsValidation_doesNotSaveOrPublish() {
    UUID userId = UUID.randomUUID();
    ArchiveItemRequest request =
        new ArchiveItemRequest(
            PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
            "  ",
            PreferenceArchiveTestData.ingredientPayload("x", 1),
            1,
            null,
            ArchiveReason.STALE);

    assertThatThrownBy(() -> service().archiveItem(userId, request))
        .isInstanceOf(ConstraintViolationException.class);

    verify(archiveRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void archiveItem_itemKeyExceeds128Chars_failsValidation() {
    UUID userId = UUID.randomUUID();
    String tooLong = "k".repeat(129);
    ArchiveItemRequest request =
        new ArchiveItemRequest(
            PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
            tooLong,
            PreferenceArchiveTestData.ingredientPayload("x", 1),
            1,
            null,
            ArchiveReason.STALE);

    assertThatThrownBy(() -> service().archiveItem(userId, request))
        .isInstanceOf(ConstraintViolationException.class);
    verify(archiveRepository, never()).save(any());
  }

  @Test
  void archiveItem_negativeEvidenceCount_failsValidation() {
    UUID userId = UUID.randomUUID();
    ArchiveItemRequest request =
        new ArchiveItemRequest(
            PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
            PreferenceArchiveTestData.DEFAULT_ITEM_KEY,
            PreferenceArchiveTestData.ingredientPayload("x", 1),
            -1,
            null,
            ArchiveReason.STALE);

    assertThatThrownBy(() -> service().archiveItem(userId, request))
        .isInstanceOf(ConstraintViolationException.class);
    verify(archiveRepository, never()).save(any());
  }

  // ---------------- markRePromoted ----------------

  @Test
  void markRePromoted_flipsRePromotedAt_onUnpromotedEntry_andPublishesEvent() {
    UUID userId = UUID.randomUUID();
    PreferenceArchiveEntry entry = PreferenceArchiveTestData.entry(userId);
    when(archiveRepository.findByUserIdAndFieldPathAndItemKeyAndRePromotedAtIsNull(
            userId,
            PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
            PreferenceArchiveTestData.DEFAULT_ITEM_KEY))
        .thenReturn(Optional.of(entry));
    when(archiveRepository.save(any(PreferenceArchiveEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    PreferenceArchiveEntryDto dto =
        service()
            .markRePromoted(
                userId,
                PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
                PreferenceArchiveTestData.DEFAULT_ITEM_KEY);

    assertThat(entry.getRePromotedAt()).isEqualTo(Instant.parse("2026-05-22T10:00:00Z"));
    assertThat(dto.rePromotedAt()).isEqualTo(Instant.parse("2026-05-22T10:00:00Z"));

    ArgumentCaptor<PreferenceRePromotedEvent> evCaptor =
        ArgumentCaptor.forClass(PreferenceRePromotedEvent.class);
    verify(eventPublisher).publishEvent(evCaptor.capture());
    PreferenceRePromotedEvent ev = evCaptor.getValue();
    assertThat(ev.userId()).isEqualTo(userId);
    assertThat(ev.archiveEntryId()).isEqualTo(entry.getId());
    assertThat(ev.fieldPath()).isEqualTo(PreferenceArchiveTestData.DEFAULT_FIELD_PATH);
    assertThat(ev.itemKey()).isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
    assertThat(ev.scopeKind()).isEqualTo("taste-profile-archive");
    assertThat(ev.scopeId()).isEqualTo(userId);
  }

  @Test
  void markRePromoted_noUnpromotedEntry_throwsNotFound_doesNotPublish() {
    UUID userId = UUID.randomUUID();
    when(archiveRepository.findByUserIdAndFieldPathAndItemKeyAndRePromotedAtIsNull(
            userId, "f", "k"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().markRePromoted(userId, "f", "k"))
        .isInstanceOfSatisfying(
            PreferenceArchiveEntryNotFoundException.class,
            ex -> {
              assertThat(ex.userId()).isEqualTo(userId);
              assertThat(ex.fieldPath()).isEqualTo("f");
              assertThat(ex.itemKey()).isEqualTo("k");
              assertThat(ex.getMessage()).contains(userId.toString());
            });

    verify(archiveRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  // ---------------- queries ----------------

  @Test
  void getArchive_delegatesToRepo_mapsToDto() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(1, 5);
    Page<PreferenceArchiveEntry> page =
        new PageImpl<>(List.of(PreferenceArchiveTestData.entry(userId)), pageable, 11L);
    when(archiveRepository.findByUserIdOrderByArchivedAtDesc(userId, pageable)).thenReturn(page);

    Page<PreferenceArchiveEntryDto> result = service().getArchive(userId, pageable);

    assertThat(result.getTotalElements()).isEqualTo(11L);
    assertThat(result.getNumber()).isEqualTo(1);
    assertThat(result.getContent())
        .singleElement()
        .satisfies(d -> assertThat(d.userId()).isEqualTo(userId));
  }

  @Test
  void getArchiveForField_filtersByPrefix() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    Page<PreferenceArchiveEntry> page =
        new PageImpl<>(List.of(PreferenceArchiveTestData.entry(userId)), pageable, 1L);
    when(archiveRepository.findByUserIdAndFieldPathStartingWithOrderByArchivedAtDesc(
            userId, "ingredientPreferences", pageable))
        .thenReturn(page);

    Page<PreferenceArchiveEntryDto> result =
        service().getArchiveForField(userId, "ingredientPreferences", pageable);

    assertThat(result.getContent()).hasSize(1);
    verify(archiveRepository)
        .findByUserIdAndFieldPathStartingWithOrderByArchivedAtDesc(
            userId, "ingredientPreferences", pageable);
  }

  @Test
  void getFullArchive_returnsAllEntriesMapped() {
    UUID userId = UUID.randomUUID();
    when(archiveRepository.findAllByUserId(userId))
        .thenReturn(List.of(PreferenceArchiveTestData.entry(userId)));

    List<PreferenceArchiveEntryDto> result = service().getFullArchive(userId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).itemKey()).isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
  }

  @Test
  void getFullArchive_empty_returnsEmptyList() {
    UUID userId = UUID.randomUUID();
    when(archiveRepository.findAllByUserId(userId)).thenReturn(List.of());

    assertThat(service().getFullArchive(userId)).isEmpty();
  }

  @Test
  void countActiveEntries_delegatesToRepo() {
    UUID userId = UUID.randomUUID();
    when(archiveRepository.countByUserIdAndRePromotedAtIsNull(userId)).thenReturn(7L);

    assertThat(service().countActiveEntries(userId)).isEqualTo(7L);
  }

  // ---------------- mapper null-safety ----------------

  @Test
  void mapper_nullEntity_returnsNull_and_emptyList_collapses() {
    assertThat(mapper.toDto(null)).isNull();
    assertThat(mapper.toDtos(null)).isEmpty();
    assertThat(mapper.toDtos(List.of())).isEmpty();
  }
}
