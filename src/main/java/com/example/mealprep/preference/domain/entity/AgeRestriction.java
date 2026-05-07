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
 * Child of {@link HardConstraints}. An age-derived rule (e.g. {@code no_whole_nuts} for under-5s)
 * applied by the filter via a static rule registry. {@code autoPopulated} flags rules seeded by
 * profile-metadata changes vs. user-set ones.
 */
@Entity
@Table(name = "preference_age_restrictions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AgeRestriction {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hard_constraints_id", nullable = false)
  private HardConstraints hardConstraints;

  @Column(name = "rule_key", nullable = false, length = 64)
  private String ruleKey;

  @Column(name = "auto_populated", nullable = false)
  private boolean autoPopulated;
}
