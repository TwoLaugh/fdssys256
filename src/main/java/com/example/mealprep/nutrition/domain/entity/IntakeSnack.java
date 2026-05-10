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
 * Snack row attached to an {@link IntakeDay}. No business-key uniqueness — multiple snacks per day
 * are allowed. {@code ingredientMappingKey} is reserved for nutrition-01d's pipeline; for 01b the
 * frontend supplies pre-resolved nutrition values directly.
 */
@Entity
@Table(name = "nutrition_intake_snack")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IntakeSnack {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "intake_day_id", nullable = false)
  private IntakeDay intakeDay;

  @Column(name = "ingredient_mapping_key", length = 255)
  private String ingredientMappingKey;

  @Column(name = "free_text", nullable = false, length = 255)
  private String freeText;

  @Column(name = "quantity_g", nullable = false, precision = 8, scale = 1)
  private BigDecimal quantityG;

  @Column(name = "calories", nullable = false)
  private int calories;

  @Column(name = "protein_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal proteinG;

  @Column(name = "carbs_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal carbsG;

  @Column(name = "fat_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal fatG;

  @Column(name = "fibre_g", precision = 6, scale = 1)
  private BigDecimal fibreG;

  @Type(JsonBinaryType.class)
  @Column(name = "micros", columnDefinition = "jsonb")
  private JsonNode micros;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 24)
  private IntakeSource source;

  @Column(name = "logged_at", nullable = false)
  private Instant loggedAt;
}
