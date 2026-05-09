package com.example.mealprep.household.domain.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aggregate root for a household. Owns its {@link HouseholdMember} children via
 * {@code @OneToMany(cascade = ALL, orphanRemoval = true)} — the aggregate's {@code @Version} covers
 * concurrency for the whole graph. Mirrors the shape of {@code preference.HardConstraints}.
 *
 * <p>v1 invariant: a user belongs to at most one household at a time, enforced by the {@code UNIQUE
 * (user_id)} constraint on {@code household_member}. Future tickets layer settings, invites,
 * audit-log, and the merge service on top of this root.
 */
@Entity
@Table(name = "household")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Household {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "created_by_user_id", nullable = false, updatable = false)
  private UUID createdByUserId;

  @OneToMany(
      mappedBy = "household",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<HouseholdMember> members = new ArrayList<>();

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Replace all members in-place; preserves the parent's collection identity for Hibernate. */
  public void replaceMembers(List<HouseholdMember> replacements) {
    this.members.clear();
    if (replacements != null) {
      for (HouseholdMember child : replacements) {
        child.setHousehold(this);
        this.members.add(child);
      }
    }
  }
}
