package com.example.mealprep.nutrition.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Optional eating-window child of {@link NutritionTargets}. {@code @OneToOne} (not @OneToMany), so
 * the row is fetched in the same SELECT as the root — no extra round-trip.
 */
@Entity
@Table(name = "nutrition_eating_window")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class EatingWindow {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "targets_id", nullable = false, unique = true)
  private NutritionTargets target;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "window_start")
  private LocalTime windowStart;

  @Column(name = "window_end")
  private LocalTime windowEnd;

  @Column(name = "notes", length = 255)
  private String notes;
}
