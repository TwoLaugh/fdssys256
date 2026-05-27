package com.example.mealprep.grocery.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * Tier 4 APPEND-ONLY price observation row. Per lld/grocery.md §Entities line 365. NO
 * {@code @Version}, NO {@code @LastModifiedDate} (no {@code updated_at} column) — the row is
 * written once and never updated. {@code source} enum; {@code confidenceWeight} is source-weighted
 * at write time and never changed.
 *
 * <p>Maps to {@code grocery_price_history}. Household-scoped aggregation; {@code householdId}
 * nullable for single-user mode. TODO(core-03): the write boundary normalises {@code
 * ingredientMappingKey} via {@code IngredientMappingKeys.normalise()} once core-03 lands.
 */
@Entity
@Table(name = "grocery_price_history")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PriceObservation {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "household_id")
  private UUID householdId;

  @Column(name = "ingredient_mapping_key", nullable = false, length = 128)
  private String ingredientMappingKey;

  @Column(name = "store", nullable = false, length = 64)
  private String store;

  @Column(name = "provider_product_id", length = 128)
  private String providerProductId;

  @Column(name = "pack_size_g")
  private Integer packSizeG;

  @Column(name = "pack_count")
  private Integer packCount;

  @Column(name = "quantity", precision = 10, scale = 3)
  private BigDecimal quantity;

  @Column(name = "quantity_unit", length = 16)
  private String quantityUnit;

  @Column(name = "paid_unit_pence")
  private Integer paidUnitPence;

  @Column(name = "paid_total_pence")
  private Integer paidTotalPence;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 24)
  private PriceSource source;

  @Column(name = "confidence_weight", nullable = false, precision = 4, scale = 3)
  private BigDecimal confidenceWeight;

  @Column(name = "grocery_order_id")
  private UUID groceryOrderId;

  @Column(name = "shopping_list_line_id")
  private UUID shoppingListLineId;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  @Column(name = "note", length = 255)
  private String note;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
