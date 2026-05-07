package com.example.mealprep.preference.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Child of {@link HardConstraints}. A non-allergy intolerance (lactose, FODMAPs, ...) the filter
 * treats as a hard rule, paired with a severity descriptor used by the planner's messaging.
 */
@Entity
@Table(name = "preference_hard_intolerances")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HardIntolerance {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hard_constraints_id", nullable = false)
  private HardConstraints hardConstraints;

  @Column(name = "substance", nullable = false, length = 64)
  private String substance;

  @Column(name = "severity", nullable = false, length = 32)
  private String severity;

  @Column(name = "notes", length = 255)
  private String notes;
}
