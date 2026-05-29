package com.example.mealprep.core.lock;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque handle returned by {@link LockService#acquireLease}. Proves the holder owns a lease and
 * carries the {@code holderToken} needed to {@link LockService#releaseLease} or {@link
 * LockService#renewLease} it.
 *
 * <p>Only the holder of the matching {@code (lockKey, holderToken)} pair may release or renew the
 * lease — a caller that reclaimed an expired lease gets a <em>fresh</em> token, so the original
 * (crashed) holder cannot later delete the new owner's lease. This is the liveness guarantee that
 * lets a lease survive a holder crash: the lease becomes reclaimable once {@code expiresAt} passes,
 * and the reclaiming caller's token supersedes the dead one.
 *
 * @param key the lock identity this lease covers
 * @param holderToken the per-acquisition secret proving ownership; never reused across acquisitions
 * @param acquiredAt when this lease was claimed (or reclaimed)
 * @param expiresAt when this lease becomes reclaimable by another caller if not released/renewed
 */
public record LeaseHandle(LockKey key, UUID holderToken, Instant acquiredAt, Instant expiresAt) {

  public LeaseHandle {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(holderToken, "holderToken");
    Objects.requireNonNull(acquiredAt, "acquiredAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
