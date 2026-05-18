package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.DirectiveConfidence;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import com.example.mealprep.nutrition.domain.entity.EatingWindow;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditAction;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditLog;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.IntakeSource;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * State-roundtrip tests for the Lombok-generated nutrition data entities. Each field is built with
 * a distinct non-default value and every accessor is asserted, which kills the per-getter {@code
 * NullReturnVals} / {@code EmptyObjectReturnVals} / {@code PrimitiveReturns} / {@code
 * BooleanReturnVals} mutants (a getter that returns the wrong constant must be observable). Pure
 * in-memory — no Spring context, no DB.
 */
class NutritionEntityRoundtripTest {

  @Test
  void healthDirective_builder_roundtrip_covers_every_accessor() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID decidedBy = UUID.randomUUID();
    Instant received = Instant.parse("2026-05-01T09:00:00Z");
    Instant expires = Instant.parse("2026-06-01T09:00:00Z");
    Instant decided = Instant.parse("2026-05-02T09:00:00Z");
    Instant created = Instant.parse("2026-05-01T09:00:01Z");
    Instant updated = Instant.parse("2026-05-02T09:00:02Z");
    DirectiveInstructionDocument payload =
        new DirectiveInstructionDocument("adjust_target", "protein_floor_g", "global", null, null);
    DirectiveInstructionDocument userMod =
        new DirectiveInstructionDocument("adjust_target", "carbs_floor_g", "global", null, null);
    List<SafetyFindingDto> findings = List.of(new SafetyFindingDto("C1", "msg", "HIGH"));

    HealthDirective d =
        HealthDirective.builder()
            .id(id)
            .userId(userId)
            .externalDirectiveId("ext-77")
            .sourcePlatform("acme-health")
            .receivedAt(received)
            .status(DirectiveStatus.ACCEPTED)
            .directiveType(DirectiveType.TARGET_ADJUSTMENT)
            .evidenceSummary("strong RCT evidence")
            .evidenceConfidence(DirectiveConfidence.MODERATE)
            .instructionPayload(payload)
            .mapsToModel("nutrition_model")
            .mapsToTier("protein_floor_g")
            .temporary(true)
            .autoExpiresAt(expires)
            .decidedAt(decided)
            .decidedByUserId(decidedBy)
            .userModificationJson(userMod)
            .rejectionReason("n/a")
            .safetyGateVerdict(SafetyGateVerdict.PASSED)
            .safetyGateFindings(findings)
            .optimisticVersion(7L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(d.getId()).isEqualTo(id);
    assertThat(d.getUserId()).isEqualTo(userId);
    assertThat(d.getExternalDirectiveId()).isEqualTo("ext-77");
    assertThat(d.getSourcePlatform()).isEqualTo("acme-health");
    assertThat(d.getReceivedAt()).isEqualTo(received);
    assertThat(d.getStatus()).isEqualTo(DirectiveStatus.ACCEPTED);
    assertThat(d.getDirectiveType()).isEqualTo(DirectiveType.TARGET_ADJUSTMENT);
    assertThat(d.getEvidenceSummary()).isEqualTo("strong RCT evidence");
    assertThat(d.getEvidenceConfidence()).isEqualTo(DirectiveConfidence.MODERATE);
    assertThat(d.getInstructionPayload()).isSameAs(payload);
    assertThat(d.getMapsToModel()).isEqualTo("nutrition_model");
    assertThat(d.getMapsToTier()).isEqualTo("protein_floor_g");
    assertThat(d.isTemporary()).isTrue();
    assertThat(d.getAutoExpiresAt()).isEqualTo(expires);
    assertThat(d.getDecidedAt()).isEqualTo(decided);
    assertThat(d.getDecidedByUserId()).isEqualTo(decidedBy);
    assertThat(d.getUserModificationJson()).isSameAs(userMod);
    assertThat(d.getRejectionReason()).isEqualTo("n/a");
    assertThat(d.getSafetyGateVerdict()).isEqualTo(SafetyGateVerdict.PASSED);
    assertThat(d.getSafetyGateFindings()).containsExactlyElementsOf(findings);
    assertThat(d.getOptimisticVersion()).isEqualTo(7L);
    assertThat(d.getCreatedAt()).isEqualTo(created);
    assertThat(d.getUpdatedAt()).isEqualTo(updated);
  }

  @Test
  void healthDirective_temporary_false_is_distinguishable_from_true() {
    HealthDirective d = HealthDirective.builder().temporary(false).build();
    assertThat(d.isTemporary()).isFalse();
  }

  @Test
  void intakeSlot_builder_roundtrip_covers_every_accessor() {
    UUID id = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    IntakeDay day = IntakeDay.builder().id(UUID.randomUUID()).build();
    Instant overriddenAt = Instant.parse("2026-05-18T12:00:00Z");

    IntakeSlot s =
        IntakeSlot.builder()
            .id(id)
            .intakeDay(day)
            .mealSlot(MealSlot.LUNCH)
            .plannedRecipeId(recipeId)
            .plannedCalories(600)
            .plannedProteinG(new BigDecimal("40.0"))
            .plannedCarbsG(new BigDecimal("55.0"))
            .plannedFatG(new BigDecimal("20.0"))
            .plannedFibreG(new BigDecimal("8.0"))
            .actualStatus(IntakeSlotStatus.CONFIRMED)
            .actualCalories(620)
            .actualProteinG(new BigDecimal("41.0"))
            .actualCarbsG(new BigDecimal("56.0"))
            .actualFatG(new BigDecimal("21.0"))
            .actualFibreG(new BigDecimal("9.0"))
            .overrideFreeText("two extra eggs")
            .overriddenAt(overriddenAt)
            .needsAiParse(true)
            .build();

    assertThat(s.getId()).isEqualTo(id);
    assertThat(s.getIntakeDay()).isSameAs(day);
    assertThat(s.getMealSlot()).isEqualTo(MealSlot.LUNCH);
    assertThat(s.getPlannedRecipeId()).isEqualTo(recipeId);
    assertThat(s.getPlannedCalories()).isEqualTo(600);
    assertThat(s.getPlannedProteinG()).isEqualByComparingTo("40.0");
    assertThat(s.getPlannedCarbsG()).isEqualByComparingTo("55.0");
    assertThat(s.getPlannedFatG()).isEqualByComparingTo("20.0");
    assertThat(s.getPlannedFibreG()).isEqualByComparingTo("8.0");
    assertThat(s.getActualStatus()).isEqualTo(IntakeSlotStatus.CONFIRMED);
    assertThat(s.getActualCalories()).isEqualTo(620);
    assertThat(s.getActualProteinG()).isEqualByComparingTo("41.0");
    assertThat(s.getActualCarbsG()).isEqualByComparingTo("56.0");
    assertThat(s.getActualFatG()).isEqualByComparingTo("21.0");
    assertThat(s.getActualFibreG()).isEqualByComparingTo("9.0");
    assertThat(s.getOverrideFreeText()).isEqualTo("two extra eggs");
    assertThat(s.getOverriddenAt()).isEqualTo(overriddenAt);
    assertThat(s.isNeedsAiParse()).isTrue();
  }

  @Test
  void intakeSlot_builder_defaults_are_pending_and_no_ai_parse() {
    IntakeSlot s = IntakeSlot.builder().id(UUID.randomUUID()).build();
    assertThat(s.getActualStatus()).isEqualTo(IntakeSlotStatus.PENDING);
    assertThat(s.isNeedsAiParse()).isFalse();
  }

  @Test
  void intakeSnack_builder_roundtrip_covers_every_accessor() {
    UUID id = UUID.randomUUID();
    IntakeDay day = IntakeDay.builder().id(UUID.randomUUID()).build();
    Instant logged = Instant.parse("2026-05-18T15:30:00Z");

    IntakeSnack snack =
        IntakeSnack.builder()
            .id(id)
            .intakeDay(day)
            .ingredientMappingKey("almonds")
            .freeText("handful of almonds")
            .quantityG(new BigDecimal("28.0"))
            .calories(164)
            .proteinG(new BigDecimal("6.0"))
            .carbsG(new BigDecimal("6.1"))
            .fatG(new BigDecimal("14.2"))
            .fibreG(new BigDecimal("3.5"))
            .source(IntakeSource.MANUAL)
            .loggedAt(logged)
            .build();

    assertThat(snack.getId()).isEqualTo(id);
    assertThat(snack.getIntakeDay()).isSameAs(day);
    assertThat(snack.getIngredientMappingKey()).isEqualTo("almonds");
    assertThat(snack.getFreeText()).isEqualTo("handful of almonds");
    assertThat(snack.getQuantityG()).isEqualByComparingTo("28.0");
    assertThat(snack.getCalories()).isEqualTo(164);
    assertThat(snack.getProteinG()).isEqualByComparingTo("6.0");
    assertThat(snack.getCarbsG()).isEqualByComparingTo("6.1");
    assertThat(snack.getFatG()).isEqualByComparingTo("14.2");
    assertThat(snack.getFibreG()).isEqualByComparingTo("3.5");
    assertThat(snack.getSource()).isEqualTo(IntakeSource.MANUAL);
    assertThat(snack.getLoggedAt()).isEqualTo(logged);
  }

  @Test
  void eatingWindow_builder_roundtrip_covers_every_accessor() {
    UUID id = UUID.randomUUID();
    NutritionTargets target = NutritionTestData.targets().build();
    LocalTime start = LocalTime.of(8, 0);
    LocalTime end = LocalTime.of(20, 0);

    EatingWindow w =
        EatingWindow.builder()
            .id(id)
            .target(target)
            .enabled(true)
            .windowStart(start)
            .windowEnd(end)
            .notes("12h window")
            .build();

    assertThat(w.getId()).isEqualTo(id);
    assertThat(w.getTarget()).isSameAs(target);
    assertThat(w.isEnabled()).isTrue();
    assertThat(w.getWindowStart()).isEqualTo(start);
    assertThat(w.getWindowEnd()).isEqualTo(end);
    assertThat(w.getNotes()).isEqualTo("12h window");
  }

  @Test
  void eatingWindow_disabled_is_distinguishable_from_enabled() {
    EatingWindow w = EatingWindow.builder().id(UUID.randomUUID()).enabled(false).build();
    assertThat(w.isEnabled()).isFalse();
  }

  @Test
  void ingredientMapping_builder_roundtrip_covers_every_accessor() {
    UUID id = UUID.randomUUID();
    Instant verified = Instant.parse("2026-05-10T00:00:00Z");
    Instant created = Instant.parse("2026-05-09T00:00:00Z");
    Instant updated = Instant.parse("2026-05-11T00:00:00Z");
    IngredientNutritionDocument nutrition =
        new IngredientNutritionDocument(
            52,
            new BigDecimal("0.3"),
            new BigDecimal("14.0"),
            new BigDecimal("0.2"),
            new BigDecimal("2.4"),
            null,
            new BigDecimal("10.0"),
            Map.of(),
            Map.of());

    IngredientMapping m =
        IngredientMapping.builder()
            .id(id)
            .searchTerm("apple")
            .source(IngredientMappingSource.USDA)
            .externalId("usda-1750")
            .nutritionPer100g(nutrition)
            .defaultPieceGrams(182)
            .confidence(new BigDecimal("0.950"))
            .needsReview(true)
            .lastVerifiedAt(verified)
            .version(3L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(m.getId()).isEqualTo(id);
    assertThat(m.getSearchTerm()).isEqualTo("apple");
    assertThat(m.getSource()).isEqualTo(IngredientMappingSource.USDA);
    assertThat(m.getExternalId()).isEqualTo("usda-1750");
    assertThat(m.getNutritionPer100g()).isSameAs(nutrition);
    assertThat(m.getDefaultPieceGrams()).isEqualTo(182);
    assertThat(m.getConfidence()).isEqualByComparingTo("0.950");
    assertThat(m.isNeedsReview()).isTrue();
    assertThat(m.getLastVerifiedAt()).isEqualTo(verified);
    assertThat(m.getVersion()).isEqualTo(3L);
    assertThat(m.getCreatedAt()).isEqualTo(created);
    assertThat(m.getUpdatedAt()).isEqualTo(updated);
  }

  @Test
  void ingredientMapping_needsReview_false_is_distinguishable() {
    IngredientMapping m =
        IngredientMapping.builder().id(UUID.randomUUID()).needsReview(false).build();
    assertThat(m.isNeedsReview()).isFalse();
  }

  @Test
  void intakeAuditLog_constructor_roundtrip_covers_every_accessor() {
    ObjectMapper om = new ObjectMapper();
    UUID id = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID snackId = UUID.randomUUID();
    IntakeDay day = IntakeDay.builder().id(UUID.randomUUID()).build();
    JsonNode prev = om.valueToTree(Map.of("calories", 500));
    JsonNode next = om.valueToTree(Map.of("calories", 620));
    Instant occurred = Instant.parse("2026-05-18T13:45:00Z");

    IntakeAuditLog log =
        new IntakeAuditLog(
            id,
            day,
            actor,
            IntakeAuditAction.OVERRIDE,
            MealSlot.DINNER,
            snackId,
            prev,
            next,
            occurred);

    assertThat(log.getId()).isEqualTo(id);
    assertThat(log.getIntakeDay()).isSameAs(day);
    assertThat(log.getActorUserId()).isEqualTo(actor);
    assertThat(log.getAction()).isEqualTo(IntakeAuditAction.OVERRIDE);
    assertThat(log.getMealSlot()).isEqualTo(MealSlot.DINNER);
    assertThat(log.getSnackId()).isEqualTo(snackId);
    assertThat(log.getPreviousValueJson()).isSameAs(prev);
    assertThat(log.getNewValueJson()).isSameAs(next);
    assertThat(log.getOccurredAt()).isEqualTo(occurred);
  }
}
