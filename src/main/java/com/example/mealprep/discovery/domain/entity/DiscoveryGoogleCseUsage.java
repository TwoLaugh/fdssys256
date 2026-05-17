package com.example.mealprep.discovery.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One-row-per-UTC-day audit of Google CSE call volume (01e). Backs {@code
 * GoogleCseDailyQuotaTracker}'s crash-recovery: on startup the tracker reads today's row to seed
 * the in-memory counter so a runner restart doesn't overrun the free-tier daily budget.
 *
 * <p>{@code day} is the natural PK (a {@code date}); no surrogate UUID, no {@code @Version} — the
 * tracker upserts via the repository with a single eager UPDATE per call (cheap; one row/day).
 */
@Entity
@Table(name = "discovery_google_cse_usage")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DiscoveryGoogleCseUsage {

  @Id
  @Column(name = "day", nullable = false, updatable = false)
  private LocalDate day;

  @Column(name = "call_count", nullable = false)
  private int callCount;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
