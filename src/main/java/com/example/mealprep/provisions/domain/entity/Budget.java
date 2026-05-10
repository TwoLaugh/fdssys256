package com.example.mealprep.provisions.domain.entity;

import com.example.mealprep.provisions.api.dto.PriceSensitivity;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Per-user budget aggregate root. One row per {@code userId} — UNIQUE constraint enforces this. The
 * {@code enabled} flag captures the HLD's "budget is optional" rule (a user can pause budgeting
 * without discarding their configured numbers). Spend-tracking derivation lives outside the
 * aggregate (deferred to provisions-01f/01h once the order-history source is wired).
 */
@Entity
@Table(name = "provision_budget")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Budget {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false, unique = true)
  private UUID userId;

  @Column(name = "weekly_target", nullable = false, precision = 8, scale = 2)
  private BigDecimal weeklyTarget;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "tolerance_over", nullable = false, precision = 8, scale = 2)
  private BigDecimal toleranceOver;

  @Enumerated(EnumType.STRING)
  @Column(name = "price_sensitivity", nullable = false, length = 16)
  private PriceSensitivity priceSensitivity;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

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
