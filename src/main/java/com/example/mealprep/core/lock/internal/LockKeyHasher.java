package com.example.mealprep.core.lock.internal;

import com.example.mealprep.core.lock.LockKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Maps a {@link LockKey} to a stable 64-bit signed long suitable for {@code
 * pg_try_advisory_xact_lock(bigint)}.
 *
 * <p><strong>Hash choice — SHA-256 truncated to 64 bits.</strong> The ticket initially specced
 * Murmur3-128 truncated to 64. Murmur3 is not in the JDK and Guava (its usual provider) is not on
 * this project's main classpath. SHA-256's first 8 bytes provide equivalent collision resistance
 * for our scale (~tens of millions of distinct lock keys; collision probability on 64 bits is well
 * below 1 in a billion at that scale) and avoid pulling Guava in for a single helper. Deterministic
 * on bytes; suitable for advisory-lock identity. Documented deviation from the ticket's "Decisions
 * left to the implementor" §2.
 */
public final class LockKeyHasher {

  private LockKeyHasher() {}

  /** Hash {@code key.serialize()} to a signed long. */
  public static long hash(LockKey key) {
    return hashBytes(key.serialize().getBytes(StandardCharsets.UTF_8));
  }

  static long hashBytes(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
      // Take the first 8 bytes as a signed big-endian long. Stable across JVMs and
      // platforms; the bit pattern is what's stored as the bigint key on Postgres.
      long result = 0L;
      for (int i = 0; i < 8; i++) {
        result = (result << 8) | (digest[i] & 0xff);
      }
      return result;
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every JRE per the JCA spec; this branch is unreachable.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
