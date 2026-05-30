package com.example.mealprep.core.lock.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the {@code core_lock_leases} table — one row per held lease.
 *
 * <p>The {@code lockKey} is the primary key, so the database enforces single-flight: a second
 * insert for the same key conflicts while the row exists. A crashed holder's row is reclaimed
 * lazily on the next acquire once {@code expiresAt} has passed.
 *
 * <p>All lease reads/writes go through {@code LockLeaseRepository}'s explicit native/JPQL queries
 * ({@code insertIfAbsent}, {@code reclaimIfExpired}, {@code deleteByLockKeyAndHolderToken}, {@code
 * renewByLockKeyAndHolderToken}) rather than entity-state persistence — those queries are atomic
 * and conditional in ways a managed-entity save cannot express. This entity exists so {@code
 * JpaRepository<LockLease, String>} resolves and Hibernate validates the schema (field access; no
 * getters/setters needed). It is intentionally a plain mapped type with no behaviour.
 *
 * <p>Lives in {@code core.lock.domain.entity} (with its repository in {@code
 * core.lock.domain.repository}) per the module-layout convention; cross-module callers go through
 * {@code com.example.mealprep.core.lock.LockService}.
 */
@Entity
@Table(name = "core_lock_leases")
public class LockLease {

  @Id
  @Column(name = "lock_key", updatable = false, nullable = false, length = 160)
  private String lockKey;

  @Column(name = "holder_token", nullable = false)
  private UUID holderToken;

  @Column(name = "acquired_at", nullable = false)
  private Instant acquiredAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** For Hibernate. Not for application code. */
  protected LockLease() {}
}
