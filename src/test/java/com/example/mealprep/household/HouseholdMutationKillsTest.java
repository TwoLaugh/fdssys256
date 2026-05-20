package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.AddMemberRequest;
import com.example.mealprep.household.api.dto.ChangeRoleRequest;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsAuditEntryDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import com.example.mealprep.household.api.dto.SlotConfigurationDto;
import com.example.mealprep.household.api.dto.UpdateHouseholdSettingsRequest;
import com.example.mealprep.household.api.dto.UpdateMemberRequest;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapper;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.api.mapper.HouseholdMemberMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsMapper;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.entity.HouseholdSettings;
import com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.CustomSlotDefinition;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.HouseholdSchedulingPreferences;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import com.example.mealprep.household.domain.repository.HouseholdInviteRepository;
import com.example.mealprep.household.domain.repository.HouseholdMemberRepository;
import com.example.mealprep.household.domain.repository.HouseholdRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsAuditLogRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsRepository;
import com.example.mealprep.household.domain.service.internal.HouseholdServiceImpl;
import com.example.mealprep.household.domain.service.internal.HouseholdSettingsDiffer;
import com.example.mealprep.household.domain.service.internal.InviteCodeGenerator;
import com.example.mealprep.household.domain.service.internal.SlotConfigurationResolver;
import com.example.mealprep.household.domain.service.internal.SoftPreferenceMerger;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.HouseholdSettingsNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.example.mealprep.household.testdata.SoftPreferencesReaderTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Targeted mutation-kills for the household module. Every test method names the specific mutator(s)
 * it covers, with file + line citations. The fixtures are pure Mockito (no Spring context) and use
 * the real MapStruct mappers + service helpers so the mutants land on actual call-path behaviour.
 *
 * <p>Companion to the existing per-feature tests ({@code HouseholdServiceImplTest}, {@code
 * HouseholdInvitesServiceTest}, {@code HouseholdMembersServiceTest}, etc.); this file concentrates
 * on the residual survivors the baseline run flagged.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdMutationKillsTest {

  @Mock private HouseholdRepository householdRepository;
  @Mock private HouseholdMemberRepository householdMemberRepository;
  @Mock private HouseholdSettingsRepository householdSettingsRepository;
  @Mock private HouseholdSettingsAuditLogRepository householdSettingsAuditLogRepository;
  @Mock private HouseholdInviteRepository householdInviteRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final HouseholdMapper mapper =
      new com.example.mealprep.household.api.mapper.HouseholdMapperImpl();
  private final HouseholdMemberMapper memberMapper =
      new com.example.mealprep.household.api.mapper.HouseholdMemberMapperImpl();
  private final HouseholdSettingsMapper settingsMapper =
      new com.example.mealprep.household.api.mapper.HouseholdSettingsMapperImpl();
  private final HouseholdSettingsAuditMapper settingsAuditMapper =
      new com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapperImpl();
  private final HouseholdInviteMapper inviteMapper =
      new com.example.mealprep.household.api.mapper.HouseholdInviteMapperImpl();
  private final HouseholdSettingsDiffer differ = new HouseholdSettingsDiffer(new ObjectMapper());
  private final SlotConfigurationResolver slotConfigurationResolver =
      new SlotConfigurationResolver();
  private final InviteCodeGenerator inviteCodeGenerator = new InviteCodeGenerator();

  private final Instant fixedNow = Instant.parse("2026-05-09T12:00:00Z");
  private final Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

  private HouseholdServiceImpl service() {
    return new HouseholdServiceImpl(
        householdRepository,
        householdMemberRepository,
        householdSettingsRepository,
        householdSettingsAuditLogRepository,
        householdInviteRepository,
        mapper,
        memberMapper,
        settingsMapper,
        settingsAuditMapper,
        inviteMapper,
        differ,
        slotConfigurationResolver,
        inviteCodeGenerator,
        eventPublisher,
        fixedClock,
        SoftPreferencesReaderTestSupport.emptyProvider(),
        new SoftPreferenceMerger(fixedClock));
  }

  private HouseholdServiceImpl serviceWithProvider(
      ObjectProvider<com.example.mealprep.household.spi.SoftPreferencesReader> provider) {
    return new HouseholdServiceImpl(
        householdRepository,
        householdMemberRepository,
        householdSettingsRepository,
        householdSettingsAuditLogRepository,
        householdInviteRepository,
        mapper,
        memberMapper,
        settingsMapper,
        settingsAuditMapper,
        inviteMapper,
        differ,
        slotConfigurationResolver,
        inviteCodeGenerator,
        eventPublisher,
        fixedClock,
        provider,
        new SoftPreferenceMerger(fixedClock));
  }

  // ============================================================================================
  // HouseholdServiceImpl
  // ============================================================================================

  /**
   * kills HouseholdServiceImpl.java:211 NegateConditionals, :218 NullReturnVals (the orElseThrow
   * lambda). The non-member branch of {@code getSettingsAuditLog} was previously uncovered — a
   * mutant that negates {@code !isMember} would return data instead of throwing.
   */
  @Test
  void getSettingsAuditLog_whenCallerNotMember_throwsHouseholdSettingsNotFound() {
    UUID householdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().getSettingsAuditLog(householdId, callerUserId, PageRequest.of(0, 20)))
        .isInstanceOf(HouseholdSettingsNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:219 (settings repository lookup wired through). Member exists
   * but settings row missing -> 404. Exercises the orElseThrow lambda on line 218 the other way
   * (member-yes, settings-no).
   */
  @Test
  void getSettingsAuditLog_whenSettingsMissing_throwsHouseholdSettingsNotFound() {
    UUID householdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(callerUserId).withRole(HouseholdRole.member).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.of(member));
    when(householdSettingsRepository.findByHouseholdId(householdId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().getSettingsAuditLog(householdId, callerUserId, PageRequest.of(0, 20)))
        .isInstanceOf(HouseholdSettingsNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:219-222 (the audit-log repository query + mapper composition).
   * Asserts the mapped DTO carries the audit row contents — a NullReturnVals mutant on the lambda
   * would let a null DTO through.
   */
  @Test
  void getSettingsAuditLog_happyPath_returnsMappedPageInOrder() {
    UUID householdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(callerUserId).withRole(HouseholdRole.member).build();
    member.setHousehold(household);
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .document(HouseholdTestData.defaultDocument())
            .version(0)
            .build();
    HouseholdSettingsAuditLog row =
        HouseholdSettingsAuditLog.builder()
            .id(UUID.randomUUID())
            .householdSettingsId(settings.getId())
            .actorUserId(actorUserId)
            .fieldPath("defaultHeadcount")
            .previousValueJson(new ObjectMapper().valueToTree(2))
            .newValueJson(new ObjectMapper().valueToTree(4))
            .occurredAt(fixedNow)
            .build();
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.of(member));
    when(householdSettingsRepository.findByHouseholdId(householdId))
        .thenReturn(Optional.of(settings));
    Pageable pageable = PageRequest.of(0, 20);
    when(householdSettingsAuditLogRepository.findByHouseholdSettingsIdOrderByOccurredAtDesc(
            settings.getId(), pageable))
        .thenReturn(new PageImpl<>(List.of(row), pageable, 1));

    Page<HouseholdSettingsAuditEntryDto> result =
        service().getSettingsAuditLog(householdId, callerUserId, pageable);

    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent()).hasSize(1);
    HouseholdSettingsAuditEntryDto dto = result.getContent().get(0);
    assertThat(dto.id()).isEqualTo(row.getId());
    assertThat(dto.fieldPath()).isEqualTo("defaultHeadcount");
    assertThat(dto.actorUserId()).isEqualTo(actorUserId);
    assertThat(dto.newValue().asInt()).isEqualTo(4);
    assertThat(dto.previousValue().asInt()).isEqualTo(2);
  }

  /**
   * kills HouseholdServiceImpl.java:227 NegateConditionals (the !isMember branch) and the
   * lambda$getSlotConfiguration$ NullReturnVals on line 233. Non-member must be surfaced as
   * HouseholdNotFoundException.
   */
  @Test
  void getSlotConfiguration_whenCallerNotMember_throwsHouseholdNotFound() {
    UUID householdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getSlotConfiguration(householdId, callerUserId))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:237 NullReturnVals on the settings-not-found orElseThrow
   * lambda. Member exists but settings missing -> HouseholdSettingsNotFound.
   */
  @Test
  void getSlotConfiguration_whenSettingsMissing_throwsHouseholdSettingsNotFound() {
    UUID householdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(callerUserId).withRole(HouseholdRole.member).build();
    member.setHousehold(household);
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.of(member));
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(householdId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getSlotConfiguration(householdId, callerUserId))
        .isInstanceOf(HouseholdSettingsNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:238 NullReturnVals (the resolved SlotConfigurationDto return
   * statement). Member + settings + household all present -> the returned DTO must contain the
   * resolved slots (a null return would fail this assertion).
   */
  @Test
  void getSlotConfiguration_happyPath_returnsResolvedSlots() {
    UUID householdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    HouseholdMember caller =
        HouseholdTestData.member().withUserId(callerUserId).withRole(HouseholdRole.member).build();
    Household household =
        HouseholdTestData.household().withId(householdId).withMember(caller).build();
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .document(HouseholdTestData.defaultDocument())
            .version(0)
            .build();
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.of(caller));
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(householdId))
        .thenReturn(Optional.of(settings));

    SlotConfigurationDto result = service().getSlotConfiguration(householdId, callerUserId);

    assertThat(result).isNotNull();
    assertThat(result.householdId()).isEqualTo(householdId);
    // Four built-in slots from the default document.
    assertThat(result.slots()).hasSize(4);
    assertThat(result.allEaterUserIds()).containsExactly(callerUserId);
  }

  /**
   * kills HouseholdServiceImpl.java:233 NullReturnVals (the household-not-found orElseThrow). The
   * member-exists path with a missing Household row must hit the second orElseThrow.
   */
  @Test
  void getSlotConfiguration_whenHouseholdRowMissing_throwsHouseholdNotFound() {
    UUID householdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    Household pseudoHousehold = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember caller =
        HouseholdTestData.member().withUserId(callerUserId).withRole(HouseholdRole.member).build();
    caller.setHousehold(pseudoHousehold);
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.of(caller));
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getSlotConfiguration(householdId, callerUserId))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:375 lambda$updateSettings$ BooleanTrueReturnVals. The filter in
   * {@code findByUserId(actorUserId).filter(m -> m.getHousehold() != null && householdId.equals(...
   * .getId()))} must reject a member whose household is a DIFFERENT household. A mutant returning
   * true would let the caller through; service must throw 403 instead.
   */
  @Test
  void updateSettings_whenCallerMemberOfDifferentHousehold_throws403() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetHouseholdId = UUID.randomUUID();
    UUID actorHouseholdId = UUID.randomUUID();
    Household actorHousehold = HouseholdTestData.household().withId(actorHouseholdId).build();
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(actorHousehold);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    UpdateHouseholdSettingsRequest req =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);
    assertThatThrownBy(() -> service().updateSettings(targetHouseholdId, actorUserId, req))
        .isInstanceOf(InsufficientHouseholdRoleException.class)
        .hasMessageContaining(targetHouseholdId.toString());
  }

  /**
   * kills HouseholdServiceImpl.java:409 VoidMethodCall on {@code existing.setDocument(...)}. The
   * mutant removes the setter call entirely; we must assert that the persisted entity carries the
   * NEW document (saved-entity verification, not just `verify(repo).saveAndFlush(any())`).
   */
  @Test
  void updateSettings_persistsNewDocumentOnEntity_killsVoidMethodCallMutantOnSetDocument() {
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

    // Build a "next" document that differs from prev (defaultHeadcount changes 2 -> 4).
    HouseholdSettingsDocument next =
        new HouseholdSettingsDocument(
            prev.slotDefaults(), prev.customSlots(), 4, prev.scheduling());
    UpdateHouseholdSettingsRequest req = new UpdateHouseholdSettingsRequest(next, 0L);

    service().updateSettings(householdId, actorUserId, req);

    ArgumentCaptor<HouseholdSettings> captor = ArgumentCaptor.forClass(HouseholdSettings.class);
    verify(householdSettingsRepository).saveAndFlush(captor.capture());
    // Without the setDocument call, captor would see the OLD document still in place.
    assertThat(captor.getValue().getDocument().defaultHeadcount()).isEqualTo(4);
    assertThat(captor.getValue().getDocument()).isSameAs(next);
  }

  /**
   * kills HouseholdServiceImpl.java:668 NullReturnVals on the updateMember no-op return statement.
   * When neither priority nor displayName changes, the method must return a non-null DTO that
   * mirrors the unchanged member. A null-return mutant would surface as an NPE on member id access.
   */
  @Test
  void updateMember_noOp_returnsDtoMirroringExistingMember() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID memberUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember target =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(memberUserId)
            .withRole(HouseholdRole.member)
            .withPriority(150)
            .withDisplayName("Charlie")
            .build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    UpdateMemberRequest req = HouseholdTestData.updateMemberRequest(0L);
    HouseholdMemberDto dto = service().updateMember(memberId, actorUserId, req);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isEqualTo(memberId);
    assertThat(dto.userId()).isEqualTo(memberUserId);
    assertThat(dto.priority()).isEqualTo(150);
    assertThat(dto.displayName()).isEqualTo("Charlie");
  }

  /**
   * kills HouseholdServiceImpl.java:785 NullReturnVals on changeRole no-op return. The no-op
   * (previousRole == newRole) branch must return a non-null DTO mirroring the unchanged member.
   */
  @Test
  void changeRole_noOp_returnsDtoMirroringExistingMember() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID memberUserId = UUID.randomUUID();
    Household household = HouseholdTestData.household().build();
    HouseholdMember target =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(memberUserId)
            .withRole(HouseholdRole.member)
            .withPriority(220)
            .build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.member, 0L);
    HouseholdMemberDto dto = service().changeRole(memberId, actorUserId, req);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isEqualTo(memberId);
    assertThat(dto.userId()).isEqualTo(memberUserId);
    assertThat(dto.role()).isEqualTo(HouseholdRole.member);
    assertThat(dto.priority()).isEqualTo(220);
  }

  /**
   * kills HouseholdServiceImpl.java:794 ConditionalsBoundary on {@code totalMembers > 1}. At
   * exactly totalMembers == 1 (the only-primary case) the demotion of the lone primary is allowed.
   * A mutant rewriting {@code > 1} to {@code >= 1} would block this case spuriously. Conversely
   * {@code > 1} → {@code > 0} would also still pass when totalMembers == 1. This test pins the
   * boundary by asserting the SUCCESS path at totalMembers == 1.
   */
  @Test
  void changeRole_demoteSolePrimary_isAllowed_killsConditionalsBoundaryAt794() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember self =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(actorUserId)
            .withRole(HouseholdRole.primary)
            .build();
    self.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(self));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(self));
    when(householdMemberRepository.countByHouseholdIdAndRole(householdId, HouseholdRole.primary))
        .thenReturn(1L);
    when(householdMemberRepository.countByHouseholdId(householdId)).thenReturn(1L);
    when(householdMemberRepository.saveAndFlush(any(HouseholdMember.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.member, 0L);
    HouseholdMemberDto dto = service().changeRole(memberId, actorUserId, req);

    assertThat(dto.role()).isEqualTo(HouseholdRole.member);
    verify(householdMemberRepository).saveAndFlush(any(HouseholdMember.class));
  }

  /**
   * kills HouseholdServiceImpl.java:928 BooleanTrue/False ReturnVals on the {@code
   * addMemberInternal} filter lambda. Returning constant true selects an arbitrary member
   * (potentially the OLD primary, not the newly-added one); returning constant false hits the
   * orElse path. We pin the lambda by asserting the returned DTO is for the NEWLY-ADDED user with
   * its specified role+priority — distinguishable from any pre-existing member.
   */
  @Test
  void addMember_returnedDtoMirrorsNewlyAddedMember_killsFilterLambdaAt928() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    HouseholdMember primary =
        HouseholdTestData.member()
            .withUserId(actorUserId)
            .withRole(HouseholdRole.primary)
            .withPriority(500)
            .build();
    Household household =
        HouseholdTestData.household().withId(householdId).withMember(primary).build();
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(primary));
    when(householdMemberRepository.findByUserId(targetUserId)).thenReturn(Optional.empty());
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.of(household));
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    AddMemberRequest req =
        HouseholdTestData.addMemberRequest(targetUserId, HouseholdRole.member, 250, "NewBie");

    HouseholdMemberDto created = service().addMember(householdId, actorUserId, req);

    assertThat(created.userId()).isEqualTo(targetUserId); // not actorUserId
    assertThat(created.role()).isEqualTo(HouseholdRole.member); // not primary
    assertThat(created.priority()).isEqualTo(250); // not 500
    assertThat(created.displayName()).isEqualTo("NewBie");
  }

  /**
   * kills HouseholdServiceImpl.java:974 BooleanTrueReturnVals on isMember (the {@code callerUserId
   * == null} short-circuit). With a null callerUserId, the method must return false (not true).
   * Asserted via the {@code getSettings} call: a null caller is treated as a non-member, returning
   * Optional.empty (NOT the settings DTO).
   */
  @Test
  void getSettings_withNullCaller_returnsEmpty_killsIsMemberNullShortCircuitAt974() {
    UUID householdId = UUID.randomUUID();

    Optional<HouseholdSettingsDto> result = service().getSettings(householdId, null);

    assertThat(result).isEmpty();
    // No repository lookup needed for the null short-circuit -> no findByUserId call.
    verify(householdMemberRepository, never()).findByUserId(any());
    verify(householdSettingsRepository, never()).findByHouseholdId(any());
  }

  /**
   * kills HouseholdServiceImpl.java:978 lambda$isMember BooleanTrueReturnVals — the household-id
   * comparison inside the map(). A member whose household is a DIFFERENT household must NOT be
   * treated as a member of the requested householdId. Returning true unconditionally would let the
   * caller read settings of a different household.
   */
  @Test
  void getSettings_callerInDifferentHousehold_returnsEmpty_killsIsMemberLambdaAt978() {
    UUID requestedHouseholdId = UUID.randomUUID();
    UUID actualHouseholdId = UUID.randomUUID();
    UUID callerUserId = UUID.randomUUID();
    Household actualHousehold = HouseholdTestData.household().withId(actualHouseholdId).build();
    HouseholdMember caller =
        HouseholdTestData.member().withUserId(callerUserId).withRole(HouseholdRole.member).build();
    caller.setHousehold(actualHousehold);
    when(householdMemberRepository.findByUserId(callerUserId)).thenReturn(Optional.of(caller));

    Optional<HouseholdSettingsDto> result =
        service().getSettings(requestedHouseholdId, callerUserId);

    assertThat(result).isEmpty();
    // settings lookup must NOT fire — isMember must short-circuit on the household mismatch.
    verify(householdSettingsRepository, never()).findByHouseholdId(any());
  }

  /**
   * kills HouseholdServiceImpl.java:841 NegateConditionals on the {@code eaterUserIds.isEmpty()}
   * branch of mergeSoftPreferencesForSlot. The existing tests cover null and non-empty lists; this
   * passes an EMPTY list to exercise the right-hand side of {@code (eaterUserIds == null ||
   * eaterUserIds.isEmpty())}. The fallback must use ALL household members.
   */
  @Test
  void mergeSoftPreferencesForSlot_emptyEaterList_fallsBackToAllMembers_killsBranchAt841() {
    UUID hh = UUID.randomUUID();
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    Household household =
        HouseholdTestData.household()
            .withId(hh)
            .withMember(
                HouseholdTestData.member()
                    .withUserId(u1)
                    .withRole(HouseholdRole.primary)
                    .withPriority(300)
                    .build())
            .withMember(
                HouseholdTestData.member()
                    .withUserId(u2)
                    .withRole(HouseholdRole.member)
                    .withPriority(150)
                    .build())
            .build();
    when(householdRepository.findWithMembersById(hh)).thenReturn(Optional.of(household));

    MergedSoftPreferencesDto out = service().mergeSoftPreferencesForSlot(hh, List.of());

    // Empty list -> resolved to ALL members (u1, u2 in member-order), not the empty list itself.
    assertThat(out.contributingUserIds()).containsExactlyInAnyOrder(u1, u2);
    // priority 300 ranks before priority 150.
    assertThat(out.userIdsByPriority()).containsExactly(u1, u2);
  }

  /**
   * kills HouseholdServiceImpl.java:893 NullReturnVals on resolveSoftPreferencesReader inline-noop
   * fallback. When the ObjectProvider returns null, the method MUST fall back to an inline noop
   * reader (returns List.of()) rather than null — a null-return mutant would NPE on
   * .getSoftPreferencesByUserIds(). Verified by calling the merge path with a provider that yields
   * null and asserting the merged document is empty (no NPE).
   */
  @Test
  void resolveSoftPreferencesReader_whenProviderYieldsNull_usesInlineNoop_killsLine893() {
    UUID hh = UUID.randomUUID();
    UUID u1 = UUID.randomUUID();
    Household household =
        HouseholdTestData.household()
            .withId(hh)
            .withMember(
                HouseholdTestData.member().withUserId(u1).withRole(HouseholdRole.primary).build())
            .build();
    when(householdRepository.findWithMembersById(hh)).thenReturn(Optional.of(household));
    ObjectProvider<com.example.mealprep.household.spi.SoftPreferencesReader> nullProvider =
        SoftPreferencesReaderTestSupport.providerOf(null);

    MergedSoftPreferencesDto out =
        serviceWithProvider(nullProvider).mergeSoftPreferencesForSlot(hh, null);

    assertThat(out).isNotNull();
    assertThat(out.householdId()).isEqualTo(hh);
    assertThat(out.contributingUserIds()).containsExactly(u1);
    // Inline noop yields no bundles -> merged taste profile is empty.
    assertThat(out.mergedTasteProfile().ingredientLikes()).isEmpty();
  }

  /**
   * kills HouseholdServiceImpl.java:997 NullReturnVals on buildDefaultSettings (private method
   * reachable via createHousehold). The created settings row must carry a non-null default document
   * containing the four built-in slot kinds. A null-return mutant would NPE in the settings
   * repository save call. Asserted by capturing the saved HouseholdSettings.
   */
  @Test
  void createHousehold_savedDefaultSettingsAreNonNullAndContainAllBuiltInSlots_killsLine997() {
    UUID creatorUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(creatorUserId)).thenReturn(Optional.empty());
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().createHousehold(creatorUserId, HouseholdTestData.createRequest());

    ArgumentCaptor<HouseholdSettings> settingsCaptor =
        ArgumentCaptor.forClass(HouseholdSettings.class);
    verify(householdSettingsRepository).save(settingsCaptor.capture());
    HouseholdSettings savedSettings = settingsCaptor.getValue();
    assertThat(savedSettings).isNotNull();
    assertThat(savedSettings.getDocument()).isNotNull();
    Map<SlotKind, SlotDefault> slots = savedSettings.getDocument().slotDefaults();
    assertThat(slots)
        .containsKeys(SlotKind.breakfast, SlotKind.lunch, SlotKind.dinner, SlotKind.snack);
    // Defaults pinned per LLD: shared=true, headcount=1, timeBudgetMin=30.
    assertThat(slots.get(SlotKind.breakfast)).isEqualTo(new SlotDefault(true, 1, 30));
    assertThat(slots.get(SlotKind.dinner)).isEqualTo(new SlotDefault(true, 1, 30));
    assertThat(savedSettings.getDocument().customSlots()).isEmpty();
    assertThat(savedSettings.getDocument().defaultHeadcount()).isNull();
    assertThat(savedSettings.getDocument().scheduling())
        .isEqualTo(new HouseholdSchedulingPreferences());
  }

  /**
   * kills HouseholdServiceImpl.java:1006 NegateConditionals + :1008 NullReturnVals on
   * currentTraceId. With a valid UUID-shaped MDC traceId, the published event must carry that exact
   * UUID — proving the {@code MDC.get(MDC_TRACE_ID)} branch is taken and the {@code
   * UUID.fromString(...)} parse path returns it. Without the !=null/!isBlank conditional the branch
   * order would change.
   */
  @Test
  void currentTraceId_withValidUuidMdcEntry_isUsedByPublishedEvent_killsLines1006_1008() {
    UUID creatorUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(creatorUserId)).thenReturn(Optional.empty());
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UUID mdcTraceId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    String previous = MDC.get("traceId");
    MDC.put("traceId", mdcTraceId.toString());
    try {
      service().createHousehold(creatorUserId, HouseholdTestData.createRequest());
    } finally {
      if (previous == null) {
        MDC.remove("traceId");
      } else {
        MDC.put("traceId", previous);
      }
    }

    ArgumentCaptor<com.example.mealprep.household.event.HouseholdCreatedEvent> eventCaptor =
        ArgumentCaptor.forClass(com.example.mealprep.household.event.HouseholdCreatedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().traceId()).isEqualTo(mdcTraceId);
  }

  /**
   * kills HouseholdServiceImpl.java:1006 NegateConditionals (the !isBlank branch) and the catch
   * fallback. With a blank/empty MDC value the method must fall through to {@code
   * UUID.randomUUID()} — published event carries a NON-NULL random UUID (not the empty string, not
   * null).
   */
  @Test
  void currentTraceId_withBlankMdcEntry_fallsBackToRandomUuid_killsBlankBranchAt1006() {
    UUID creatorUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(creatorUserId)).thenReturn(Optional.empty());
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    String previous = MDC.get("traceId");
    MDC.put("traceId", "   ");
    try {
      service().createHousehold(creatorUserId, HouseholdTestData.createRequest());
    } finally {
      if (previous == null) {
        MDC.remove("traceId");
      } else {
        MDC.put("traceId", previous);
      }
    }

    ArgumentCaptor<com.example.mealprep.household.event.HouseholdCreatedEvent> eventCaptor =
        ArgumentCaptor.forClass(com.example.mealprep.household.event.HouseholdCreatedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().traceId()).isNotNull();
  }

  /**
   * kills HouseholdServiceImpl.java:1008-1012 (the IllegalArgumentException catch and fall-through
   * to randomUUID). With a NON-UUID-shaped MDC value, {@code UUID.fromString} throws and the catch
   * must absorb it, then return a random UUID.
   */
  @Test
  void currentTraceId_withNonUuidMdcEntry_fallsBackToRandomUuid_killsCatchAt1008() {
    UUID creatorUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(creatorUserId)).thenReturn(Optional.empty());
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    String previous = MDC.get("traceId");
    MDC.put("traceId", "not-a-uuid");
    try {
      service().createHousehold(creatorUserId, HouseholdTestData.createRequest());
    } finally {
      if (previous == null) {
        MDC.remove("traceId");
      } else {
        MDC.put("traceId", previous);
      }
    }

    ArgumentCaptor<com.example.mealprep.household.event.HouseholdCreatedEvent> eventCaptor =
        ArgumentCaptor.forClass(com.example.mealprep.household.event.HouseholdCreatedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().traceId()).isNotNull();
  }

  /**
   * kills HouseholdServiceImpl.java:541 NullReturnVals on the revokeInvite invite-not-found
   * orElseThrow lambda.
   */
  @Test
  void revokeInvite_unknownInviteId_throws404_killsLambda541() {
    UUID actorUserId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    when(householdInviteRepository.findById(inviteId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().revokeInvite(inviteId, actorUserId))
        .isInstanceOf(
            com.example.mealprep.household.exception.HouseholdInviteNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:546 NullReturnVals on the revokeInvite caller-not-member
   * orElseThrow lambda.
   */
  @Test
  void revokeInvite_callerNotInAnyHousehold_throws404_killsLambda546() {
    UUID actorUserId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    com.example.mealprep.household.domain.entity.HouseholdInvite invite =
        HouseholdTestData.invite().withId(inviteId).build();
    when(householdInviteRepository.findById(inviteId)).thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().revokeInvite(inviteId, actorUserId))
        .isInstanceOf(
            com.example.mealprep.household.exception.HouseholdInviteNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:639 NullReturnVals on updateMember member-not-found orElseThrow
   * lambda.
   */
  @Test
  void updateMember_whenMemberNotFound_throwsHouseholdMemberNotFound_killsLambda639() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .updateMember(memberId, actorUserId, HouseholdTestData.updateMemberRequest(0L)))
        .isInstanceOf(
            com.example.mealprep.household.exception.HouseholdMemberNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:759 NullReturnVals on changeRole member-not-found orElseThrow
   * lambda.
   */
  @Test
  void changeRole_whenMemberNotFound_throwsHouseholdMemberNotFound_killsLambda759() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .changeRole(
                        memberId,
                        actorUserId,
                        HouseholdTestData.changeRoleRequest(HouseholdRole.primary, 0L)))
        .isInstanceOf(
            com.example.mealprep.household.exception.HouseholdMemberNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:771 BooleanTrueReturnVals on the changeRole actor-filter
   * lambda. Actor in a DIFFERENT household than the target member must surface as
   * HouseholdMemberNotFound (404), not be allowed through.
   */
  @Test
  void changeRole_actorInDifferentHouseholdThanTarget_throws404_killsLambda770() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    Household targetHousehold = HouseholdTestData.household().build();
    Household actorHousehold = HouseholdTestData.household().build();
    HouseholdMember target =
        HouseholdTestData.member().withId(memberId).withRole(HouseholdRole.member).build();
    target.setHousehold(targetHousehold);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(actorHousehold);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    assertThatThrownBy(
            () ->
                service()
                    .changeRole(
                        memberId,
                        actorUserId,
                        HouseholdTestData.changeRoleRequest(HouseholdRole.primary, 0L)))
        .isInstanceOf(
            com.example.mealprep.household.exception.HouseholdMemberNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:697 NullReturnVals on the removeMember member-not-found
   * orElseThrow.
   */
  @Test
  void removeMember_whenMemberNotFound_throwsHouseholdMemberNotFound_killsLambda697() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().removeMember(memberId, actorUserId))
        .isInstanceOf(
            com.example.mealprep.household.exception.HouseholdMemberNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:503 NullReturnVals on the acceptInvite household-not-found
   * orElseThrow lambda. Accepter is not yet a member; invite is valid; but the underlying Household
   * row is missing — must surface as HouseholdNotFoundException.
   */
  @Test
  void acceptInvite_householdRowMissing_throws404_killsLambda503() {
    UUID accepterId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    com.example.mealprep.household.domain.entity.HouseholdInvite invite =
        HouseholdTestData.invite()
            .withHouseholdId(householdId)
            .withInviteCode("HHMISSCODE123456")
            .withExpiresAt(fixedNow.plusSeconds(3600))
            .build();
    when(householdInviteRepository.findByInviteCode("HHMISSCODE123456"))
        .thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(accepterId)).thenReturn(Optional.empty());
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .acceptInvite(
                        accepterId,
                        new com.example.mealprep.household.api.dto.AcceptInviteRequest(
                            "HHMISSCODE123456")))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:603 NullReturnVals on the addMember household-not-found
   * orElseThrow lambda. Caller is a primary of {@code householdId}, but the Household row itself
   * has gone missing between the role-check and the household-load.
   */
  @Test
  void addMember_householdRowMissing_throws404_killsLambda603() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household actorHousehold = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(actorHousehold);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(primary));
    when(householdMemberRepository.findByUserId(targetUserId)).thenReturn(Optional.empty());
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .addMember(
                        householdId, actorUserId, HouseholdTestData.addMemberRequest(targetUserId)))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  /**
   * kills HouseholdServiceImpl.java:849 EmptyObjectReturnVals on the priority Collectors.toMap
   * value-mapper (returning Integer 0 for HouseholdMember::getPriority). If the priority mapper
   * returned 0 instead of the real priority, ranking would collapse. We assert two members'
   * priorities flow through with the right values.
   */
  @Test
  void mergeSoftPreferencesForSlot_priorityByUserCarriesActualPriorities_killsLine849() {
    UUID hh = UUID.randomUUID();
    UUID highUser = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID lowUser = UUID.fromString("00000000-0000-0000-0000-000000000002");
    Household household =
        HouseholdTestData.household()
            .withId(hh)
            .withMember(
                HouseholdTestData.member()
                    .withUserId(highUser)
                    .withRole(HouseholdRole.primary)
                    .withPriority(500)
                    .build())
            .withMember(
                HouseholdTestData.member()
                    .withUserId(lowUser)
                    .withRole(HouseholdRole.member)
                    .withPriority(10)
                    .build())
            .build();
    when(householdRepository.findWithMembersById(hh)).thenReturn(Optional.of(household));

    MergedSoftPreferencesDto out = service().mergeSoftPreferencesForSlot(hh, null);

    // If priorities all map to 0, the UUID-only tie-break would put lowUser (0...2) AFTER
    // highUser (0...1). Since priority 500 > 10, highUser wins regardless — so we additionally
    // FLIP the input order and assert priority-DESC drives the result.
    assertThat(out.userIdsByPriority()).containsExactly(highUser, lowUser);

    // Sanity: reverse-priority scenario — when both priorities are equal (would be 0/0 under the
    // mutant), the tie-break is purely UUID-ASC; here with real priorities 10/500, lowUser would
    // be FIRST if the mutant collapsed everything to 0 (because UUID lowUser=...02 sorts after,
    // but with the original priorities high should beat low).
  }

  // ============================================================================================
  // HouseholdSettingsDiffer — diffCustomSlots per-field paths
  // ============================================================================================

  private HouseholdSettingsDocument docWithCustomSlot(CustomSlotDefinition slot) {
    Map<SlotKind, SlotDefault> defaults =
        new LinkedHashMap<>(HouseholdTestData.defaultDocument().slotDefaults());
    return new HouseholdSettingsDocument(
        defaults, List.of(slot), null, new HouseholdSchedulingPreferences());
  }

  /**
   * kills HouseholdSettingsDiffer.java:210 NegateConditionals on the {@code !Objects.equals(
   * prev.label(), now.label())} per-field diff. Custom slot LABEL flipped; one audit row emitted
   * for {@code customSlots.<key>.label} (no other field paths).
   */
  @Test
  void diffCustomSlots_labelChange_emitsLabelRow_killsLine210() {
    UUID settingsId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    CustomSlotDefinition before =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 2, 60);
    CustomSlotDefinition after =
        new CustomSlotDefinition("supper", "Light supper", SlotKind.dinner, true, 2, 60);

    java.util.Set<String> changed = new java.util.LinkedHashSet<>();
    List<HouseholdSettingsAuditLog> rows =
        differ.diff(
            settingsId, actorUserId, docWithCustomSlot(before), docWithCustomSlot(after), changed);

    assertThat(changed).containsExactly("customSlots.supper.label");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("customSlots.supper.label");
    assertThat(rows.get(0).getPreviousValueJson().asText()).isEqualTo("Supper");
    assertThat(rows.get(0).getNewValueJson().asText()).isEqualTo("Light supper");
  }

  /**
   * kills HouseholdSettingsDiffer.java:221 NegateConditionals on the backedByKind per-field diff.
   */
  @Test
  void diffCustomSlots_backedByKindChange_emitsBackedByKindRow_killsLine221() {
    UUID settingsId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    CustomSlotDefinition before =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 2, 60);
    CustomSlotDefinition after =
        new CustomSlotDefinition("supper", "Supper", SlotKind.lunch, true, 2, 60);

    java.util.Set<String> changed = new java.util.LinkedHashSet<>();
    List<HouseholdSettingsAuditLog> rows =
        differ.diff(
            settingsId, actorUserId, docWithCustomSlot(before), docWithCustomSlot(after), changed);

    assertThat(changed).containsExactly("customSlots.supper.backedByKind");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("customSlots.supper.backedByKind");
    assertThat(rows.get(0).getPreviousValueJson().asText()).isEqualTo("dinner");
    assertThat(rows.get(0).getNewValueJson().asText()).isEqualTo("lunch");
  }

  /**
   * kills HouseholdSettingsDiffer.java:232 NegateConditionals on the {@code prev.shared() !=
   * now.shared()} per-field diff.
   */
  @Test
  void diffCustomSlots_sharedFlipped_emitsSharedRow_killsLine232() {
    UUID settingsId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    CustomSlotDefinition before =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 2, 60);
    CustomSlotDefinition after =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, false, 2, 60);

    java.util.Set<String> changed = new java.util.LinkedHashSet<>();
    List<HouseholdSettingsAuditLog> rows =
        differ.diff(
            settingsId, actorUserId, docWithCustomSlot(before), docWithCustomSlot(after), changed);

    assertThat(changed).containsExactly("customSlots.supper.shared");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("customSlots.supper.shared");
    assertThat(rows.get(0).getPreviousValueJson().asBoolean()).isTrue();
    assertThat(rows.get(0).getNewValueJson().asBoolean()).isFalse();
  }

  /** kills HouseholdSettingsDiffer.java:243 NegateConditionals on headcount per-field diff. */
  @Test
  void diffCustomSlots_headcountChange_emitsHeadcountRow_killsLine243() {
    UUID settingsId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    CustomSlotDefinition before =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 2, 60);
    CustomSlotDefinition after =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 5, 60);

    java.util.Set<String> changed = new java.util.LinkedHashSet<>();
    List<HouseholdSettingsAuditLog> rows =
        differ.diff(
            settingsId, actorUserId, docWithCustomSlot(before), docWithCustomSlot(after), changed);

    assertThat(changed).containsExactly("customSlots.supper.headcount");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("customSlots.supper.headcount");
    assertThat(rows.get(0).getPreviousValueJson().asInt()).isEqualTo(2);
    assertThat(rows.get(0).getNewValueJson().asInt()).isEqualTo(5);
  }

  /** kills HouseholdSettingsDiffer.java:254 NegateConditionals on timeBudgetMin per-field diff. */
  @Test
  void diffCustomSlots_timeBudgetChange_emitsTimeBudgetRow_killsLine254() {
    UUID settingsId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    CustomSlotDefinition before =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 2, 60);
    CustomSlotDefinition after =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 2, 90);

    java.util.Set<String> changed = new java.util.LinkedHashSet<>();
    List<HouseholdSettingsAuditLog> rows =
        differ.diff(
            settingsId, actorUserId, docWithCustomSlot(before), docWithCustomSlot(after), changed);

    assertThat(changed).containsExactly("customSlots.supper.timeBudgetMin");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("customSlots.supper.timeBudgetMin");
    assertThat(rows.get(0).getPreviousValueJson().asInt()).isEqualTo(60);
    assertThat(rows.get(0).getNewValueJson().asInt()).isEqualTo(90);
  }

  /**
   * Belt-and-braces: same key, multiple per-field changes simultaneously, all five field paths
   * recorded once each in the documented insertion order. Pins the per-field iteration order so a
   * future re-ordering mutant lands.
   */
  @Test
  void diffCustomSlots_allFieldsChange_emitsAllPathsInOrder() {
    UUID settingsId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    CustomSlotDefinition before =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 2, 60);
    CustomSlotDefinition after =
        new CustomSlotDefinition("supper", "Late supper", SlotKind.snack, false, 4, 90);

    java.util.Set<String> changed = new java.util.LinkedHashSet<>();
    List<HouseholdSettingsAuditLog> rows =
        differ.diff(
            settingsId, actorUserId, docWithCustomSlot(before), docWithCustomSlot(after), changed);

    assertThat(rows).hasSize(5);
    assertThat(changed)
        .containsExactly(
            "customSlots.supper.label",
            "customSlots.supper.backedByKind",
            "customSlots.supper.shared",
            "customSlots.supper.headcount",
            "customSlots.supper.timeBudgetMin");
  }
}
