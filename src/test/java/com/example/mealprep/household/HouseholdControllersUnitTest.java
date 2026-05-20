package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.household.api.controller.HouseholdInvitesController;
import com.example.mealprep.household.api.controller.HouseholdMembersController;
import com.example.mealprep.household.api.controller.HouseholdMergeController;
import com.example.mealprep.household.api.controller.HouseholdSettingsController;
import com.example.mealprep.household.api.controller.HouseholdSlotConfigurationPlannerViewController;
import com.example.mealprep.household.api.controller.HouseholdsController;
import com.example.mealprep.household.api.dto.AcceptInviteRequest;
import com.example.mealprep.household.api.dto.AddMemberRequest;
import com.example.mealprep.household.api.dto.ChangeRoleRequest;
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.CreateInviteRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdInviteDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsAuditEntryDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.InviteStatus;
import com.example.mealprep.household.api.dto.MergeSoftPreferencesRequest;
import com.example.mealprep.household.api.dto.MergeStrategy;
import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import com.example.mealprep.household.api.dto.SlotConfigurationDto;
import com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto;
import com.example.mealprep.household.api.dto.UpdateHouseholdSettingsRequest;
import com.example.mealprep.household.api.dto.UpdateMemberRequest;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdMergeService;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.testdata.HouseholdTestData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure-unit tests for the six household REST controllers. Mocks the service-tier seam and the
 * {@link CurrentUserResolver}; asserts every method's HTTP-status, body, Location header (where
 * applicable), and the value forwarded to the service layer. Targets the previously-uncovered
 * controller package (37 of 47 mutants were NO_COVERAGE in the baseline).
 *
 * <p>No Spring context — DECISION-LOG-0014's multi-interface @MockBean eviction trap means we use
 * straight Mockito mocks against the {@link HouseholdQueryService}, {@link HouseholdUpdateService},
 * and {@link HouseholdMergeService} interfaces (the household service impl implements all three).
 */
@ExtendWith(MockitoExtension.class)
class HouseholdControllersUnitTest {

  @Mock private HouseholdQueryService queryService;
  @Mock private HouseholdUpdateService updateService;
  @Mock private HouseholdMergeService mergeService;
  @Mock private CurrentUserResolver currentUserResolver;

  private static HouseholdDto sampleHousehold(UUID householdId, UUID userId) {
    HouseholdMemberDto member =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            householdId,
            userId,
            HouseholdRole.primary,
            null,
            100,
            Instant.parse("2026-05-09T00:00:00Z"),
            0L);
    return new HouseholdDto(
        householdId,
        "Smith Family",
        userId,
        List.of(member),
        Instant.parse("2026-05-09T00:00:00Z"),
        0L);
  }

  private static HouseholdMemberDto sampleMember(UUID memberId, UUID userId, UUID householdId) {
    return new HouseholdMemberDto(
        memberId,
        householdId,
        userId,
        HouseholdRole.member,
        null,
        100,
        Instant.parse("2026-05-09T00:00:00Z"),
        0L);
  }

  // ============================================================================================
  // HouseholdsController — POST / and GET /current
  // ============================================================================================

  private HouseholdsController householdsController() {
    return new HouseholdsController(queryService, updateService, currentUserResolver);
  }

  /**
   * kills HouseholdsController.java create() NullReturnVals + verifies the 201 + Location header.
   */
  @Test
  void householdsController_create_returns201_withLocationHeader_andDelegatesToUpdateService() {
    UUID creatorUserId = UUID.randomUUID();
    UUID newHouseholdId = UUID.randomUUID();
    HouseholdDto created = sampleHousehold(newHouseholdId, creatorUserId);
    CreateHouseholdRequest req = HouseholdTestData.createRequest("Family");
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(creatorUserId));
    when(updateService.createHousehold(creatorUserId, req)).thenReturn(created);

    ResponseEntity<HouseholdDto> response = householdsController().create(req);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(created);
    assertThat(response.getHeaders().getLocation())
        .isNotNull()
        .satisfies(
            uri -> assertThat(uri.toString()).isEqualTo("/api/v1/households/" + newHouseholdId));
    verify(updateService, times(1)).createHousehold(creatorUserId, req);
  }

  /** kills requireCurrentUserId lambda — unauthenticated POST returns 401. */
  @Test
  void householdsController_create_unauthenticated_throws401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    CreateHouseholdRequest req = HouseholdTestData.createRequest();
    assertThatThrownBy(() -> householdsController().create(req))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(updateService);
  }

  @Test
  void householdsController_getCurrent_present_returnsBody() {
    UUID userId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    when(queryService.getByUserId(userId))
        .thenReturn(Optional.of(sampleHousehold(householdId, userId)));

    HouseholdDto dto = householdsController().getCurrent();

    assertThat(dto.id()).isEqualTo(householdId);
    assertThat(dto.createdByUserId()).isEqualTo(userId);
  }

  @Test
  void householdsController_getCurrent_absent_throws404() {
    UUID userId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    when(queryService.getByUserId(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> householdsController().getCurrent())
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  @Test
  void householdsController_getCurrent_unauthenticated_throws401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> householdsController().getCurrent())
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================================================================================
  // HouseholdSettingsController — GET /settings, PUT /settings, GET /audit-log, GET /slot-config
  // ============================================================================================

  private HouseholdSettingsController settingsController() {
    return new HouseholdSettingsController(queryService, updateService, currentUserResolver);
  }

  /**
   * kills HouseholdSettingsController.java:60 NullReturnVals — getSettings forwards the caller's
   * resolved userId and returns the DTO from the service when present.
   */
  @Test
  void settingsController_getSettings_present_returnsDto_andPassesCallerUserIdThrough() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    HouseholdSettingsDto dto =
        new HouseholdSettingsDto(
            UUID.randomUUID(),
            householdId,
            HouseholdTestData.defaultDocument(),
            0L,
            Instant.parse("2026-05-09T00:00:00Z"));
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getSettings(householdId, callerUserId)).thenReturn(Optional.of(dto));

    HouseholdSettingsDto result = settingsController().getSettings(householdId);

    assertThat(result).isSameAs(dto);
    verify(queryService).getSettings(householdId, callerUserId);
  }

  /** kills lambda$getSettings$ NullReturnVals — absent settings -> HouseholdSettingsNotFound. */
  @Test
  void settingsController_getSettings_absent_throwsHouseholdSettingsNotFound() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getSettings(householdId, callerUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> settingsController().getSettings(householdId))
        .isInstanceOf(
            com.example.mealprep.household.exception.HouseholdSettingsNotFoundException.class);
  }

  /** kills updateSettings NullReturnVals + that the service returns its actual DTO. */
  @Test
  void settingsController_updateSettings_returnsServiceDto_andPassesCallerUserIdThrough() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UpdateHouseholdSettingsRequest req =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);
    HouseholdSettingsDto returned =
        new HouseholdSettingsDto(
            UUID.randomUUID(),
            householdId,
            HouseholdTestData.defaultDocument(),
            1L,
            Instant.parse("2026-05-09T00:00:00Z"));
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(updateService.updateSettings(householdId, callerUserId, req)).thenReturn(returned);

    HouseholdSettingsDto result = settingsController().updateSettings(householdId, req);

    assertThat(result).isSameAs(returned);
    verify(updateService).updateSettings(householdId, callerUserId, req);
  }

  /**
   * kills HouseholdSettingsController.java:104 ConditionalsBoundary + NegateConditionals on the
   * clampPageSize {@code size < 1} guard. At size == 0 the controller MUST substitute DEFAULT_PAGE_
   * SIZE (20). At size == 1 the controller uses 1. We assert by inspecting the Pageable forwarded
   * to the service.
   */
  @Test
  void settingsController_getSettingsAuditLog_sizeZero_clampsToDefaultPageSize20() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    Page<HouseholdSettingsAuditEntryDto> empty = new PageImpl<>(List.of());
    when(queryService.getSettingsAuditLog(eq(householdId), eq(callerUserId), any(Pageable.class)))
        .thenReturn(empty);

    Page<HouseholdSettingsAuditEntryDto> result =
        settingsController().getSettingsAuditLog(householdId, 0, 0);

    // kills HouseholdSettingsController.java:86 NullReturnVals on getSettingsAuditLog — the
    // returned Page must be the EXACT Page the service produced (a null-return mutant would NPE
    // here).
    assertThat(result).isSameAs(empty);
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(queryService).getSettingsAuditLog(eq(householdId), eq(callerUserId), captor.capture());
    assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
  }

  /** size == 1 boundary — must NOT clamp to 20. Pins the other side of {@code size < 1}. */
  @Test
  void settingsController_getSettingsAuditLog_sizeOne_usesOne() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    Page<HouseholdSettingsAuditEntryDto> empty = new PageImpl<>(List.of());
    when(queryService.getSettingsAuditLog(eq(householdId), eq(callerUserId), any(Pageable.class)))
        .thenReturn(empty);

    settingsController().getSettingsAuditLog(householdId, 0, 1);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(queryService).getSettingsAuditLog(eq(householdId), eq(callerUserId), captor.capture());
    assertThat(captor.getValue().getPageSize()).isEqualTo(1);
  }

  /**
   * kills HouseholdSettingsController.java:105 PrimitiveReturns + Math.min ceiling. size > MAX
   * (100) clamped down to MAX. size == 100 must pass through as 100.
   */
  @Test
  void settingsController_getSettingsAuditLog_sizeAbove100_clampedTo100() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    Page<HouseholdSettingsAuditEntryDto> empty = new PageImpl<>(List.of());
    when(queryService.getSettingsAuditLog(eq(householdId), eq(callerUserId), any(Pageable.class)))
        .thenReturn(empty);

    settingsController().getSettingsAuditLog(householdId, 0, 500);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(queryService).getSettingsAuditLog(eq(householdId), eq(callerUserId), captor.capture());
    assertThat(captor.getValue().getPageSize()).isEqualTo(100);
  }

  /** kills negative-page guard {@code Math.max(0, page)} — a negative page becomes 0. */
  @Test
  void settingsController_getSettingsAuditLog_negativePage_clampedToZero() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    Page<HouseholdSettingsAuditEntryDto> empty = new PageImpl<>(List.of());
    when(queryService.getSettingsAuditLog(eq(householdId), eq(callerUserId), any(Pageable.class)))
        .thenReturn(empty);

    settingsController().getSettingsAuditLog(householdId, -5, 50);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(queryService).getSettingsAuditLog(eq(householdId), eq(callerUserId), captor.capture());
    assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
    assertThat(captor.getValue().getPageSize()).isEqualTo(50);
  }

  /** kills HouseholdSettingsController.java:93 getSlotConfiguration NullReturnVals. */
  @Test
  void settingsController_getSlotConfiguration_returnsServiceDto() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    SlotConfigurationDto dto = new SlotConfigurationDto(householdId, List.of(), List.of());
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getSlotConfiguration(householdId, callerUserId)).thenReturn(dto);

    SlotConfigurationDto result = settingsController().getSlotConfiguration(householdId);

    assertThat(result).isSameAs(dto);
  }

  /** kills requireCurrentUserId on the settings controller. */
  @Test
  void settingsController_unauthenticated_throws401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> settingsController().getSettings(id))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> settingsController().getSlotConfiguration(id))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                settingsController()
                    .updateSettings(
                        id,
                        new UpdateHouseholdSettingsRequest(
                            HouseholdTestData.defaultDocument(), 0L)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> settingsController().getSettingsAuditLog(id, 0, 20))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================================================================================
  // HouseholdMembersController — POST/PATCH/DELETE/POST role
  // ============================================================================================

  private HouseholdMembersController membersController() {
    return new HouseholdMembersController(queryService, updateService, currentUserResolver);
  }

  /** kills HouseholdMembersController.java:70 NullReturnVals + the 201 + Location pattern. */
  @Test
  void membersController_add_returns201_withLocationHeader_andDelegatesToService() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    AddMemberRequest req = HouseholdTestData.addMemberRequest(targetUserId);
    HouseholdMemberDto created = sampleMember(memberId, targetUserId, householdId);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));
    when(updateService.addMember(householdId, callerUserId, req)).thenReturn(created);

    ResponseEntity<HouseholdMemberDto> response = membersController().add(req);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(created);
    assertThat(response.getHeaders().getLocation().toString())
        .isEqualTo("/api/v1/households/current/members/" + memberId);
  }

  /** kills HouseholdMembersController.java:124 (resolveCurrentHouseholdId orElseThrow). */
  @Test
  void membersController_add_callerHasNoHousehold_throws404() {
    UUID callerUserId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId)).thenReturn(Optional.empty());

    AddMemberRequest req = HouseholdTestData.addMemberRequest(UUID.randomUUID());
    assertThatThrownBy(() -> membersController().add(req))
        .isInstanceOf(HouseholdNotFoundException.class);
    verifyNoInteractions(updateService);
  }

  /** kills HouseholdMembersController.java:86 NullReturnVals + delegates to the service. */
  @Test
  void membersController_update_returnsDtoFromService() {
    UUID callerUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UpdateMemberRequest req = HouseholdTestData.updateMemberRequest(200, "Renamed", 0L);
    HouseholdMemberDto returned = sampleMember(memberId, UUID.randomUUID(), UUID.randomUUID());
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(updateService.updateMember(memberId, callerUserId, req)).thenReturn(returned);

    HouseholdMemberDto result = membersController().update(memberId, req);

    assertThat(result).isSameAs(returned);
    verify(updateService).updateMember(memberId, callerUserId, req);
  }

  /**
   * kills HouseholdMembersController.java:96 VoidMethodCall on removeMember + :97 NullReturnVals on
   * the 204 ResponseEntity build.
   */
  @Test
  void membersController_remove_returns204_andInvokesService() {
    UUID callerUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));

    ResponseEntity<Void> response = membersController().remove(memberId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getBody()).isNull();
    verify(updateService).removeMember(memberId, callerUserId);
  }

  /** kills HouseholdMembersController.java:108 NullReturnVals on changeRole. */
  @Test
  void membersController_changeRole_returnsServiceDto() {
    UUID callerUserId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    ChangeRoleRequest req = HouseholdTestData.changeRoleRequest(HouseholdRole.primary, 0L);
    HouseholdMemberDto returned = sampleMember(memberId, UUID.randomUUID(), UUID.randomUUID());
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(updateService.changeRole(memberId, callerUserId, req)).thenReturn(returned);

    HouseholdMemberDto result = membersController().changeRole(memberId, req);

    assertThat(result).isSameAs(returned);
    verify(updateService).changeRole(memberId, callerUserId, req);
  }

  /** kills requireCurrentUserId on the members controller. */
  @Test
  void membersController_unauthenticated_allEndpoints_throw401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());
    UUID memberId = UUID.randomUUID();
    AddMemberRequest addReq = HouseholdTestData.addMemberRequest(UUID.randomUUID());
    UpdateMemberRequest updReq = HouseholdTestData.updateMemberRequest(0L);
    ChangeRoleRequest roleReq = HouseholdTestData.changeRoleRequest(HouseholdRole.member, 0L);

    assertThatThrownBy(() -> membersController().add(addReq))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> membersController().update(memberId, updReq))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> membersController().remove(memberId))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> membersController().changeRole(memberId, roleReq))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================================================================================
  // HouseholdInvitesController — POST / GET /current/invites, DELETE / POST /invites/accept
  // ============================================================================================

  private HouseholdInvitesController invitesController() {
    return new HouseholdInvitesController(queryService, updateService, currentUserResolver);
  }

  private HouseholdInviteDto sampleInvite(UUID inviteId, UUID householdId) {
    return new HouseholdInviteDto(
        inviteId,
        householdId,
        "TESTCODE12345678",
        UUID.randomUUID(),
        null,
        HouseholdRole.member,
        Instant.parse("2026-06-09T00:00:00Z"),
        null,
        null,
        InviteStatus.PENDING);
  }

  /**
   * kills HouseholdInvitesController.java:69 EmptyObjectReturnVals on listPending — the controller
   * must return the actual list from the service (a mutant returning empty list would be a leak).
   */
  @Test
  void invitesController_listPending_returnsListFromService() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));
    HouseholdInviteDto inviteDto = sampleInvite(inviteId, householdId);
    when(queryService.listPendingInvites(householdId)).thenReturn(List.of(inviteDto));

    List<HouseholdInviteDto> result = invitesController().listPending();

    assertThat(result).containsExactly(inviteDto);
  }

  /** kills HouseholdInvitesController.java:82 NullReturnVals + verifies 201+Location. */
  @Test
  void invitesController_create_returns201_withLocationHeader_andDelegatesToService() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));
    CreateInviteRequest req =
        new CreateInviteRequest(null, HouseholdRole.member, Instant.parse("2026-06-09T00:00:00Z"));
    HouseholdInviteDto created = sampleInvite(inviteId, householdId);
    when(updateService.createInvite(householdId, callerUserId, req)).thenReturn(created);

    ResponseEntity<HouseholdInviteDto> response = invitesController().create(req);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(created);
    assertThat(response.getHeaders().getLocation().toString())
        .isEqualTo("/api/v1/households/current/invites/" + inviteId);
  }

  /**
   * kills HouseholdInvitesController.java:91 VoidMethodCall on revokeInvite + :92 NullReturnVals.
   */
  @Test
  void invitesController_revoke_returns204_andInvokesService() {
    UUID callerUserId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));

    ResponseEntity<Void> response = invitesController().revoke(inviteId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getBody()).isNull();
    verify(updateService).revokeInvite(inviteId, callerUserId);
  }

  /** kills HouseholdInvitesController.java:102 NullReturnVals on accept. */
  @Test
  void invitesController_accept_returnsServiceDto() {
    UUID accepterUserId = UUID.randomUUID();
    AcceptInviteRequest req = new AcceptInviteRequest("ACCEPTCODE123456");
    HouseholdMemberDto returned =
        sampleMember(UUID.randomUUID(), accepterUserId, UUID.randomUUID());
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(accepterUserId));
    when(updateService.acceptInvite(accepterUserId, req)).thenReturn(returned);

    HouseholdMemberDto result = invitesController().accept(req);

    assertThat(result).isSameAs(returned);
    verify(updateService).acceptInvite(accepterUserId, req);
  }

  /** kills lambda$resolveCurrentHouseholdId$ NullReturnVals (caller without a household). */
  @Test
  void invitesController_listPending_callerHasNoHousehold_throws404() {
    UUID callerUserId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> invitesController().listPending())
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  /** kills lambda$resolveCurrentHouseholdId$ on create. */
  @Test
  void invitesController_create_callerHasNoHousehold_throws404() {
    UUID callerUserId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId)).thenReturn(Optional.empty());

    CreateInviteRequest req =
        new CreateInviteRequest(null, HouseholdRole.member, Instant.parse("2026-06-09T00:00:00Z"));
    assertThatThrownBy(() -> invitesController().create(req))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  /** kills requireCurrentUserId on the invites controller. */
  @Test
  void invitesController_unauthenticated_allEndpoints_throw401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());
    UUID inviteId = UUID.randomUUID();
    CreateInviteRequest createReq =
        new CreateInviteRequest(null, HouseholdRole.member, Instant.parse("2026-06-09T00:00:00Z"));
    AcceptInviteRequest acceptReq = new AcceptInviteRequest("CODECODECODE1234");

    assertThatThrownBy(() -> invitesController().listPending())
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> invitesController().create(createReq))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> invitesController().revoke(inviteId))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> invitesController().accept(acceptReq))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================================================================================
  // HouseholdMergeController — POST /current/merge
  // ============================================================================================

  private HouseholdMergeController mergeController() {
    return new HouseholdMergeController(queryService, mergeService, currentUserResolver);
  }

  private MergedSoftPreferencesDto sampleMerged(UUID householdId, List<UUID> userIds) {
    return new MergedSoftPreferencesDto(
        householdId,
        userIds,
        new com.example.mealprep.household.api.dto.TasteProfileDocument(
            java.util.Map.of(), java.util.Map.of(), List.of()),
        new com.example.mealprep.household.api.dto.LifestyleConfigDocument(null, null, null, false),
        userIds,
        MergeStrategy.MEAN_WEIGHTED_BY_PRIORITY,
        Instant.parse("2026-05-09T12:00:00Z"));
  }

  /** kills HouseholdMergeController.java requireCurrentUserId / lambda$merge$ + merge() flow. */
  @Test
  void mergeController_merge_nullEaterIds_passesNullThroughAndReturnsServiceDto() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));
    MergedSoftPreferencesDto serviceResp = sampleMerged(householdId, List.of(callerUserId));
    when(mergeService.mergeSoftPreferencesForSlot(householdId, null)).thenReturn(serviceResp);

    MergedSoftPreferencesDto result =
        mergeController().merge(new MergeSoftPreferencesRequest(null));

    assertThat(result).isSameAs(serviceResp);
    verify(mergeService).mergeSoftPreferencesForSlot(householdId, null);
  }

  /**
   * kills HouseholdMergeController.java:64 NegateConditionals — empty list takes the
   * "skip-validation" branch (no membership check, passes empty through).
   */
  @Test
  void mergeController_merge_emptyEaterList_skipsValidation_forwardsEmpty() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));
    MergedSoftPreferencesDto serviceResp = sampleMerged(householdId, List.of(callerUserId));
    when(mergeService.mergeSoftPreferencesForSlot(householdId, List.of())).thenReturn(serviceResp);

    MergedSoftPreferencesDto result =
        mergeController().merge(new MergeSoftPreferencesRequest(List.of()));

    assertThat(result).isSameAs(serviceResp);
    verify(mergeService).mergeSoftPreferencesForSlot(householdId, List.of());
  }

  /**
   * kills HouseholdMergeController.java:70 NegateConditionals on the {@code !contains}
   * member-validation. A requested userId NOT in the household must surface as 403
   * (InsufficientRole).
   */
  @Test
  void mergeController_merge_eaterNotInHousehold_throwsInsufficientRole() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    UUID outsiderUserId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));

    assertThatThrownBy(
            () -> mergeController().merge(new MergeSoftPreferencesRequest(List.of(outsiderUserId))))
        .isInstanceOf(InsufficientHouseholdRoleException.class)
        .hasMessageContaining(outsiderUserId.toString());
    verifyNoInteractions(mergeService);
  }

  /** Sane-eaters happy path — every requested user IS a member, request reaches service. */
  @Test
  void mergeController_merge_eatersAreAllMembers_forwardsListToService() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));
    MergedSoftPreferencesDto serviceResp = sampleMerged(householdId, List.of(callerUserId));
    when(mergeService.mergeSoftPreferencesForSlot(householdId, List.of(callerUserId)))
        .thenReturn(serviceResp);

    MergedSoftPreferencesDto result =
        mergeController().merge(new MergeSoftPreferencesRequest(List.of(callerUserId)));

    assertThat(result).isSameAs(serviceResp);
  }

  /** kills lambda on missing household lookup. */
  @Test
  void mergeController_merge_callerHasNoHousehold_throws404() {
    UUID callerUserId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> mergeController().merge(new MergeSoftPreferencesRequest(null)))
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  /** kills requireCurrentUserId on the merge controller. */
  @Test
  void mergeController_merge_unauthenticated_throws401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());
    assertThatThrownBy(() -> mergeController().merge(new MergeSoftPreferencesRequest(null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================================================================================
  // HouseholdSlotConfigurationPlannerViewController
  // ============================================================================================

  private HouseholdSlotConfigurationPlannerViewController plannerController() {
    return new HouseholdSlotConfigurationPlannerViewController(queryService, currentUserResolver);
  }

  @Test
  void plannerController_get_returnsServiceDto() {
    UUID callerUserId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId))
        .thenReturn(Optional.of(sampleHousehold(householdId, callerUserId)));
    SlotConfigurationPlannerViewDto dto =
        new SlotConfigurationPlannerViewDto(
            householdId,
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            Instant.parse("2026-05-09T12:00:00Z"));
    when(queryService.getSlotConfigurationPlannerView(householdId)).thenReturn(dto);

    SlotConfigurationPlannerViewDto result = plannerController().get();

    assertThat(result).isSameAs(dto);
    verify(queryService).getSlotConfigurationPlannerView(householdId);
  }

  @Test
  void plannerController_get_callerHasNoHousehold_throws404() {
    UUID callerUserId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(callerUserId));
    when(queryService.getByUserId(callerUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> plannerController().get())
        .isInstanceOf(HouseholdNotFoundException.class);
  }

  @Test
  void plannerController_get_unauthenticated_throws401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());
    assertThatThrownBy(() -> plannerController().get()).isInstanceOf(ResponseStatusException.class);
  }
}
