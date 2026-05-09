package com.example.mealprep.household.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
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
 * Child of {@link Household}. Models a user's membership in a household — exactly one of the
 * members is {@link HouseholdRole#primary} (enforced by the partial unique index {@code
 * idx_household_member_one_primary} at the DB level).
 *
 * <p>{@code role} is persisted via {@code @Enumerated(STRING)} producing the literal strings {@code
 * 'primary'} / {@code 'member'} — see {@link HouseholdRole} for the rationale (matches the
 * partial-index predicate without a converter).
 */
@Entity
@Table(name = "household_member")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HouseholdMember {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "household_id", nullable = false)
  private Household household;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 16)
  private HouseholdRole role;

  @Column(name = "display_name", length = 64)
  private String displayName;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "joined_at", nullable = false, updatable = false)
  private Instant joinedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
