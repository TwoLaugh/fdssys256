package com.example.mealprep.provisions.domain.entity;

import com.example.mealprep.provisions.exception.InvalidInventoryQuantityException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Single-table aggregate root for the provisions module. One row per pantry item, discriminated by
 * {@link #trackingMode} into a quantity-tracked subset (numeric quantity + unit + cost) and a
 * status-tracked subset (discrete {@link StapleStatus}).
 *
 * <p>The {@link #validateTrackingModeInvariant() @PrePersist/@PreUpdate} hook surfaces a Java-level
 * 422 ({@link InvalidInventoryQuantityException}) before the row hits the DB, so callers see a
 * crisp ProblemDetail rather than a {@code DataIntegrityViolationException}. The DB CHECK
 * constraints in the migration remain the safety net.
 */
@Entity
@Table(name = "provision_inventory")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InventoryItem {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "category", nullable = false, length = 64)
  private String category;

  @Enumerated(EnumType.STRING)
  @Column(name = "storage_location", nullable = false, length = 16)
  private StorageLocation storageLocation;

  @Enumerated(EnumType.STRING)
  @Column(name = "tracking_mode", nullable = false, length = 16)
  private TrackingMode trackingMode;

  // Quantity-tracked
  @Column(name = "quantity", precision = 10, scale = 3)
  private BigDecimal quantity;

  @Column(name = "unit", length = 16)
  private String unit;

  @Column(name = "cost_paid", precision = 8, scale = 2)
  private BigDecimal costPaid;

  // Status-tracked
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 16)
  private StapleStatus status;

  @Column(name = "is_staple", nullable = false)
  private boolean isStaple;

  @Column(name = "expiry_date")
  private LocalDate expiryDate;

  @Column(name = "ingredient_mapping_key", length = 128)
  private String ingredientMappingKey;

  @Column(name = "notes", length = 255)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 16)
  private ItemSource source;

  @Column(name = "source_ref", length = 128)
  private String sourceRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "item_status", nullable = false, length = 16)
  private ItemLifecycleStatus itemStatus;

  // Freezer extension (nullable when storage_location != FREEZER)
  @Column(name = "frozen_at")
  private LocalDate frozenAt;

  @Column(name = "max_freeze_weeks")
  private Integer maxFreezeWeeks;

  @Enumerated(EnumType.STRING)
  @Column(name = "defrost_method", length = 32)
  private DefrostMethod defrostMethod;

  @Column(name = "defrost_lead_time_hours")
  private Integer defrostLeadTimeHours;

  @Column(name = "source_recipe_id")
  private UUID sourceRecipeId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Mirrors the DB CHECK constraints — surfaces a 422 {@link InvalidInventoryQuantityException}
   * before the row reaches the DB so the caller gets a crisp ProblemDetail.
   */
  @PrePersist
  @PreUpdate
  void validateTrackingModeInvariant() {
    if (trackingMode == TrackingMode.QUANTITY && (quantity == null || unit == null)) {
      throw new InvalidInventoryQuantityException(
          "tracking_mode=QUANTITY requires both quantity and unit");
    }
    if (trackingMode == TrackingMode.STATUS && status == null) {
      throw new InvalidInventoryQuantityException("tracking_mode=STATUS requires status");
    }
    if (quantity != null && quantity.signum() < 0) {
      throw new InvalidInventoryQuantityException("quantity must be non-negative");
    }
  }
}
