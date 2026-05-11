package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.MergeStrategy;
import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.api.dto.TasteProfileDocument;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapper;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.api.mapper.HouseholdMemberMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsMapper;
import com.example.mealprep.household.domain.entity.Household;
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
import com.example.mealprep.household.domain.service.internal.SoftPreferenceMerger;
import com.example.mealprep.household.exception.EmptyHouseholdMergeException;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.example.mealprep.household.testdata.SoftPreferencesReaderTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** Unit tests for the merge methods on {@link HouseholdServiceImpl}. */
@ExtendWith(MockitoExtension.class)
class HouseholdMergeServiceTest {

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

  private final SoftPreferenceMerger merger = new SoftPreferenceMerger(fixedClock);

  private HouseholdServiceImpl service(List<SoftPreferenceBundleDto> bundles) {
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
        SoftPreferencesReaderTestSupport.providerOf(
            SoftPreferencesReaderTestSupport.fixedReader(bundles)),
        merger);
  }

  @Test
  void mergeSoftPreferencesForSlot_nullEaters_resolvesAllMembers_returnsEmptyWithNoopReader() {
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
                    .withPriority(100)
                    .build())
            .withMember(
                HouseholdTestData.member()
                    .withUserId(u2)
                    .withRole(HouseholdRole.member)
                    .withPriority(200)
                    .build())
            .build();
    when(householdRepository.findWithMembersById(hh)).thenReturn(Optional.of(household));

    MergedSoftPreferencesDto out = service(List.of()).mergeSoftPreferencesForSlot(hh, null);

    assertThat(out.householdId()).isEqualTo(hh);
    assertThat(out.contributingUserIds()).containsExactlyInAnyOrder(u1, u2);
    assertThat(out.userIdsByPriority()).startsWith(u2); // priority 200 first
    assertThat(out.mergedTasteProfile().ingredientLikes()).isEmpty();
    assertThat(out.strategy()).isEqualTo(MergeStrategy.MEAN_WEIGHTED_BY_PRIORITY);
  }

  @Test
  void mergeSoftPreferencesForSlot_householdMissing_throws404() {
    UUID hh = UUID.randomUUID();
    when(householdRepository.findWithMembersById(hh)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service(List.of()).mergeSoftPreferencesForSlot(hh, null))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  @Test
  void mergeSoftPreferencesForSlot_emptyHousehold_throws422() {
    UUID hh = UUID.randomUUID();
    Household household = HouseholdTestData.household().withId(hh).build();
    when(householdRepository.findWithMembersById(hh)).thenReturn(Optional.of(household));

    assertThatThrownBy(() -> service(List.of()).mergeSoftPreferencesForSlot(hh, null))
        .isInstanceOf(EmptyHouseholdMergeException.class);
  }

  @Test
  void mergeSoftPreferencesForSlot_withFakeReader_producesNonEmpty() {
    UUID hh = UUID.randomUUID();
    UUID u1 = UUID.randomUUID();
    Household household =
        HouseholdTestData.household()
            .withId(hh)
            .withMember(
                HouseholdTestData.member()
                    .withUserId(u1)
                    .withRole(HouseholdRole.primary)
                    .withPriority(100)
                    .build())
            .build();
    when(householdRepository.findWithMembersById(hh)).thenReturn(Optional.of(household));

    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1,
            new TasteProfileDocument(Map.of("onion", new BigDecimal("0.7")), Map.of(), List.of()),
            null);

    MergedSoftPreferencesDto out =
        service(List.of(b1)).mergeSoftPreferencesForSlot(hh, List.of(u1));

    assertThat(out.mergedTasteProfile().ingredientLikes().get("onion").doubleValue())
        .isEqualTo(0.7d, org.assertj.core.data.Offset.offset(0.0001d));
  }

  @Test
  void mergeSoftPreferencesForUsers_mismatchedLengths_throws() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    assertThatThrownBy(
            () -> service(List.of()).mergeSoftPreferencesForUsers(List.of(u1, u2), List.of(100)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mergeSoftPreferencesForUsers_returnsNullHouseholdId() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    MergedSoftPreferencesDto out =
        service(List.of()).mergeSoftPreferencesForUsers(List.of(u1, u2), List.of(10, 20));

    assertThat(out.householdId()).isNull();
    assertThat(out.contributingUserIds()).containsExactly(u1, u2);
    assertThat(out.userIdsByPriority()).containsExactly(u2, u1); // 20 > 10
  }
}
