package com.example.mealprep.household.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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

/**
 * Aggregate root for a household invite. Modeled as its own aggregate (no {@code @OneToMany} from
 * {@link Household}) — adding invites to {@link Household#getMembers()}'s aggregate would force a
 * re-write of {@code HouseholdMapper}; the FK ({@code household_id}) on the row plus the DB-level
 * {@code ON DELETE CASCADE} handles parent-child lifecycle.
 *
 * <p>Status ({@code PENDING / ACCEPTED / REVOKED / EXPIRED}) is derived from the {@code
 * acceptedAt}, {@code revokedAt}, {@code expiresAt} columns at mapping time — not stored.
 *
 * <p>{@code intendedRole} is persisted via {@code @Enumerated(STRING)} producing the literal
 * strings {@code 'primary'} / {@code 'member'} (matches the existing {@link HouseholdRole}
 * convention from 01a).
 *
 * <p>No {@code @LastModifiedDate} — invites are accept/revoke-once and have no mid-life mutations
 * worth recording at the row level.
 */
@Entity
@Table(name = "household_invite")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HouseholdInvite {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "household_id", nullable = false, updatable = false)
  private UUID householdId;

  @Column(name = "invite_code", nullable = false, updatable = false, unique = true, length = 32)
  private String inviteCode;

  @Column(name = "issued_by_user_id", nullable = false, updatable = false)
  private UUID issuedByUserId;

  @Column(name = "issued_for_user_id")
  private UUID issuedForUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "intended_role", nullable = false, length = 16)
  private HouseholdRole intendedRole;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_by_user_id")
  private UUID acceptedByUserId;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
