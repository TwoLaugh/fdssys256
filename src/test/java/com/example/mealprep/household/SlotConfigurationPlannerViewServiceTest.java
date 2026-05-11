package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.PlannerSlotEntryDto;
import com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapper;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.api.mapper.HouseholdMemberMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsMapper;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.entity.HouseholdSettings;
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
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.HouseholdSettingsNotFoundException;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit test for the 01f {@code getSlotConfigurationPlannerView} service method. Mirrors the
 * structure of {@code HouseholdServiceImplTest} (mock repos at the module boundary; real mappers /
 * resolver / differ).
 */
@ExtendWith(MockitoExtension.class)
class SlotConfigurationPlannerViewServiceTest {

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

  private HouseholdSettings settingsFor(UUID householdId) {
    return HouseholdSettings.builder()
        .id(UUID.randomUUID())
        .householdId(householdId)
        .document(HouseholdTestData.defaultDocument())
        .build();
  }

  @Test
  void getSlotConfigurationPlannerView_happyPath_returnsFlattenedSlotsAndPriorityOrdering() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    HouseholdMember memberA =
        HouseholdTestData.member()
            .withUserId(userA)
            .withRole(HouseholdRole.primary)
            .withPriority(200)
            .build();
    HouseholdMember memberB =
        HouseholdTestData.member()
            .withUserId(userB)
            .withRole(HouseholdRole.member)
            .withPriority(100)
            .build();
    Household household =
        HouseholdTestData.household().withMember(memberA).withMember(memberB).build();

    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(household.getId()))
        .thenReturn(Optional.of(settingsFor(household.getId())));

    SlotConfigurationPlannerViewDto result =
        service().getSlotConfigurationPlannerView(household.getId());

    assertThat(result.householdId()).isEqualTo(household.getId());
    // Four built-in slots: breakfast/lunch/dinner/snack from default document.
    assertThat(result.slots()).hasSize(4);
    assertThat(result.slots())
        .allSatisfy(
            slot -> {
              assertThat(slot.shared()).isTrue();
              assertThat(slot.headcount()).isEqualTo(1);
              assertThat(slot.timeBudgetMin()).isEqualTo(30);
              assertThat(slot.eaterUserIdsIfPerPerson()).isNull();
              // cuisinePreferenceWeight: always null in v1.
              assertThat(slot.cuisinePreferenceWeight()).isNull();
            });
    // Priority DESC: A (200) before B (100).
    assertThat(result.eaterUserIdsByPriority()).containsExactly(userA, userB);
    assertThat(result.allEaterUserIds()).containsExactlyInAnyOrder(userA, userB);
    assertThat(result.mealTimingWindowStart()).isNull();
    assertThat(result.mealTimingWindowEnd()).isNull();
    assertThat(result.generatedAt()).isEqualTo(Instant.parse("2026-05-08T10:00:00Z"));
  }

  @Test
  void getSlotConfigurationPlannerView_zeroMembers_returnsEmptyEaterListsAndPopulatedSlots() {
    Household household = HouseholdTestData.household().build(); // no members
    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(household.getId()))
        .thenReturn(Optional.of(settingsFor(household.getId())));

    SlotConfigurationPlannerViewDto result =
        service().getSlotConfigurationPlannerView(household.getId());

    assertThat(result.allEaterUserIds()).isEmpty();
    assertThat(result.eaterUserIdsByPriority()).isEmpty();
    // Slots still populated from defaults — the "empty plan" case is a read, not an exception.
    assertThat(result.slots()).hasSize(4);
  }

  @Test
  void getSlotConfigurationPlannerView_householdMissing_throws404() {
    UUID householdId = UUID.randomUUID();
    when(householdRepository.findWithMembersById(householdId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getSlotConfigurationPlannerView(householdId))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  @Test
  void getSlotConfigurationPlannerView_settingsMissing_throws404() {
    Household household =
        HouseholdTestData.household().withMember(HouseholdTestData.member().build()).build();
    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(household.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getSlotConfigurationPlannerView(household.getId()))
        .isInstanceOf(HouseholdSettingsNotFoundException.class);
  }

  @Test
  void getSlotConfigurationPlannerView_priorityTieBreaksOnUuidAscending() {
    // Construct three members: (priority=100, uuid-A), (priority=200, uuid-B),
    // (priority=100, uuid-C), with A < C lexically. Expected ordering: [B, A, C].
    UUID uuidA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID uuidB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID uuidC = UUID.fromString("00000000-0000-0000-0000-000000000003");
    HouseholdMember a = HouseholdTestData.member().withUserId(uuidA).withPriority(100).build();
    HouseholdMember b = HouseholdTestData.member().withUserId(uuidB).withPriority(200).build();
    HouseholdMember c = HouseholdTestData.member().withUserId(uuidC).withPriority(100).build();
    Household household =
        HouseholdTestData.household().withMember(a).withMember(b).withMember(c).build();

    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(household.getId()))
        .thenReturn(Optional.of(settingsFor(household.getId())));

    SlotConfigurationPlannerViewDto result =
        service().getSlotConfigurationPlannerView(household.getId());

    assertThat(result.eaterUserIdsByPriority()).containsExactly(uuidB, uuidA, uuidC);
  }

  @Test
  void getSlotConfigurationPlannerView_determinism_twoCallsProduceEqualSnapshots() {
    UUID userA = UUID.randomUUID();
    HouseholdMember memberA =
        HouseholdTestData.member().withUserId(userA).withPriority(100).build();
    Household household = HouseholdTestData.household().withMember(memberA).build();

    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(household.getId()))
        .thenReturn(Optional.of(settingsFor(household.getId())));

    SlotConfigurationPlannerViewDto first =
        service().getSlotConfigurationPlannerView(household.getId());
    SlotConfigurationPlannerViewDto second =
        service().getSlotConfigurationPlannerView(household.getId());

    // Every field except generatedAt is byte-identical (clock is fixed so even that matches).
    assertThat(first.householdId()).isEqualTo(second.householdId());
    assertThat(first.slots()).isEqualTo(second.slots());
    assertThat(first.allEaterUserIds()).isEqualTo(second.allEaterUserIds());
    assertThat(first.eaterUserIdsByPriority()).isEqualTo(second.eaterUserIdsByPriority());
    assertThat(first.mealTimingWindowStart()).isEqualTo(second.mealTimingWindowStart());
    assertThat(first.mealTimingWindowEnd()).isEqualTo(second.mealTimingWindowEnd());
  }

  @Test
  void getSlotConfigurationPlannerView_perPersonSlotsCarryFullMemberList() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    HouseholdMember memberA = HouseholdTestData.member().withUserId(userA).build();
    HouseholdMember memberB = HouseholdTestData.member().withUserId(userB).build();
    Household household =
        HouseholdTestData.household().withMember(memberA).withMember(memberB).build();

    // Custom per-person settings: override one slot to shared=false.
    java.util.Map<
            SlotKind,
            com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault>
        slotDefaults = new java.util.LinkedHashMap<>();
    slotDefaults.put(
        SlotKind.breakfast,
        new com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault(
            false, 2, 45));
    com.example.mealprep.household.domain.entity.HouseholdSettingsDocument doc =
        new com.example.mealprep.household.domain.entity.HouseholdSettingsDocument(
            slotDefaults,
            new ArrayList<>(),
            null,
            new com.example.mealprep.household.domain.entity.HouseholdSettingsDocument
                .HouseholdSchedulingPreferences());
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(household.getId())
            .document(doc)
            .build();

    when(householdRepository.findWithMembersById(household.getId()))
        .thenReturn(Optional.of(household));
    when(householdSettingsRepository.findByHouseholdId(household.getId()))
        .thenReturn(Optional.of(settings));

    SlotConfigurationPlannerViewDto result =
        service().getSlotConfigurationPlannerView(household.getId());

    assertThat(result.slots()).hasSize(1);
    PlannerSlotEntryDto breakfast = result.slots().get(0);
    assertThat(breakfast.kind()).isEqualTo(SlotKind.breakfast);
    assertThat(breakfast.shared()).isFalse();
    assertThat(breakfast.headcount()).isEqualTo(2);
    assertThat(breakfast.timeBudgetMin()).isEqualTo(45);
    assertThat(breakfast.eaterUserIdsIfPerPerson()).containsExactlyInAnyOrder(userA, userB);
    assertThat(breakfast.cuisinePreferenceWeight()).isNull();
  }
}
