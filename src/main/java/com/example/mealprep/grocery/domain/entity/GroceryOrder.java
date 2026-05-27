package com.example.mealprep.grocery.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Tier 3 aggregate root — a provider order with an explicit, top-down lifecycle state machine. Per
 * lld/grocery.md §Entities line 361. Owns {@code List<GroceryOrderLine>}. {@code
 * automationFailureLog} is mapped to {@code List<AutomationFailureRecord>} via JSONB.
 * {@code @Version} guards the aggregate.
 *
 * <p>TODO(core-03): provider-line writes should normalise mapping keys via {@code
 * IngredientMappingKeys.normalise()} once core-03 lands. 01a writes nothing — forward note only.
 */
@Entity
@Table(name = "grocery_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GroceryOrder {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "household_id")
  private UUID householdId;

  @Column(name = "shopping_list_id", nullable = false)
  private UUID shoppingListId;

  @Column(name = "provider_key", nullable = false, length = 32)
  private String providerKey;

  @Column(name = "provider_order_id", length = 128)
  private String providerOrderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private GroceryOrderStatus status;

  @Column(name = "status_reason", length = 255)
  private String statusReason;

  @Column(name = "quoted_total_pence")
  private Integer quotedTotalPence;

  @Column(name = "confirmed_total_pence")
  private Integer confirmedTotalPence;

  @Column(name = "paid_total_pence")
  private Integer paidTotalPence;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "delivery_slot_start")
  private Instant deliverySlotStart;

  @Column(name = "delivery_slot_end")
  private Instant deliverySlotEnd;

  @Column(name = "confirm_link", columnDefinition = "text")
  private String confirmLink;

  @Column(name = "placed_at")
  private Instant placedAt;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "reconciled_at")
  private Instant reconciledAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "cancel_reason", length = 64)
  private String cancelReason;

  @Column(name = "last_status_check_at")
  private Instant lastStatusCheckAt;

  @Type(JsonBinaryType.class)
  @Column(name = "automation_failure_log", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<AutomationFailureRecord> automationFailureLog = new ArrayList<>();

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(
      mappedBy = "groceryOrder",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<GroceryOrderLine> lines = new ArrayList<>();
}
