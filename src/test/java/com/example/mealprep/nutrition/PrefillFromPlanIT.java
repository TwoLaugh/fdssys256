package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeEntryDto;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.exception.IntakeDayNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSlotNotFoundException;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * In-process service-call IT for {@link NutritionUpdateService#prefillFromPlan} and the slot-write
 * paths that depend on a pre-filled day. There is no HTTP endpoint for {@code prefillFromPlan} in
 * 01b — the planner module (deferred) will call this method directly.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PrefillFromPlanIT {

  @Autowired private NutritionUpdateService updateService;
  @Autowired private NutritionQueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_intake_audit");
    jdbcTemplate.update("DELETE FROM nutrition_intake_snack");
    jdbcTemplate.update("DELETE FROM nutrition_intake_slot");
    jdbcTemplate.update("DELETE FROM nutrition_intake_day");
  }

  @Test
  void prefillFromPlan_createsDay_withSlots() {
    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    LocalDate onDate = LocalDate.of(2026, 5, 9);
    List<PlannedSlotInputDto> slots = NutritionTestData.defaultPlannedSlots();

    IntakeDayDto dto = updateService.prefillFromPlan(userId, onDate, planId, slots);

    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.onDate()).isEqualTo(onDate);
    assertThat(dto.planId()).isEqualTo(planId);
    assertThat(dto.slots()).hasSize(3);
    assertThat(dto.slots())
        .allSatisfy(s -> assertThat(s.actual().status()).isEqualTo(IntakeSlotStatus.PENDING));

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE intake_day_id = ?",
            Long.class,
            dto.id());
    assertThat(auditCount).isEqualTo(1L);
    String action =
        jdbcTemplate.queryForObject(
            "SELECT action FROM nutrition_intake_audit WHERE intake_day_id = ?",
            String.class,
            dto.id());
    assertThat(action).isEqualTo("PREFILL");
  }

  @Test
  void confirm_thenIdempotentReConfirm_writesNoExtraAudit() {
    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    LocalDate onDate = LocalDate.of(2026, 5, 9);
    updateService.prefillFromPlan(userId, onDate, planId, NutritionTestData.defaultPlannedSlots());

    // First confirm — writes audit + actuals copied.
    updateService.confirmFromPlan(userId, onDate, MealSlot.BREAKFAST);
    Long auditAfterFirst =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE action = 'CONFIRM'", Long.class);
    assertThat(auditAfterFirst).isEqualTo(1L);

    // Second confirm — idempotent, NO audit row.
    updateService.confirmFromPlan(userId, onDate, MealSlot.BREAKFAST);
    Long auditAfterSecond =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE action = 'CONFIRM'", Long.class);
    assertThat(auditAfterSecond).isEqualTo(1L);
  }

  @Test
  void edit_setsStatusEdited_andValuesPersisted() {
    UUID userId = UUID.randomUUID();
    LocalDate onDate = LocalDate.of(2026, 5, 9);
    updateService.prefillFromPlan(
        userId, onDate, UUID.randomUUID(), NutritionTestData.defaultPlannedSlots());

    IntakeEntryDto entry =
        new IntakeEntryDto(
            450,
            BigDecimal.valueOf(28.0),
            BigDecimal.valueOf(55.0),
            BigDecimal.valueOf(13.0),
            BigDecimal.valueOf(7.0),
            null);
    IntakeDayDto after = updateService.editIntakeManually(userId, onDate, MealSlot.LUNCH, entry);

    assertThat(after.slots())
        .filteredOn(s -> s.mealSlot() == MealSlot.LUNCH)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.actual().status()).isEqualTo(IntakeSlotStatus.EDITED);
              assertThat(s.actual().calories()).isEqualTo(450);
            });
  }

  @Test
  void override_zeroesActuals_setsNeedsAiParse() {
    UUID userId = UUID.randomUUID();
    LocalDate onDate = LocalDate.of(2026, 5, 9);
    updateService.prefillFromPlan(
        userId, onDate, UUID.randomUUID(), NutritionTestData.defaultPlannedSlots());

    IntakeDayDto after =
        updateService.overrideIntakeFromFreeText(userId, onDate, MealSlot.DINNER, "ate at mum's");

    assertThat(after.slots())
        .filteredOn(s -> s.mealSlot() == MealSlot.DINNER)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.actual().status()).isEqualTo(IntakeSlotStatus.OVERRIDDEN);
              assertThat(s.actual().needsAiParse()).isTrue();
              assertThat(s.actual().overrideFreeText()).isEqualTo("ate at mum's");
              assertThat(s.actual().calories()).isEqualTo(0);
            });
  }

  @Test
  void confirm_throws_whenDayMissing() {
    UUID userId = UUID.randomUUID();
    LocalDate onDate = LocalDate.of(2026, 5, 9);
    assertThatThrownBy(() -> updateService.confirmFromPlan(userId, onDate, MealSlot.BREAKFAST))
        .isInstanceOf(IntakeDayNotFoundException.class);
  }

  @Test
  void confirm_throws_whenSlotMissing() {
    UUID userId = UUID.randomUUID();
    LocalDate onDate = LocalDate.of(2026, 5, 9);
    updateService.prefillFromPlan(
        userId, onDate, UUID.randomUUID(), NutritionTestData.defaultPlannedSlots());

    // SNACKS slot was not pre-filled.
    assertThatThrownBy(() -> updateService.confirmFromPlan(userId, onDate, MealSlot.SNACKS))
        .isInstanceOf(IntakeSlotNotFoundException.class);
  }

  @Test
  void getIntakeForDay_returns_DtoWithSlotsAndSnacks() {
    UUID userId = UUID.randomUUID();
    LocalDate onDate = LocalDate.of(2026, 5, 9);
    updateService.prefillFromPlan(
        userId, onDate, UUID.randomUUID(), NutritionTestData.defaultPlannedSlots());

    assertThat(queryService.getIntakeForDay(userId, onDate))
        .isPresent()
        .get()
        .satisfies(
            dto -> {
              assertThat(dto.slots()).hasSize(3);
              assertThat(dto.snacks()).isEmpty();
            });
  }
}
