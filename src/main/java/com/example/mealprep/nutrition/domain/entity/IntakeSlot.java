package com.example.mealprep.nutrition.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Per-meal-slot row attached to an {@link IntakeDay}. {@code planned_*} columns mirror the planner
 * snapshot at pre-fill time; {@code actual_*} columns are written on confirm / edit / override /
 * skip. {@code needsAiParse} is the 01b extension flagging override rows for the deferred
 * (nutrition-01k) AI parse.
 */
@Entity
@Table(
    name = "nutrition_intake_slot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"intake_day_id", "meal_slot"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IntakeSlot {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "intake_day_id", nullable = false)
  private IntakeDay intakeDay;

  @Enumerated(EnumType.STRING)
  @Column(name = "meal_slot", nullable = false, length = 24)
  private MealSlot mealSlot;

  @Column(name = "planned_recipe_id")
  private UUID plannedRecipeId;

  @Column(name = "planned_calories")
  private Integer plannedCalories;

  @Column(name = "planned_protein_g", precision = 6, scale = 1)
  private BigDecimal plannedProteinG;

  @Column(name = "planned_carbs_g", precision = 6, scale = 1)
  private BigDecimal plannedCarbsG;

  @Column(name = "planned_fat_g", precision = 6, scale = 1)
  private BigDecimal plannedFatG;

  @Column(name = "planned_fibre_g", precision = 6, scale = 1)
  private BigDecimal plannedFibreG;

  @Type(JsonBinaryType.class)
  @Column(name = "planned_micros", columnDefinition = "jsonb")
  private JsonNode plannedMicros;

  @Enumerated(EnumType.STRING)
  @Column(name = "actual_status", nullable = false, length = 24)
  @Builder.Default
  private IntakeSlotStatus actualStatus = IntakeSlotStatus.PENDING;

  @Column(name = "actual_calories")
  private Integer actualCalories;

  @Column(name = "actual_protein_g", precision = 6, scale = 1)
  private BigDecimal actualProteinG;

  @Column(name = "actual_carbs_g", precision = 6, scale = 1)
  private BigDecimal actualCarbsG;

  @Column(name = "actual_fat_g", precision = 6, scale = 1)
  private BigDecimal actualFatG;

  @Column(name = "actual_fibre_g", precision = 6, scale = 1)
  private BigDecimal actualFibreG;

  @Type(JsonBinaryType.class)
  @Column(name = "actual_micros", columnDefinition = "jsonb")
  private JsonNode actualMicros;

  @Column(name = "override_free_text", length = 512)
  private String overrideFreeText;

  @Column(name = "overridden_at")
  private Instant overriddenAt;

  @Column(name = "needs_ai_parse", nullable = false)
  @Builder.Default
  private boolean needsAiParse = false;
}
