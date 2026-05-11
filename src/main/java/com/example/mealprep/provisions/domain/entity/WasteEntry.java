package com.example.mealprep.provisions.domain.entity;

import com.example.mealprep.provisions.api.dto.WasteReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * Append-only waste log row per LLD §Entities (line 258): "Append-only. No {@code @Version}, no
 * {@code @LastModifiedDate}. {@code inventoryItemId} nullable. {@code reason} enum."
 *
 * <p>Corrections create a new row; no update path is exposed by the controller or repository.
 * {@code itemName} is denormalised at write time (LLD line 213) so analytics survive even if the
 * underlying inventory row is later deleted.
 */
@Entity
@Table(name = "provision_waste_log")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class WasteEntry {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "inventory_item_id", updatable = false)
  private UUID inventoryItemId;

  @Column(name = "item_name", nullable = false, updatable = false, length = 128)
  private String itemName;

  @Column(name = "quantity", updatable = false, precision = 10, scale = 3)
  private BigDecimal quantity;

  @Column(name = "unit", updatable = false, length = 16)
  private String unit;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, updatable = false, length = 32)
  private WasteReason reason;

  @Column(name = "cost_estimate", updatable = false, precision = 8, scale = 2)
  private BigDecimal costEstimate;

  @Column(name = "occurred_on", nullable = false, updatable = false)
  private LocalDate occurredOn;

  @Column(name = "notes", updatable = false, length = 255)
  private String notes;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
  // NO @Version, NO @LastModifiedDate — append-only per LLD line 258.
}
