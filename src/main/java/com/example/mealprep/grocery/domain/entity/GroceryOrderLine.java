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
 * Child of {@link GroceryOrder}. Per lld/grocery.md §Entities line 362. No {@code @Version} — the
 * parent's version covers the aggregate. {@code shoppingListLineId} is a soft FK (ON DELETE SET
 * NULL) back to the originating shopping-list line.
 */
@Entity
@Table(name = "grocery_order_lines")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GroceryOrderLine {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "grocery_order_id", nullable = false)
  private GroceryOrder groceryOrder;

  @Column(name = "shopping_list_line_id")
  private UUID shoppingListLineId;

  @Column(name = "provider_product_id", length = 128)
  private String providerProductId;

  @Column(name = "ingredient_mapping_key", nullable = false, length = 128)
  private String ingredientMappingKey;

  @Column(name = "display_name", nullable = false, length = 255)
  private String displayName;

  @Column(name = "quantity_requested", nullable = false, precision = 10, scale = 3)
  private BigDecimal quantityRequested;

  @Column(name = "quantity_unit", nullable = false, length = 16)
  private String quantityUnit;

  @Column(name = "pack_size_g")
  private Integer packSizeG;

  @Column(name = "pack_count_requested")
  private Integer packCountRequested;

  @Column(name = "pack_count_delivered")
  private Integer packCountDelivered;

  @Column(name = "quoted_unit_pence")
  private Integer quotedUnitPence;

  @Column(name = "confirmed_unit_pence")
  private Integer confirmedUnitPence;

  @Column(name = "paid_unit_pence")
  private Integer paidUnitPence;

  @Enumerated(EnumType.STRING)
  @Column(name = "line_status", nullable = false, length = 16)
  private OrderLineStatus lineStatus;

  @Column(name = "note", length = 255)
  private String note;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
