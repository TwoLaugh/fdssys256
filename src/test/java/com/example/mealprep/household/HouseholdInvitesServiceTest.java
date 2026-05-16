package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.AcceptInviteRequest;
import com.example.mealprep.household.api.dto.CreateInviteRequest;
import com.example.mealprep.household.api.dto.HouseholdInviteDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.InviteStatus;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapper;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.api.mapper.HouseholdMemberMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsMapper;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdInvite;
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
import com.example.mealprep.household.event.HouseholdInviteAcceptedEvent;
import com.example.mealprep.household.event.HouseholdInviteCreatedEvent;
import com.example.mealprep.household.exception.HouseholdInviteAlreadyAcceptedException;
import com.example.mealprep.household.exception.HouseholdInviteExpiredException;
import com.example.mealprep.household.exception.HouseholdInviteNotFoundException;
import com.example.mealprep.household.exception.HouseholdInviteRevokedException;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.exception.UserAlreadyInHouseholdException;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/** Unit tests for the invite-related branches of {@link HouseholdServiceImpl}. */
@ExtendWith(MockitoExtension.class)
class HouseholdInvitesServiceTest {

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

  // Anchored relative to real wall-clock, NOT a hardcoded date. HouseholdInviteMapper.deriveStatus
  // compares expiresAt against the real Instant.now() (correct production behaviour — an invite
  // expires in real time). A hardcoded fixedNow whose +7d/+30d windows lapse in real time turns
  // this into a time-bomb (it rotted to EXPIRED once the date rolled past 2026-05-16). Anchoring a
  // day into the real future keeps every fixedNow.plus(...) window safely unexpired.
  private final Instant fixedNow =
      Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
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
        com.example.mealprep.household.testdata.SoftPreferencesReaderTestSupport.emptyProvider(),
        new com.example.mealprep.household.domain.service.internal.SoftPreferenceMerger(
            fixedClock));
  }

  // ---------------- createInvite ----------------

  @Test
  void createInvite_byPrimary_persistsAndPublishesEvent_andReturnsCode() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(primary));
    when(householdInviteRepository.saveAndFlush(any(HouseholdInvite.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    Instant expiresAt = fixedNow.plus(7, ChronoUnit.DAYS);
    HouseholdInviteDto created =
        service()
            .createInvite(
                householdId,
                actorId,
                new CreateInviteRequest(null, HouseholdRole.member, expiresAt));

    assertThat(created).isNotNull();
    assertThat(created.householdId()).isEqualTo(householdId);
    assertThat(created.intendedRole()).isEqualTo(HouseholdRole.member);
    assertThat(created.inviteCode()).isNotNull().hasSize(16);
    assertThat(created.expiresAt()).isEqualTo(expiresAt);
    assertThat(created.status()).isEqualTo(InviteStatus.PENDING);

    ArgumentCaptor<HouseholdInviteCreatedEvent> eventCaptor =
        ArgumentCaptor.forClass(HouseholdInviteCreatedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().householdId()).isEqualTo(householdId);
    assertThat(eventCaptor.getValue().issuedByUserId()).isEqualTo(actorId);
  }

  @Test
  void createInvite_capsExpiresAtAt30Days_silently() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(primary));
    when(householdInviteRepository.saveAndFlush(any(HouseholdInvite.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    Instant requested = fixedNow.plus(60, ChronoUnit.DAYS);
    HouseholdInviteDto created =
        service()
            .createInvite(
                householdId,
                actorId,
                new CreateInviteRequest(null, HouseholdRole.member, requested));

    Instant cap = fixedNow.plus(30, ChronoUnit.DAYS);
    assertThat(created.expiresAt()).isEqualTo(cap);
  }

  @Test
  void createInvite_byMember_throws403() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember nonPrimary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.member).build();
    nonPrimary.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(nonPrimary));

    assertThatThrownBy(
            () ->
                service()
                    .createInvite(
                        householdId,
                        actorId,
                        new CreateInviteRequest(
                            null, HouseholdRole.member, fixedNow.plus(1, ChronoUnit.DAYS))))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
  }

  @Test
  void createInvite_byUserNotInAnyHousehold_throws404() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .createInvite(
                        householdId,
                        actorId,
                        new CreateInviteRequest(
                            null, HouseholdRole.member, fixedNow.plus(1, ChronoUnit.DAYS))))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  @Test
  void createInvite_retriesOnInviteCodeCollision() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(primary));
    // First save throws collision; second succeeds.
    when(householdInviteRepository.saveAndFlush(any(HouseholdInvite.class)))
        .thenThrow(new DataIntegrityViolationException("uniq invite_code"))
        .thenAnswer(inv -> inv.getArgument(0));

    HouseholdInviteDto created =
        service()
            .createInvite(
                householdId,
                actorId,
                new CreateInviteRequest(
                    null, HouseholdRole.member, fixedNow.plus(1, ChronoUnit.DAYS)));

    assertThat(created.inviteCode()).hasSize(16);
    verify(householdInviteRepository, times(2)).saveAndFlush(any(HouseholdInvite.class));
  }

  // ---------------- listPendingInvites ----------------

  @Test
  void listPendingInvites_returnsCodeRedactedDtos() {
    UUID householdId = UUID.randomUUID();
    HouseholdInvite a =
        HouseholdTestData.invite()
            .withHouseholdId(householdId)
            .withInviteCode("AAAAAAAAAAAAAAAA")
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    HouseholdInvite b =
        HouseholdTestData.invite()
            .withHouseholdId(householdId)
            .withInviteCode("BBBBBBBBBBBBBBBB")
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository
            .findByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(
                householdId))
        .thenReturn(List.of(a, b));

    List<HouseholdInviteDto> dtos = service().listPendingInvites(householdId);

    assertThat(dtos).hasSize(2);
    assertThat(dtos).allMatch(d -> d.inviteCode() == null);
  }

  // ---------------- acceptInvite ----------------

  @Test
  void acceptInvite_happyPath_addsMemberAndStampsInvite_andPublishesEvent() {
    UUID accepterId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withHouseholdId(householdId)
            .withInviteCode("ACCEPTABLECODE12")
            .withIntendedRole(HouseholdRole.member)
            .withExpiresAt(fixedNow.plus(1, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findByInviteCode("ACCEPTABLECODE12"))
        .thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(accepterId)).thenReturn(Optional.empty());
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.of(household));
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(householdInviteRepository.saveAndFlush(any(HouseholdInvite.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    HouseholdMemberDto memberDto =
        service().acceptInvite(accepterId, new AcceptInviteRequest("ACCEPTABLECODE12"));

    assertThat(memberDto).isNotNull();
    assertThat(memberDto.userId()).isEqualTo(accepterId);
    assertThat(memberDto.role()).isEqualTo(HouseholdRole.member);
    assertThat(memberDto.priority()).isEqualTo(100);
    assertThat(memberDto.householdId()).isEqualTo(householdId);
    assertThat(invite.getAcceptedAt()).isEqualTo(fixedNow);
    assertThat(invite.getAcceptedByUserId()).isEqualTo(accepterId);

    ArgumentCaptor<HouseholdInviteAcceptedEvent> ec =
        ArgumentCaptor.forClass(HouseholdInviteAcceptedEvent.class);
    verify(eventPublisher).publishEvent(ec.capture());
    assertThat(ec.getValue().acceptedByUserId()).isEqualTo(accepterId);
    assertThat(ec.getValue().grantedRole()).isEqualTo(HouseholdRole.member);
  }

  @Test
  void acceptInvite_unknownCode_throws404() {
    when(householdInviteRepository.findByInviteCode("MISSINGCODEXXXX0"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .acceptInvite(UUID.randomUUID(), new AcceptInviteRequest("MISSINGCODEXXXX0")))
        .isInstanceOf(HouseholdInviteNotFoundException.class);
  }

  @Test
  void acceptInvite_revoked_throws410() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withInviteCode("REVOKEDCODEXXXX0")
            .withRevokedAt(fixedNow.minus(1, ChronoUnit.DAYS))
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findByInviteCode("REVOKEDCODEXXXX0"))
        .thenReturn(Optional.of(invite));

    assertThatThrownBy(
            () ->
                service()
                    .acceptInvite(UUID.randomUUID(), new AcceptInviteRequest("REVOKEDCODEXXXX0")))
        .isInstanceOf(HouseholdInviteRevokedException.class);
  }

  @Test
  void acceptInvite_expired_throws410() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withInviteCode("EXPIREDCODEXXXX0")
            .withExpiresAt(fixedNow.minus(1, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findByInviteCode("EXPIREDCODEXXXX0"))
        .thenReturn(Optional.of(invite));

    assertThatThrownBy(
            () ->
                service()
                    .acceptInvite(UUID.randomUUID(), new AcceptInviteRequest("EXPIREDCODEXXXX0")))
        .isInstanceOf(HouseholdInviteExpiredException.class);
  }

  @Test
  void acceptInvite_alreadyAccepted_throws409() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withInviteCode("USEDCODEXXXXXXX0")
            .withAcceptedAt(fixedNow.minus(1, ChronoUnit.DAYS))
            .withAcceptedByUserId(UUID.randomUUID())
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findByInviteCode("USEDCODEXXXXXXX0"))
        .thenReturn(Optional.of(invite));

    assertThatThrownBy(
            () ->
                service()
                    .acceptInvite(UUID.randomUUID(), new AcceptInviteRequest("USEDCODEXXXXXXX0")))
        .isInstanceOf(HouseholdInviteAlreadyAcceptedException.class);
  }

  @Test
  void acceptInvite_wrongRecipient_throws403() {
    UUID accepterId = UUID.randomUUID();
    UUID intendedRecipient = UUID.randomUUID();
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withInviteCode("WRONGRECIPIENT123")
            .withIssuedForUserId(intendedRecipient)
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findByInviteCode("WRONGRECIPIENT123"))
        .thenReturn(Optional.of(invite));

    assertThatThrownBy(
            () -> service().acceptInvite(accepterId, new AcceptInviteRequest("WRONGRECIPIENT123")))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
  }

  @Test
  void acceptInvite_accepterAlreadyInHousehold_throws409() {
    UUID accepterId = UUID.randomUUID();
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withInviteCode("DUPHOUSEHOLDCODE0")
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    HouseholdMember existing = HouseholdTestData.member().withUserId(accepterId).build();
    Household other = HouseholdTestData.household().build();
    existing.setHousehold(other);
    when(householdInviteRepository.findByInviteCode("DUPHOUSEHOLDCODE0"))
        .thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(accepterId)).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () -> service().acceptInvite(accepterId, new AcceptInviteRequest("DUPHOUSEHOLDCODE0")))
        .isInstanceOf(UserAlreadyInHouseholdException.class);
  }

  // ---------------- revokeInvite ----------------

  @Test
  void revokeInvite_byPrimary_setsRevokedAt() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withId(inviteId)
            .withHouseholdId(householdId)
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findById(inviteId)).thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(primary));
    when(householdInviteRepository.saveAndFlush(any(HouseholdInvite.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().revokeInvite(inviteId, actorId);

    assertThat(invite.getRevokedAt()).isEqualTo(fixedNow);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void revokeInvite_inviteFromOtherHousehold_throws404() {
    UUID actorId = UUID.randomUUID();
    UUID actorHouseholdId = UUID.randomUUID();
    UUID otherHouseholdId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    Household actorHousehold = HouseholdTestData.household().withId(actorHouseholdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(actorHousehold);
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withId(inviteId)
            .withHouseholdId(otherHouseholdId)
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findById(inviteId)).thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(primary));

    assertThatThrownBy(() -> service().revokeInvite(inviteId, actorId))
        .isInstanceOf(HouseholdInviteNotFoundException.class);
  }

  @Test
  void revokeInvite_byMember_throws403() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember nonPrimary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.member).build();
    nonPrimary.setHousehold(household);
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withId(inviteId)
            .withHouseholdId(householdId)
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findById(inviteId)).thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(nonPrimary));

    assertThatThrownBy(() -> service().revokeInvite(inviteId, actorId))
        .isInstanceOf(InsufficientHouseholdRoleException.class);
  }

  @Test
  void revokeInvite_alreadyAccepted_throws409() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withId(inviteId)
            .withHouseholdId(householdId)
            .withAcceptedAt(fixedNow.minus(1, ChronoUnit.HOURS))
            .withAcceptedByUserId(UUID.randomUUID())
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findById(inviteId)).thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(primary));

    assertThatThrownBy(() -> service().revokeInvite(inviteId, actorId))
        .isInstanceOf(HouseholdInviteAlreadyAcceptedException.class);
  }

  @Test
  void revokeInvite_alreadyRevoked_throws409() {
    UUID actorId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(householdId).build();
    HouseholdMember primary =
        HouseholdTestData.member().withUserId(actorId).withRole(HouseholdRole.primary).build();
    primary.setHousehold(household);
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withId(inviteId)
            .withHouseholdId(householdId)
            .withRevokedAt(fixedNow.minus(1, ChronoUnit.HOURS))
            .withExpiresAt(fixedNow.plus(7, ChronoUnit.DAYS))
            .build();
    when(householdInviteRepository.findById(inviteId)).thenReturn(Optional.of(invite));
    when(householdMemberRepository.findByUserId(actorId)).thenReturn(Optional.of(primary));

    assertThatThrownBy(() -> service().revokeInvite(inviteId, actorId))
        .isInstanceOf(HouseholdInviteAlreadyAcceptedException.class);
  }
}
