package com.example.mealprep.grocery.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tier 1 REFERENCE DATA — maps an ingredient category (or mapping key) to a typical pack size. Per
 * lld/grocery.md §Entities line 366. NO {@code @Version} (refreshed via the repeatable seed
 * migration), no audit columns (the migration has none). The provider-agnostic v1 pack-size
 * fallback; provider-specific pack sizes are a Tier-3 enrichment.
 *
 * <p>Match target: at least one of {@code ingredientMappingKey} / {@code category}. Pack size: at
 * least one of {@code packSizeG} / {@code packCount} (enforced by DB CHECK constraints).
 */
@Entity
@Table(name = "grocery_pack_size_heuristics")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PackSizeHeuristic {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "ingredient_mapping_key", length = 128)
  private String ingredientMappingKey;

  @Column(name = "category", length = 64)
  private String category;

  @Column(name = "pack_size_g")
  private Integer packSizeG;

  @Column(name = "pack_count")
  private Integer packCount;

  @Column(name = "pack_unit", nullable = false, length = 16)
  private String packUnit;

  @Column(name = "rank", nullable = false)
  private int rank;

  @Column(name = "notes", length = 255)
  private String notes;
}
