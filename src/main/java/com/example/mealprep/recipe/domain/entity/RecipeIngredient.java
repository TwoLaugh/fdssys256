package com.example.mealprep.recipe.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Child of {@link RecipeVersion}. {@code line_order} preserves the UI order; {@code (version_id,
 * line_order)} is unique at the DB level.
 */
@Entity
@Table(name = "recipe_ingredients")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeIngredient {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "version_id", nullable = false)
  private RecipeVersion version;

  @Column(name = "line_order", nullable = false)
  private int lineOrder;

  @Column(name = "ingredient_mapping_key", nullable = false, length = 160)
  private String ingredientMappingKey;

  @Column(name = "display_name", nullable = false, length = 160)
  private String displayName;

  @Column(name = "quantity", precision = 10, scale = 3)
  private BigDecimal quantity;

  @Column(name = "unit", length = 16)
  private String unit;

  @Column(name = "preparation", length = 80)
  private String preparation;

  @Column(name = "optional", nullable = false)
  private boolean optional;

  @Column(name = "needs_review", nullable = false)
  private boolean needsReview;

  @Column(name = "mapping_confidence", precision = 4, scale = 3)
  private BigDecimal mappingConfidence;
}
