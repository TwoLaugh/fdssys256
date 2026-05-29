package com.example.mealprep.core.lock.internal;

import com.example.mealprep.core.lock.LeaseHandle;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The single {@link LockService} bean. Delegates the two lock mechanisms to dedicated transactional
 * helper beans:
 *
 * <ul>
 *   <li>{@link #tryAcquire} → {@link AdvisoryLockServiceImpl} ({@code @Transactional(MANDATORY)} —
 *       transaction-scoped {@code pg_try_advisory_xact_lock}).
 *   <li>{@link #acquireLease} / {@link #releaseLease} / {@link #renewLease} → {@link
 *       LeaseLockServiceImpl} ({@code @Transactional(REQUIRES_NEW)} — connection-free committed
 *       lease rows).
 * </ul>
 *
 * <p>The delegates are separate beans so their differing {@code @Transactional} propagation advice
 * fires across the bean boundary (a same-bean call would bypass the Spring proxy). This composite
 * carries no logic of its own beyond fan-out.
 */
@Service
public class LockServiceImpl implements LockService {

  private final AdvisoryLockServiceImpl advisory;
  private final LeaseLockServiceImpl lease;

  public LockServiceImpl(AdvisoryLockServiceImpl advisory, LeaseLockServiceImpl lease) {
    this.advisory = advisory;
    this.lease = lease;
  }

  @Override
  public boolean tryAcquire(LockKey key) {
    return advisory.tryAcquire(key);
  }

  @Override
  public Optional<LeaseHandle> acquireLease(LockKey key, Duration ttl) {
    return lease.acquireLease(key, ttl);
  }

  @Override
  public boolean releaseLease(LeaseHandle handle) {
    return lease.releaseLease(handle);
  }

  @Override
  public Optional<LeaseHandle> renewLease(LeaseHandle handle, Duration ttl) {
    return lease.renewLease(handle, ttl);
  }
}
