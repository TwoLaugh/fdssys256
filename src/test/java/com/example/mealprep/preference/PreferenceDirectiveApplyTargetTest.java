package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.preference.api.dto.AgeRestrictionDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.domain.service.internal.PreferenceServiceImpl;
import com.example.mealprep.preference.exception.InvalidDirectivePreferenceRouteException;
import com.example.mealprep.preference.spi.internal.PreferenceDirectiveApplyTarget;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PreferenceDirectiveApplyTarget}. The preference service (which Spring wires
 * as a single bean implementing both query + update) is a Mockito mock at the module boundary; the
 * test asserts the directive→hard-constraint translation, temporary-row stamping, dedup, and the
 * unmapped-action 422.
 */
@ExtendWith(MockitoExtension.class)
class PreferenceDirectiveApplyTargetTest {

  @Mock private PreferenceServiceImpl preferenceService;

  private PreferenceDirectiveApplyTarget target;

  private UUID userId;
  private UUID directiveId;
  private UUID actorUserId;
  private final Instant expiry = Instant.parse("2026-07-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    target =
        new PreferenceDirectiveApplyTarget(preferenceService, preferenceService, preferenceService);
    userId = UUID.randomUUID();
    directiveId = UUID.randomUUID();
    actorUserId = UUID.randomUUID();
  }

  private HardConstraintsDto emptyConstraints(long version) {
    return new HardConstraintsDto(
        UUID.randomUUID(),
        userId,
        List.of(),
        new DietaryIdentityDto("omnivore", null, List.of()),
        List.of(),
        List.of(),
        List.of(),
        version);
  }

  private DirectiveInstructionDocument instruction(String action, String target) {
    return new DirectiveInstructionDocument(action, target, "global", null, null);
  }

  // ---------------- action mapping ----------------

  @Test
  void restrictIngredient_addsTargetAsIntolerance_withCurrentVersionAsExpected() {
    when(preferenceService.getHardConstraints(userId))
        .thenReturn(Optional.of(emptyConstraints(3L)));

    target.applyPreferenceDirective(
        userId, instruction("restrict_ingredient", "egg"), false, null, directiveId, actorUserId);

    ArgumentCaptor<UpdateHardConstraintsRequest> reqCap =
        ArgumentCaptor.forClass(UpdateHardConstraintsRequest.class);
    verify(preferenceService).updateHardConstraints(eq(userId), reqCap.capture(), eq(actorUserId));
    UpdateHardConstraintsRequest req = reqCap.getValue();
    assertThat(req.expectedVersion()).isEqualTo(3L);
    assertThat(req.intolerances()).extracting(HardIntoleranceDto::substance).containsExactly("egg");
    // Non-temporary → no provenance stamping.
    verify(preferenceService, never()).stampTemporaryConstraint(any(), any(), any(), any());
  }

  @Test
  void eliminationTrial_isMappedAndStampsProvenance_whenTemporary() {
    when(preferenceService.getHardConstraints(userId))
        .thenReturn(Optional.of(emptyConstraints(0L)));

    target.applyPreferenceDirective(
        userId,
        instruction("eliminate_then_reintroduce", "dairy"),
        true,
        expiry,
        directiveId,
        actorUserId);

    verify(preferenceService).updateHardConstraints(eq(userId), any(), eq(actorUserId));
    verify(preferenceService).stampTemporaryConstraint(userId, "dairy", directiveId, expiry);
  }

  @Test
  void existingConstraints_arePreserved_andTargetAppended() {
    HardConstraintsDto current =
        new HardConstraintsDto(
            UUID.randomUUID(),
            userId,
            List.of("peanut"),
            new DietaryIdentityDto("vegetarian", "Veggie", List.of()),
            List.of("low_fodmap"),
            List.of(new HardIntoleranceDto("lactose", "moderate", null)),
            List.of(new AgeRestrictionDto("no_whole_nuts", true)),
            5L);
    when(preferenceService.getHardConstraints(userId)).thenReturn(Optional.of(current));

    target.applyPreferenceDirective(
        userId, instruction("restrict_ingredient", "egg"), false, null, directiveId, actorUserId);

    ArgumentCaptor<UpdateHardConstraintsRequest> reqCap =
        ArgumentCaptor.forClass(UpdateHardConstraintsRequest.class);
    verify(preferenceService).updateHardConstraints(eq(userId), reqCap.capture(), eq(actorUserId));
    UpdateHardConstraintsRequest req = reqCap.getValue();
    assertThat(req.allergies()).containsExactly("peanut");
    assertThat(req.medicalDiets()).containsExactly("low_fodmap");
    assertThat(req.dietaryIdentity().base()).isEqualTo("vegetarian");
    assertThat(req.ageRestrictions()).hasSize(1);
    assertThat(req.intolerances())
        .extracting(HardIntoleranceDto::substance)
        .containsExactlyInAnyOrder("lactose", "egg");
  }

  // ---------------- dedup ----------------

  @Test
  void targetAlreadyPresent_isNoOp_noUpdateNoStamp() {
    HardConstraintsDto current =
        new HardConstraintsDto(
            UUID.randomUUID(),
            userId,
            List.of(),
            new DietaryIdentityDto("omnivore", null, List.of()),
            List.of(),
            List.of(new HardIntoleranceDto("Egg", "moderate", null)),
            List.of(),
            1L);
    when(preferenceService.getHardConstraints(userId)).thenReturn(Optional.of(current));

    // Case-insensitive match: "egg" vs existing "Egg".
    target.applyPreferenceDirective(
        userId, instruction("restrict_ingredient", "egg"), true, expiry, directiveId, actorUserId);

    verify(preferenceService, never()).updateHardConstraints(any(), any(), any());
    verify(preferenceService, never()).stampTemporaryConstraint(any(), any(), any(), any());
  }

  // ---------------- lazy init ----------------

  @Test
  void missingConstraints_areInitialisedToOmnivoreDefaults_thenTargetAdded() {
    when(preferenceService.getHardConstraints(userId)).thenReturn(Optional.empty());
    when(preferenceService.initialiseHardConstraints(userId)).thenReturn(emptyConstraints(0L));

    target.applyPreferenceDirective(
        userId,
        instruction("restrict_ingredient", "shellfish"),
        false,
        null,
        directiveId,
        actorUserId);

    verify(preferenceService).initialiseHardConstraints(userId);
    verify(preferenceService).updateHardConstraints(eq(userId), any(), eq(actorUserId));
  }

  // ---------------- unmapped action → 422 ----------------

  @Test
  void adjustTarget_action_throwsInvalidRoute_andDoesNotTouchPreferenceState() {
    assertThatThrownBy(
            () ->
                target.applyPreferenceDirective(
                    userId,
                    instruction("adjust_target", "protein_floor_g"),
                    false,
                    null,
                    directiveId,
                    actorUserId))
        .isInstanceOf(InvalidDirectivePreferenceRouteException.class);

    verify(preferenceService, never()).getHardConstraints(any());
    verify(preferenceService, never()).updateHardConstraints(any(), any(), any());
  }

  @Test
  void rebalanceMacros_action_throwsInvalidRoute() {
    assertThatThrownBy(
            () ->
                target.applyPreferenceDirective(
                    userId,
                    instruction("rebalance_macros", null),
                    false,
                    null,
                    directiveId,
                    actorUserId))
        .isInstanceOf(InvalidDirectivePreferenceRouteException.class);
  }

  @Test
  void mappedAction_withBlankTarget_throwsInvalidRoute() {
    assertThatThrownBy(
            () ->
                target.applyPreferenceDirective(
                    userId,
                    instruction("restrict_ingredient", "   "),
                    false,
                    null,
                    directiveId,
                    actorUserId))
        .isInstanceOf(InvalidDirectivePreferenceRouteException.class);
    verify(preferenceService, never()).updateHardConstraints(any(), any(), any());
  }
}
