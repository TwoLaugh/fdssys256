package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
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
import com.example.mealprep.household.event.HouseholdCreatedEvent;
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

/**
 * Unit test for {@link HouseholdServiceImpl}. Repositories and event publisher are mocked at the
 * module boundary; the real {@link HouseholdMapper} (MapStruct-generated) is used because it is
 * deterministic, no-I/O, and central to behaviour.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdServiceImplTest {

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

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);

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

  // ---------------- getById ----------------

  @Test
  void getById_whenPresent_returnsDto() {
    UUID userId = UUID.randomUUID();
    Household household =
        HouseholdTestData.household()
            .withCreatedByUserId(userId)
            .withMember(
                HouseholdTestData.member()
                    .withUserId(userId)
                    .withRole(HouseholdRole.primary)
                    .build())
            .build();
    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));

    Optional<HouseholdDto> result = service().getById(household.getId());

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(household.getId());
    assertThat(result.get().members()).hasSize(1);
    assertThat(result.get().members().get(0).role()).isEqualTo(HouseholdRole.primary);
  }

  @Test
  void getById_whenAbsent_returnsEmpty() {
    UUID id = UUID.randomUUID();
    when(householdRepository.findWithMembersById(id)).thenReturn(Optional.empty());

    assertThat(service().getById(id)).isEmpty();
  }

  // ---------------- getByUserId ----------------

  @Test
  void getByUserId_whenMembershipMissing_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(userId)).thenReturn(Optional.empty());

    Optional<HouseholdDto> result = service().getByUserId(userId);

    assertThat(result).isEmpty();
    verifyNoInteractions(householdRepository);
  }

  @Test
  void getByUserId_whenMembershipPresent_returnsHouseholdWithMembers() {
    UUID userId = UUID.randomUUID();
    Household household = HouseholdTestData.household().withCreatedByUserId(userId).build();
    HouseholdMember member =
        HouseholdTestData.member().withUserId(userId).withRole(HouseholdRole.primary).build();
    member.setHousehold(household);
    household.getMembers().add(member);

    when(householdMemberRepository.findByUserId(userId)).thenReturn(Optional.of(member));
    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));

    Optional<HouseholdDto> result = service().getByUserId(userId);

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(household.getId());
    assertThat(result.get().members()).hasSize(1);
    assertThat(result.get().members().get(0).userId()).isEqualTo(userId);
  }

  // ---------------- createHousehold ----------------

  @Test
  void createHousehold_persistsHouseholdAndPrimaryMember_andPublishesEvent() {
    UUID creatorUserId = UUID.randomUUID();
    when(householdMemberRepository.findByUserId(creatorUserId)).thenReturn(Optional.empty());
    when(householdRepository.saveAndFlush(any(Household.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    HouseholdDto result =
        service().createHousehold(creatorUserId, new CreateHouseholdRequest("Smith Family"));

    ArgumentCaptor<Household> householdCaptor = ArgumentCaptor.forClass(Household.class);
    verify(householdRepository).saveAndFlush(householdCaptor.capture());
    Household saved = householdCaptor.getValue();
    assertThat(saved.getName()).isEqualTo("Smith Family");
    assertThat(saved.getCreatedByUserId()).isEqualTo(creatorUserId);
    assertThat(saved.getMembers()).hasSize(1);
    HouseholdMember primary = saved.getMembers().get(0);
    assertThat(primary.getUserId()).isEqualTo(creatorUserId);
    assertThat(primary.getRole()).isEqualTo(HouseholdRole.primary);
    assertThat(primary.getPriority()).isEqualTo(100);
    assertThat(primary.getDisplayName()).isNull();
    assertThat(primary.getJoinedAt()).isEqualTo(Instant.parse("2026-05-08T10:00:00Z"));

    ArgumentCaptor<HouseholdCreatedEvent> eventCaptor =
        ArgumentCaptor.forClass(HouseholdCreatedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    HouseholdCreatedEvent event = eventCaptor.getValue();
    assertThat(event.householdId()).isEqualTo(saved.getId());
    assertThat(event.createdByUserId()).isEqualTo(creatorUserId);
    assertThat(event.traceId()).isNotNull();
    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-05-08T10:00:00Z"));
    assertThat(event.scopeKind()).isEqualTo("household");
    assertThat(event.scopeId()).isEqualTo(saved.getId());

    assertThat(result.id()).isEqualTo(saved.getId());
    assertThat(result.name()).isEqualTo("Smith Family");
    assertThat(result.createdByUserId()).isEqualTo(creatorUserId);
    assertThat(result.members()).hasSize(1);
  }

  @Test
  void createHousehold_whenUserAlreadyMember_throws409_andDoesNotWrite() {
    UUID creatorUserId = UUID.randomUUID();
    HouseholdMember existing = HouseholdTestData.member().withUserId(creatorUserId).build();
    Household existingHousehold = HouseholdTestData.household().build();
    existing.setHousehold(existingHousehold);
    when(householdMemberRepository.findByUserId(creatorUserId)).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service()
                    .createHousehold(creatorUserId, new CreateHouseholdRequest("Smith Family")))
        .isInstanceOf(UserAlreadyInHouseholdException.class);

    verify(householdRepository, never()).saveAndFlush(any(Household.class));
    verifyNoInteractions(eventPublisher);
  }
}
