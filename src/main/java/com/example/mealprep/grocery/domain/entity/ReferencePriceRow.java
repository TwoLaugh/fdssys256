package com.example.mealprep.grocery.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Tier 4 REFERENCE DATA (01c) — the cold-start cost source backing {@link
 * com.example.mealprep.grocery.domain.service.ReferencePriceSource}. One rolled-up estimate per
 * normalised {@code ingredientMappingKey}, seeded from the hand-authored Open Prices starter set
 * via {@code R__grocery_seed_reference_prices.sql}. NO {@code @Version} (refreshed via the
 * repeatable seed migration), {@code createdAt} the only audit column — mirrors {@link
 * PackSizeHeuristic}.
 *
 * <p>Maps to {@code grocery_reference_prices} (migration {@code V20260601120600}). {@code
 * attribution} carries the ODbL attribution string on every row (ODbL share-alike + attribution
 * obligation; owner signed off for v1).
 */
@Entity
@Table(name = "grocery_reference_prices")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ReferencePriceRow {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "ingredient_mapping_key", nullable = false, length = 128)
  private String ingredientMappingKey;

  @Column(name = "reference_unit_pence", nullable = false)
  private int referenceUnitPence;

  @Column(name = "unit", nullable = false, length = 16)
  private String unit;

  @Column(name = "reference_confidence", nullable = false, precision = 4, scale = 3)
  private BigDecimal referenceConfidence;

  @Column(name = "source_as_of", nullable = false)
  private LocalDate sourceAsOf;

  @Column(name = "attribution", nullable = false, length = 255)
  private String attribution;

  @Column(name = "sample_products")
  private Integer sampleProducts;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
