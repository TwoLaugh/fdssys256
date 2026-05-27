package com.example.mealprep.grocery.domain.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Child of {@link ShoppingList}. Per lld/grocery.md §Entities line 360. No {@code @Version} — the
 * parent's version covers the aggregate. {@code boughtVia}, {@code groceryOrderId}, {@code
 * boughtPricePence} are populated by Tier 2 mark-bought / Tier 3 order reconciliation. {@code
 * fulfilmentStatus} is a per-line marker, not a soft-delete; the line stays for history.
 *
 * <p>TODO(core-03): a write boundary that should normalise {@code ingredientMappingKey} via {@code
 * IngredientMappingKeys.normalise()} once core-03 lands. 01a writes nothing — forward note only.
 */
@Entity
@Table(name = "shopping_list_lines")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ShoppingListLine {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "shopping_list_id", nullable = false)
  private ShoppingList shoppingList;

  @Column(name = "ingredient_mapping_key", nullable = false, length = 128)
  private String ingredientMappingKey;

  @Column(name = "display_name", nullable = false, length = 128)
  private String displayName;

  @Column(name = "requested_quantity", nullable = false, precision = 10, scale = 3)
  private BigDecimal requestedQuantity;

  @Column(name = "requested_unit", nullable = false, length = 16)
  private String requestedUnit;

  @Column(name = "suggested_pack_size_g")
  private Integer suggestedPackSizeG;

  @Column(name = "suggested_pack_count")
  private Integer suggestedPackCount;

  @Column(name = "suggested_pack_unit", length = 16)
  private String suggestedPackUnit;

  @Enumerated(EnumType.STRING)
  @Column(name = "line_type", nullable = false, length = 16)
  private ShoppingListLineType lineType;

  @Column(name = "quality_notes", length = 255)
  private String qualityNotes;

  @Column(name = "estimated_unit_pence")
  private Integer estimatedUnitPence;

  @Column(name = "estimated_line_pence")
  private Integer estimatedLinePence;

  @Column(name = "estimated_confidence", precision = 4, scale = 3)
  private BigDecimal estimatedConfidence;

  @Column(name = "is_stale_estimate", nullable = false)
  private boolean staleEstimate;

  @Enumerated(EnumType.STRING)
  @Column(name = "fulfilment_status", nullable = false, length = 16)
  private LineFulfilmentStatus fulfilmentStatus;

  @Column(name = "bought_quantity", precision = 10, scale = 3)
  private BigDecimal boughtQuantity;

  @Column(name = "bought_unit", length = 16)
  private String boughtUnit;

  @Column(name = "bought_price_pence")
  private Integer boughtPricePence;

  @Column(name = "bought_at")
  private Instant boughtAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "bought_via", length = 16)
  private BoughtVia boughtVia;

  @Column(name = "grocery_order_id")
  private UUID groceryOrderId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
