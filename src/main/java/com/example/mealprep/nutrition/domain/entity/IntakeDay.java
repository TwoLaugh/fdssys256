package com.example.mealprep.nutrition.domain.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aggregate root for a single user-day's intake. One row per {@code (userId, onDate)}. Owns three
 * list children: {@link IntakeSlot}, {@link IntakeSnack}, {@link IntakeAuditLog}.
 *
 * <p>Three list children means the repository CANNOT use a multi-attribute {@code @EntityGraph} —
 * Hibernate throws {@code MultipleBagFetchException}. Service touches each list inside a
 * transaction to force lazy load (same workaround as {@link NutritionTargets}).
 *
 * <p>The aggregate's {@code @Version} covers concurrency for the whole graph; child entities have
 * no version of their own.
 */
@Entity
@Table(
    name = "nutrition_intake_day",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "on_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IntakeDay {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "on_date", nullable = false, updatable = false)
  private LocalDate onDate;

  @Column(name = "plan_id")
  private UUID planId;

  @OneToMany(
      mappedBy = "intakeDay",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<IntakeSlot> slots = new ArrayList<>();

  @OneToMany(
      mappedBy = "intakeDay",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<IntakeSnack> snacks = new ArrayList<>();

  @OneToMany(
      mappedBy = "intakeDay",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<IntakeAuditLog> auditLog = new ArrayList<>();

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Add a slot with parent linkage (preserves collection identity for Hibernate). */
  public void addSlot(IntakeSlot slot) {
    slot.setIntakeDay(this);
    this.slots.add(slot);
  }

  /** Add a snack with parent linkage. */
  public void addSnack(IntakeSnack snack) {
    snack.setIntakeDay(this);
    this.snacks.add(snack);
  }
}
