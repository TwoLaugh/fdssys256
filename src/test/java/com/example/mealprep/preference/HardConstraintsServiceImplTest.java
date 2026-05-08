package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.dto.HardConstraintsAuditEntryDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.api.mapper.HardConstraintsMapper;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardConstraintsAuditLog;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.service.internal.PreferenceServiceImpl;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for {@link PreferenceServiceImpl}. Repositories and event publisher are mocked at the
 * module boundary; the real {@link HardConstraintsMapper} (MapStruct-generated) and a real {@link
 * ObjectMapper} are used because they are deterministic, no-I/O, and central to behaviour.
 */
@ExtendWith(MockitoExtension.class)
class HardConstraintsServiceImplTest {

  @Mock private HardConstraintsRepository hardConstraintsRepository;
  @Mock private HardConstraintsAuditLogRepository auditLogRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final HardConstraintsMapper mapper =
      new com.example.mealprep.preference.api.mapper.HardConstraintsMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);

  private PreferenceServiceImpl service() {
    return new PreferenceServiceImpl(
        hardConstraintsRepository,
        auditLogRepository,
        mapper,
        eventPublisher,
        objectMapper,
        fixedClock);
  }

  // ---------------- getHardConstraints ----------------

  @Test
  void getHardConstraints_whenAggregateExists_returnsDto() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanuts")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    Optional<HardConstraintsDto> result = service().getHardConstraints(userId);

    assertThat(result).isPresent();
    assertThat(result.get().userId()).isEqualTo(userId);
    assertThat(result.get().allergies()).containsExactly("peanuts");
    assertThat(result.get().dietaryIdentity().base()).isEqualTo("omnivore");
  }

  @Test
  void getHardConstraints_whenAggregateMissing_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId)).thenReturn(Optional.empty());

    Optional<HardConstraintsDto> result = service().getHardConstraints(userId);

    assertThat(result).isEmpty();
  }

  // ---------------- getHardConstraintsAuditLog ----------------

  @Test
  void getHardConstraintsAuditLog_whenNoAggregate_returnsEmptyPage() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    Page<HardConstraintsAuditEntryDto> result =
        service().getHardConstraintsAuditLog(userId, PageRequest.of(0, 10));

    assertThat(result.getTotalElements()).isZero();
    assertThat(result.getContent()).isEmpty();
    verifyNoInteractions(auditLogRepository);
  }

  @Test
  void getHardConstraintsAuditLog_returnsAuditEntriesNewestFirst() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    when(hardConstraintsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));

    HardConstraintsAuditLog entry =
        new HardConstraintsAuditLog(
            UUID.randomUUID(),
            aggregate.getId(),
            userId,
            "allergies",
            objectMapper.valueToTree(List.of()),
            objectMapper.valueToTree(List.of("peanuts")),
            Instant.parse("2026-05-08T09:00:00Z"));
    Pageable pageable = PageRequest.of(0, 10);
    when(auditLogRepository.findByHardConstraintsIdOrderByOccurredAtDesc(
            aggregate.getId(), pageable))
        .thenReturn(new PageImpl<>(List.of(entry), pageable, 1));

    Page<HardConstraintsAuditEntryDto> result =
        service().getHardConstraintsAuditLog(userId, pageable);

    assertThat(result.getTotalElements()).isEqualTo(1L);
    assertThat(result.getContent()).hasSize(1);
    HardConstraintsAuditEntryDto dto = result.getContent().get(0);
    assertThat(dto.fieldChanged()).isEqualTo("allergies");
    assertThat(dto.actorUserId()).isEqualTo(userId);
    assertThat(dto.newValueJson().get(0).asText()).isEqualTo("peanuts");
  }

  // ---------------- initialiseHardConstraints ----------------

  @Test
  void initialiseHardConstraints_whenAbsent_createsRowWithSensibleDefaults() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(hardConstraintsRepository.save(any(HardConstraints.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    HardConstraintsDto result = service().initialiseHardConstraints(userId);

    ArgumentCaptor<HardConstraints> saveCaptor = ArgumentCaptor.forClass(HardConstraints.class);
    verify(hardConstraintsRepository).save(saveCaptor.capture());
    HardConstraints saved = saveCaptor.getValue();
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getAllergies()).isEmpty();
    assertThat(saved.getMedicalDiets()).isEmpty();
    assertThat(saved.getDietaryIdentityBase()).isEqualTo("omnivore");
    assertThat(saved.getDietaryIdentityLabel()).isNull();
    assertThat(saved.getExceptions()).isEmpty();
    assertThat(saved.getIntolerances()).isEmpty();
    assertThat(saved.getAgeRestrictions()).isEmpty();

    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.dietaryIdentity().base()).isEqualTo("omnivore");
  }

  @Test
  void initialiseHardConstraints_whenAlreadyPresent_returnsExistingWithoutCreating() {
    UUID userId = UUID.randomUUID();
    HardConstraints existing =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanuts")
            .build();
    when(hardConstraintsRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

    HardConstraintsDto result = service().initialiseHardConstraints(userId);

    verify(hardConstraintsRepository, never()).save(any(HardConstraints.class));
    assertThat(result.id()).isEqualTo(existing.getId());
    assertThat(result.allergies()).containsExactly("peanuts");
  }

  // ---------------- updateHardConstraints ----------------

  @Test
  void updateHardConstraints_whenAggregateMissing_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId)).thenReturn(Optional.empty());

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest().withExpectedVersion(0L).build();

    assertThatThrownBy(() -> service().updateHardConstraints(userId, request, userId))
        .isInstanceOf(HardConstraintsNotFoundException.class);

    verifyNoInteractions(eventPublisher, auditLogRepository);
  }

  @Test
  void updateHardConstraints_whenVersionMismatch_throwsOptimisticLock() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate.setVersion(3L);
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest().withExpectedVersion(2L).build();

    assertThatThrownBy(() -> service().updateHardConstraints(userId, request, userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verifyNoInteractions(eventPublisher, auditLogRepository);
    verify(hardConstraintsRepository, never()).save(any());
  }

  @Test
  void updateHardConstraints_writesOneAuditRowPerChangedField_andPublishesEventWithFieldNames() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanuts")
            .withDietaryIdentityBase("omnivore")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(hardConstraintsRepository.saveAndFlush(any(HardConstraints.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts", "shellfish")
            .withDietaryIdentity(HardConstraintsTestData.vegetarianIdentityWithFishOnWeekends())
            .withMedicalDiets("low_sodium")
            .withIntolerances(HardConstraintsTestData.lactoseIntolerance())
            .withAgeRestrictions(HardConstraintsTestData.noWholeNutsRestriction())
            .withExpectedVersion(0L)
            .build();

    HardConstraintsDto result = service().updateHardConstraints(userId, request, userId);

    ArgumentCaptor<HardConstraintsAuditLog> auditCaptor =
        ArgumentCaptor.forClass(HardConstraintsAuditLog.class);
    verify(auditLogRepository, times(7)).save(auditCaptor.capture());
    List<String> fieldsLogged =
        auditCaptor.getAllValues().stream().map(HardConstraintsAuditLog::getFieldChanged).toList();
    assertThat(fieldsLogged)
        .containsExactlyInAnyOrder(
            "allergies",
            "dietaryIdentityBase",
            "dietaryIdentityLabel",
            "dietaryIdentityExceptions",
            "medicalDiets",
            "intolerances",
            "ageRestrictions");

    HardConstraintsAuditLog allergiesEntry =
        auditCaptor.getAllValues().stream()
            .filter(a -> "allergies".equals(a.getFieldChanged()))
            .findFirst()
            .orElseThrow();
    assertThat(allergiesEntry.getActorUserId()).isEqualTo(userId);
    assertThat(allergiesEntry.getOccurredAt()).isEqualTo(Instant.parse("2026-05-08T10:00:00Z"));
    assertThat(allergiesEntry.getPreviousValueJson().toString()).isEqualTo("[\"peanuts\"]");
    assertThat(allergiesEntry.getNewValueJson().toString())
        .isEqualTo("[\"peanuts\",\"shellfish\"]");

    ArgumentCaptor<HardConstraintsUpdatedEvent> eventCaptor =
        ArgumentCaptor.forClass(HardConstraintsUpdatedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().userId()).isEqualTo(userId);
    assertThat(eventCaptor.getValue().fieldsChanged())
        .containsExactlyInAnyOrder(
            "allergies",
            "dietaryIdentityBase",
            "dietaryIdentityLabel",
            "dietaryIdentityExceptions",
            "medicalDiets",
            "intolerances",
            "ageRestrictions");

    assertThat(result.allergies()).containsExactly("peanuts", "shellfish");
    assertThat(result.dietaryIdentity().base()).isEqualTo("vegetarian");
    assertThat(result.dietaryIdentity().exceptions()).hasSize(1);
    assertThat(result.intolerances()).hasSize(1);
    assertThat(result.ageRestrictions()).hasSize(1);
  }

  @Test
  void updateHardConstraints_whenNoFieldsChanged_writesNoAuditRows_andPublishesNoEvent() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanuts")
            .withDietaryIdentityBase("omnivore")
            .withMedicalDiets("low_sodium")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    UpdateHardConstraintsRequest sameValuesRequest =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts")
            .withMedicalDiets("low_sodium")
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withExpectedVersion(0L)
            .build();

    HardConstraintsDto result = service().updateHardConstraints(userId, sameValuesRequest, userId);

    verifyNoInteractions(eventPublisher);
    verify(auditLogRepository, never()).save(any());
    verify(hardConstraintsRepository, never()).save(any(HardConstraints.class));
    assertThat(result.allergies()).containsExactly("peanuts");
  }

  @Test
  void updateHardConstraints_whenOnlyAllergiesChange_writesOneAuditRowOnly() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanuts")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(hardConstraintsRepository.saveAndFlush(any(HardConstraints.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts", "shellfish")
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withExpectedVersion(0L)
            .build();

    service().updateHardConstraints(userId, request, userId);

    ArgumentCaptor<HardConstraintsAuditLog> auditCaptor =
        ArgumentCaptor.forClass(HardConstraintsAuditLog.class);
    verify(auditLogRepository, times(1)).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFieldChanged()).isEqualTo("allergies");

    ArgumentCaptor<HardConstraintsUpdatedEvent> eventCaptor =
        ArgumentCaptor.forClass(HardConstraintsUpdatedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().fieldsChanged()).containsExactly("allergies");
  }
}
