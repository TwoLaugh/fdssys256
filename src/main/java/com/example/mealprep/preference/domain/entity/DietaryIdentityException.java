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
 * Child of {@link HardConstraints}. Allows a single exception to the dietary identity (e.g.
 * "vegetarian who eats fish on weekends"). The parent's {@code @Version} covers concurrency for the
 * whole aggregate.
 */
@Entity
@Table(name = "preference_dietary_identity_exceptions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DietaryIdentityException {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hard_constraints_id", nullable = false)
  private HardConstraints hardConstraints;

  @Column(name = "allows", nullable = false, length = 64)
  private String allows;

  @Column(name = "frequency", length = 32)
  private String frequency;

  @Column(name = "context", nullable = false, length = 32)
  private String context;
}
