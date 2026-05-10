package com.example.mealprep.nutrition.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * Standalone per-user-day activity log entry. Unique on {@code (userId, onDate)} — last write wins
 * (no {@code @Version} per LLD). Upserts via JPA {@code find + save}.
 */
@Entity
@Table(
    name = "nutrition_daily_activity_log",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "on_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DailyActivityLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "on_date", nullable = false, updatable = false)
  private LocalDate onDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "activity_level", nullable = false, length = 24)
  private ActivityLevel activityLevel;

  @Column(name = "notes", length = 255)
  private String notes;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
