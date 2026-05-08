package com.example.mealprep.preference.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
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
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aggregate root for a user's hard, safety-critical preferences. One row per user (unique on {@code
 * user_id}). Children — {@link DietaryIdentityException}, {@link HardIntolerance}, {@link
 * AgeRestriction} — are owned via {@code @OneToMany(cascade = ALL, orphanRemoval = true)}; the
 * aggregate's {@code @Version} covers them.
 *
 * <p>{@code allergies} and {@code medical_diets} are stored as JSONB arrays (List&lt;String&gt; via
 * hypersistence {@link JsonBinaryType}). The LLD originally specced {@code text[]}; runtime testing
 * showed Hibernate's text[] mapping is brittle in this stack (Spring Boot 3.2.5 /
 * hibernate-utils-63), and JSONB is well-tested elsewhere in the repo. {@code
 * dietary_identity_base} is a free-form String at this stage; 01c introduces the
 * {@code @ValidDietaryIdentity} validator that constrains it to a known enum.
 */
@Entity
@Table(name = "preference_hard_constraints")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HardConstraints {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, unique = true, updatable = false)
  private UUID userId;

  @Type(JsonBinaryType.class)
  @Column(name = "allergies", nullable = false, columnDefinition = "jsonb")
  private List<String> allergies;

  @Column(name = "dietary_identity_base", nullable = false, length = 32)
  private String dietaryIdentityBase;

  @Column(name = "dietary_identity_label", length = 64)
  private String dietaryIdentityLabel;

  @Type(JsonBinaryType.class)
  @Column(name = "medical_diets", nullable = false, columnDefinition = "jsonb")
  private List<String> medicalDiets;

  @OneToMany(
      mappedBy = "hardConstraints",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<DietaryIdentityException> exceptions = new ArrayList<>();

  @OneToMany(
      mappedBy = "hardConstraints",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<HardIntolerance> intolerances = new ArrayList<>();

  @OneToMany(
      mappedBy = "hardConstraints",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<AgeRestriction> ageRestrictions = new ArrayList<>();

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Replace all exceptions in-place; preserves the parent's collection identity for Hibernate. */
  public void replaceExceptions(List<DietaryIdentityException> replacements) {
    this.exceptions.clear();
    if (replacements != null) {
      for (DietaryIdentityException child : replacements) {
        child.setHardConstraints(this);
        this.exceptions.add(child);
      }
    }
  }

  /**
   * Replace all intolerances in-place; preserves the parent's collection identity for Hibernate.
   */
  public void replaceIntolerances(List<HardIntolerance> replacements) {
    this.intolerances.clear();
    if (replacements != null) {
      for (HardIntolerance child : replacements) {
        child.setHardConstraints(this);
        this.intolerances.add(child);
      }
    }
  }

  /**
   * Replace all age restrictions in-place; preserves parent's collection identity for Hibernate.
   */
  public void replaceAgeRestrictions(List<AgeRestriction> replacements) {
    this.ageRestrictions.clear();
    if (replacements != null) {
      for (AgeRestriction child : replacements) {
        child.setHardConstraints(this);
        this.ageRestrictions.add(child);
      }
    }
  }
}
