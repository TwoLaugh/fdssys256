package com.example.mealprep.planner.domain.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

/**
 * Child of {@link Plan}. Owns its slots. No {@code @Version} — the parent {@code Plan.version}
 * covers the aggregate per LLD §Concurrency.
 */
@Entity
@Table(
    name = "planner_days",
    uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "on_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Day {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plan_id", nullable = false)
  private Plan plan;

  @Column(name = "on_date", nullable = false)
  private LocalDate onDate;

  @Column(name = "notes", length = 255)
  private String notes;

  @OneToMany(
      mappedBy = "day",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<MealSlot> slots = new ArrayList<>();
}
