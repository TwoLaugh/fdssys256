package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.UpdateHouseholdSettingsRequest;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsMapper;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.entity.HouseholdSettings;
import com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import com.example.mealprep.household.domain.repository.HouseholdMemberRepository;
import com.example.mealprep.household.domain.repository.HouseholdRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsAuditLogRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsRepository;
import com.example.mealprep.household.domain.service.internal.HouseholdServiceImpl;
import com.example.mealprep.household.domain.service.internal.HouseholdSettingsDiffer;
import com.example.mealprep.household.domain.service.internal.SlotConfigurationResolver;
import com.example.mealprep.household.event.HouseholdSettingsChangedEvent;
import com.example.mealprep.household.exception.HouseholdSettingsNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Unit test for the {@code updateSettings}/{@code getSettings} branches of {@link
 * HouseholdServiceImpl}. Covers role enforcement, version mismatch, and the no-op-replace
 * short-circuit (no audit rows + no event when changedFieldPaths is empty).
 */
@ExtendWith(MockitoExtension.class)
class HouseholdSettingsServiceTest {

  @Mock private HouseholdRepository householdRepository;
  @Mock private HouseholdMemberRepository householdMemberRepository;
  @Mock private HouseholdSettingsRepository householdSettingsRepository;
  @Mock private HouseholdSettingsAuditLogRepository householdSettingsAuditLogRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final HouseholdMapper mapper =
      new com.example.mealprep.household.api.mapper.HouseholdMapperImpl();
  private final HouseholdSettingsMapper settingsMapper =
      new com.example.mealprep.household.api.mapper.HouseholdSettingsMapperImpl();
  private final HouseholdSettingsAuditMapper settingsAuditMapper =
      new com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapperImpl();
  private final HouseholdSettingsDiffer differ = new HouseholdSettingsDiffer(new ObjectMapper());
  private final SlotConfigurationResolver slotConfigurationResolver =
      new SlotConfigurationResolver();

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneOffset.UTC);

  private HouseholdServiceImpl service() {
    return new HouseholdServiceImpl(
        householdRepository,
        householdMemberRepository,
        householdSettingsRepository,
        householdSettingsAuditLogRepository,
        mapper,
        settingsMapper,
        settingsAuditMapper,
        differ,
        slotConfigurationResolver,
        eventPublisher,
        fixedClock);
  }

  @Test
  void updateSettings_whenCallerNotAMember_throwsInsufficientRole() {
    UUID householdId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.empty());

    UpdateHouseholdSettingsRequest req =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);
    assertThatThrownBy(() -> service().updateSettings(householdId, actorUserId, req))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
    verifyNoInteractions(householdSettingsRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void updateSettings_whenCallerIsNonPrimary_throwsInsufficientRole() {
    UUID householdId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.member).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(member));

    UpdateHouseholdSettingsRequest req =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);
    assertThatThrownBy(() -> service().updateSettings(householdId, actorUserId, req))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
    verifyNoInteractions(householdSettingsRepository);
  }

  @Test
  void updateSettings_whenSettingsRowMissing_throwsNotFound() {
    UUID householdId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(member));
    when(householdSettingsRepository.findByHouseholdId(householdId)).thenReturn(Optional.empty());

    UpdateHouseholdSettingsRequest req =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);
    assertThatThrownBy(() -> service().updateSettings(householdId, actorUserId, req))
        .isInstanceOf(HouseholdSettingsNotFoundException.class);
  }

  @Test
  void updateSettings_whenExpectedVersionStale_throwsOptimisticLock() {
    UUID householdId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(member));

    HouseholdSettings existing =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .document(HouseholdTestData.defaultDocument())
            .version(7)
            .build();
    when(householdSettingsRepository.findByHouseholdId(householdId))
        .thenReturn(Optional.of(existing));

    UpdateHouseholdSettingsRequest req =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 3L);
    assertThatThrownBy(() -> service().updateSettings(householdId, actorUserId, req))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verify(householdSettingsRepository, never()).saveAndFlush(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void updateSettings_whenDocumentIdentical_returnsExisting_andSkipsAuditAndEvent() {
    UUID householdId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(member));

    HouseholdSettings existing =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .document(HouseholdTestData.defaultDocument())
            .version(0)
            .build();
    when(householdSettingsRepository.findByHouseholdId(householdId))
        .thenReturn(Optional.of(existing));

    UpdateHouseholdSettingsRequest req =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);
    HouseholdSettingsDto result = service().updateSettings(householdId, actorUserId, req);

    assertThat(result.id()).isEqualTo(existing.getId());
    verify(householdSettingsRepository, never()).saveAndFlush(any());
    verify(householdSettingsAuditLogRepository, never()).saveAll(anyList());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void updateSettings_whenDocumentChanges_writesAuditRows_andPublishesEventOnce() {
    UUID householdId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(member));

    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    HouseholdSettings existing =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .document(prev)
            .version(0)
            .build();
    when(householdSettingsRepository.findByHouseholdId(householdId))
        .thenReturn(Optional.of(existing));
    when(householdSettingsRepository.saveAndFlush(any(HouseholdSettings.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    Map<SlotKind, SlotDefault> nextSlots = new LinkedHashMap<>(prev.slotDefaults());
    nextSlots.put(SlotKind.dinner, new SlotDefault(false, 2, 60));
    HouseholdSettingsDocument next =
        new HouseholdSettingsDocument(
            nextSlots, prev.customSlots(), prev.defaultHeadcount(), prev.scheduling());

    UpdateHouseholdSettingsRequest req = new UpdateHouseholdSettingsRequest(next, 0L);
    HouseholdSettingsDto result = service().updateSettings(householdId, actorUserId, req);

    assertThat(result.householdId()).isEqualTo(householdId);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.List<HouseholdSettingsAuditLog>> rowsCaptor =
        ArgumentCaptor.forClass(java.util.List.class);
    verify(householdSettingsAuditLogRepository).saveAll(rowsCaptor.capture());
    assertThat(rowsCaptor.getValue()).hasSize(3);
    ArgumentCaptor<HouseholdSettingsChangedEvent> eventCaptor =
        ArgumentCaptor.forClass(HouseholdSettingsChangedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    HouseholdSettingsChangedEvent event = eventCaptor.getValue();
    assertThat(event.householdId()).isEqualTo(householdId);
    assertThat(event.settingsId()).isEqualTo(existing.getId());
    assertThat(event.changedFieldPaths())
        .containsExactly(
            "slotDefaults.dinner.shared",
            "slotDefaults.dinner.headcount",
            "slotDefaults.dinner.timeBudgetMin");
    assertThat(event.scopeKind()).isEqualTo("household");
    assertThat(event.scopeId()).isEqualTo(householdId);
  }

  @Test
  void getSettings_whenCallerNotMember_returnsEmpty() {
    UUID householdId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service().getSettings(householdId, userId)).isEmpty();
    verifyNoInteractions(householdSettingsRepository);
  }

  @Test
  void getSettings_whenCallerMember_returnsDocument() {
    UUID householdId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(userId).withRole(HouseholdRole.primary).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(userId)).thenReturn(Optional.of(member));

    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .document(HouseholdTestData.defaultDocument())
            .version(0)
            .build();
    when(householdSettingsRepository.findByHouseholdId(householdId))
        .thenReturn(Optional.of(settings));

    Optional<HouseholdSettingsDto> result = service().getSettings(householdId, userId);
    assertThat(result).isPresent();
    assertThat(result.get().householdId()).isEqualTo(householdId);
  }
}
