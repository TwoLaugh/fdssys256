package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.AddMemberRequest;
import com.example.mealprep.household.api.dto.ChangeRoleRequest;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.UpdateMemberRequest;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapper;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.api.mapper.HouseholdMemberMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsMapper;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.repository.HouseholdInviteRepository;
import com.example.mealprep.household.domain.repository.HouseholdMemberRepository;
import com.example.mealprep.household.domain.repository.HouseholdRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsAuditLogRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsRepository;
import com.example.mealprep.household.domain.service.internal.HouseholdServiceImpl;
import com.example.mealprep.household.domain.service.internal.HouseholdSettingsDiffer;
import com.example.mealprep.household.domain.service.internal.InviteCodeGenerator;
import com.example.mealprep.household.domain.service.internal.SlotConfigurationResolver;
import com.example.mealprep.household.event.HouseholdMemberAddedEvent;
import com.example.mealprep.household.event.HouseholdMemberRemovedEvent;
import com.example.mealprep.household.event.HouseholdRoleChangedEvent;
import com.example.mealprep.household.exception.HouseholdMemberNotFoundException;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.exception.LastPrimaryRemovalException;
import com.example.mealprep.household.exception.UserAlreadyInHouseholdException;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.OptimisticLockingFailureException;

/** Unit tests for the member-administration branches of {@link HouseholdServiceImpl} (01d). */
@ExtendWith(MockitoExtension.class)
class HouseholdMembersServiceTest {

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
        fixedClock);
  }

  // ---------------- addMember ----------------

  @Test
  void addMember_byPrimary_persistsAndPublishesEvent() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(primary));
    when(householdMemberRepository.findByUserId(targetUserId)).thenReturn(Optional.empty());
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.of(household));
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    AddMemberRequest req =
        HouseholdTestData.addMemberRequest(targetUserId, HouseholdRole.member, 200, "Alice");

    HouseholdMemberDto created = service().addMember(householdId, actorUserId, req);

    assertThat(created.userId()).isEqualTo(targetUserId);
    assertThat(created.role()).isEqualTo(HouseholdRole.member);
    assertThat(created.priority()).isEqualTo(200);
    assertThat(created.displayName()).isEqualTo("Alice");

    ArgumentCaptor<HouseholdMemberAddedEvent> captor =
        ArgumentCaptor.forClass(HouseholdMemberAddedEvent.class);
    verify(eventPublisher, times(1)).publishEvent(captor.capture());
    assertThat(captor.getValue().householdId()).isEqualTo(householdId);
    assertThat(captor.getValue().userId()).isEqualTo(targetUserId);
    assertThat(captor.getValue().role()).isEqualTo(HouseholdRole.member);
  }

  @Test
  void addMember_priorityNull_defaultsTo100() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(primary));
    when(householdMemberRepository.findByUserId(targetUserId)).thenReturn(Optional.empty());
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.of(household));
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    AddMemberRequest req =
        HouseholdTestData.addMemberRequest(targetUserId, HouseholdRole.member, null, null);
    HouseholdMemberDto created = service().addMember(householdId, actorUserId, req);
    assertThat(created.priority()).isEqualTo(100);
  }

  @Test
  void addMember_byNonPrimary_throws403() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.member).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    assertThatThrownBy(
            () ->
                service()
                    .addMember(
                        householdId, actorUserId, HouseholdTestData.addMemberRequest(targetUserId)))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void addMember_byNonMember_throws404() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .addMember(
                        householdId, actorUserId, HouseholdTestData.addMemberRequest(targetUserId)))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  @Test
  void addMember_targetAlreadyInHousehold_throws409() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    HouseholdMember existing =
        HouseholdTestData.member().withUserId(targetUserId).withRole(HouseholdRole.member).build();
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));
    when(householdMemberRepository.findByUserId(targetUserId)).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service()
                    .addMember(
                        householdId, actorUserId, HouseholdTestData.addMemberRequest(targetUserId)))
        .isInstanceOf(UserAlreadyInHouseholdException.class);
  }

  // ---------------- updateMember ----------------

  @Test
  void updateMember_priorityAndDisplayName_bumpsVersionAndReturnsDto() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember target =
        HouseholdTestData.member()
            .withId(memberId)
            .withRole(HouseholdRole.member)
            .withPriority(100)
            .withDisplayName(null)
            .build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));
    when(householdMemberRepository.saveAndFlush(any(HouseholdMember.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateMemberRequest req = HouseholdTestData.updateMemberRequest(250, "Bob", 0L);

    HouseholdMemberDto updated = service().updateMember(memberId, actorUserId, req);

    assertThat(updated.priority()).isEqualTo(250);
    assertThat(updated.displayName()).isEqualTo("Bob");
    verify(householdMemberRepository, times(1)).saveAndFlush(any(HouseholdMember.class));
    verify(eventPublisher, never()).publishEvent(any()); // no event on update
  }

  @Test
  void updateMember_bothFieldsNull_noOp_noVersionBump() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember target =
        HouseholdTestData.member()
            .withId(memberId)
            .withRole(HouseholdRole.member)
            .withPriority(100)
            .build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    UpdateMemberRequest req = HouseholdTestData.updateMemberRequest(0L);
    service().updateMember(memberId, actorUserId, req);

    verify(householdMemberRepository, never()).saveAndFlush(any(HouseholdMember.class));
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void updateMember_byNonPrimary_throws403() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember target =
        HouseholdTestData.member().withId(memberId).withRole(HouseholdRole.member).build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.member).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    assertThatThrownBy(
            () ->
                service()
                    .updateMember(memberId, actorUserId, HouseholdTestData.updateMemberRequest(0L)))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
  }

  @Test
  void updateMember_memberInDifferentHousehold_throws404() {
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
                    .updateMember(memberId, actorUserId, HouseholdTestData.updateMemberRequest(0L)))
        .isInstanceOf(HouseholdMemberNotFoundException.class);
  }

  @Test
  void updateMember_staleExpectedVersion_throws409() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    Household household = HouseholdTestData.household().build();
    HouseholdMember target =
        HouseholdTestData.member().withId(memberId).withRole(HouseholdRole.member).build();
    target.setHousehold(household);
    target.setVersion(2L);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    assertThatThrownBy(
            () ->
                service()
                    .updateMember(
                        memberId, actorUserId, HouseholdTestData.updateMemberRequest(99, "x", 0L)))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  // ---------------- removeMember ----------------

  @Test
  void removeMember_byPrimary_deletesAndPublishesEvent() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    HouseholdMember target =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(memberUserId)
            .withRole(HouseholdRole.member)
            .build();
    target.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    service().removeMember(memberId, actorUserId);

    verify(householdMemberRepository, times(1)).delete(target);
    ArgumentCaptor<HouseholdMemberRemovedEvent> captor =
        ArgumentCaptor.forClass(HouseholdMemberRemovedEvent.class);
    verify(eventPublisher, times(1)).publishEvent(captor.capture());
    assertThat(captor.getValue().roleAtRemoval()).isEqualTo(HouseholdRole.member);
    assertThat(captor.getValue().userId()).isEqualTo(memberUserId);
    assertThat(captor.getValue().householdId()).isEqualTo(householdId);
  }

  @Test
  void removeMember_selfRemoveByMember_isAllowed() {
    UUID userId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember self =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(userId)
            .withRole(HouseholdRole.member)
            .build();
    self.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(self));
    when(householdMemberRepository.findByUserId(userId)).thenReturn(Optional.of(self));

    service().removeMember(memberId, userId);

    verify(householdMemberRepository, times(1)).delete(self);
    verify(eventPublisher, times(1)).publishEvent(any(HouseholdMemberRemovedEvent.class));
  }

  @Test
  void removeMember_byMemberTargetingAnother_throws403() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    Household household = HouseholdTestData.household().build();
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.member).build();
    actor.setHousehold(household);
    HouseholdMember target =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(memberUserId)
            .withRole(HouseholdRole.member)
            .build();
    target.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    assertThatThrownBy(() -> service().removeMember(memberId, actorUserId))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
  }

  @Test
  void removeMember_lastPrimaryWithOtherMembersPresent_throws409() {
    UUID userId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember self =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(userId)
            .withRole(HouseholdRole.primary)
            .build();
    self.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(self));
    when(householdMemberRepository.findByUserId(userId)).thenReturn(Optional.of(self));
    when(householdMemberRepository.countByHouseholdId(householdId)).thenReturn(3L);
    when(householdMemberRepository.countByHouseholdIdAndRole(householdId, HouseholdRole.primary))
        .thenReturn(1L);

    assertThatThrownBy(() -> service().removeMember(memberId, userId))
        .isInstanceOf(LastPrimaryRemovalException.class);
  }

  @Test
  void removeMember_onlyMemberPrimary_isAllowed() {
    UUID userId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember self =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(userId)
            .withRole(HouseholdRole.primary)
            .build();
    self.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(self));
    when(householdMemberRepository.findByUserId(userId)).thenReturn(Optional.of(self));
    when(householdMemberRepository.countByHouseholdId(householdId)).thenReturn(1L);
    when(householdMemberRepository.countByHouseholdIdAndRole(householdId, HouseholdRole.primary))
        .thenReturn(1L);

    service().removeMember(memberId, userId);

    verify(householdMemberRepository, times(1)).delete(self);
  }

  @Test
  void removeMember_memberInDifferentHousehold_throws404() {
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

    assertThatThrownBy(() -> service().removeMember(memberId, actorUserId))
        .isInstanceOf(HouseholdMemberNotFoundException.class);
  }

  // ---------------- changeRole ----------------

  @Test
  void changeRole_promoteMemberToPrimary_publishesEvent() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember target =
        HouseholdTestData.member()
            .withId(memberId)
            .withUserId(memberUserId)
            .withRole(HouseholdRole.member)
            .build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));
    when(householdMemberRepository.saveAndFlush(any(HouseholdMember.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.primary, 0L);
    HouseholdMemberDto updated = service().changeRole(memberId, actorUserId, req);

    assertThat(updated.role()).isEqualTo(HouseholdRole.primary);
    ArgumentCaptor<HouseholdRoleChangedEvent> captor =
        ArgumentCaptor.forClass(HouseholdRoleChangedEvent.class);
    verify(eventPublisher, times(1)).publishEvent(captor.capture());
    assertThat(captor.getValue().previousRole()).isEqualTo(HouseholdRole.member);
    assertThat(captor.getValue().newRole()).isEqualTo(HouseholdRole.primary);
  }

  @Test
  void changeRole_noOp_returnsDtoWithoutEvent() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    Household household = HouseholdTestData.household().build();
    HouseholdMember target =
        HouseholdTestData.member().withId(memberId).withRole(HouseholdRole.member).build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.member, 0L);
    service().changeRole(memberId, actorUserId, req);

    verify(householdMemberRepository, never()).saveAndFlush(any(HouseholdMember.class));
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void changeRole_demoteLastPrimaryWithOthersPresent_throws409() {
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
    when(householdMemberRepository.countByHouseholdId(householdId)).thenReturn(2L);

    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.member, 0L);
    assertThatThrownBy(() -> service().changeRole(memberId, actorUserId, req))
        .isInstanceOf(LastPrimaryRemovalException.class);
  }

  @Test
  void changeRole_byNonPrimary_throws403() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    Household household = HouseholdTestData.household().build();
    HouseholdMember target =
        HouseholdTestData.member().withId(memberId).withRole(HouseholdRole.member).build();
    target.setHousehold(household);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.member).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.primary, 0L);
    assertThatThrownBy(() -> service().changeRole(memberId, actorUserId, req))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
  }

  @Test
  void changeRole_staleExpectedVersion_throws409() {
    UUID actorUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    Household household = HouseholdTestData.household().build();
    HouseholdMember target =
        HouseholdTestData.member().withId(memberId).withRole(HouseholdRole.member).build();
    target.setHousehold(household);
    target.setVersion(5L);
    HouseholdMember actor =
        HouseholdTestData.member().withUserId(actorUserId).withRole(HouseholdRole.primary).build();
    actor.setHousehold(household);
    when(householdMemberRepository.findById(memberId)).thenReturn(Optional.of(target));
    when(householdMemberRepository.findByUserId(actorUserId)).thenReturn(Optional.of(actor));

    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.primary, 0L);
    assertThatThrownBy(() -> service().changeRole(memberId, actorUserId, req))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }
}
