package com.example.mealprep.nutrition.domain.entity;

import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Standalone aggregate root: a cached ingredient mapping keyed by normalised {@code searchTerm}.
 * Reference data with light updates. {@code @Version} because the user-correction flow mutates the
 * row and concurrent corrections must collide cleanly. {@code nutritionPer100g} is mapped to the
 * {@link IngredientNutritionDocument} record via Postgres JSONB.
 */
@Entity
@Table(name = "nutrition_ingredient_mapping")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IngredientMapping {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "search_term", nullable = false, updatable = false, unique = true, length = 255)
  private String searchTerm;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 24)
  private IngredientMappingSource source;

  @Column(name = "external_id", length = 64)
  private String externalId;

  @Type(JsonBinaryType.class)
  @Column(name = "nutrition_per_100g", nullable = false, columnDefinition = "jsonb")
  private IngredientNutritionDocument nutritionPer100g;

  @Column(name = "default_piece_grams")
  private Integer defaultPieceGrams;

  @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
  private BigDecimal confidence;

  @Column(name = "needs_review", nullable = false)
  private boolean needsReview;

  @Column(name = "last_verified_at")
  private Instant lastVerifiedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
