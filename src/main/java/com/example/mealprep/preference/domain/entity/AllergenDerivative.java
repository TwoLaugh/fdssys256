package com.example.mealprep.preference.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Reference data: maps an allergen (e.g. {@code peanut}) to a known derivative ingredient key (e.g.
 * {@code peanut_oil}). Read-only at runtime; rows are seeded by the {@code R__preference_seed}
 * repeatable migration.
 *
 * <p>No {@code @Version}, no audit columns — this is reference data that never mutates outside of a
 * migration. JPA needs the protected no-args constructor only for hydration.
 */
@Entity
@Table(name = "preference_allergen_derivatives")
public class AllergenDerivative {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "allergen", nullable = false, length = 64, updatable = false)
  private String allergen;

  @Column(name = "derivative", nullable = false, length = 128, updatable = false)
  private String derivative;

  protected AllergenDerivative() {}

  public AllergenDerivative(UUID id, String allergen, String derivative) {
    this.id = id;
    this.allergen = allergen;
    this.derivative = derivative;
  }

  public UUID getId() {
    return id;
  }

  public String getAllergen() {
    return allergen;
  }

  public String getDerivative() {
    return derivative;
  }
}
