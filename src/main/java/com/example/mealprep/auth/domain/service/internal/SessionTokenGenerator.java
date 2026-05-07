package com.example.mealprep.auth.domain.service.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Issues 256-bit session tokens and the SHA-256 lookup hash that gets stored on the session row.
 *
 * <p>The raw token leaves the server exactly once — in the {@code Set-Cookie} header on the issuing
 * response. Only the hash is persisted, so a DB read yields no usable credentials.
 */
@Component
public class SessionTokenGenerator {

  /** 256 bits of entropy. {@code SecureRandom} is shared and thread-safe. */
  static final int TOKEN_BYTE_LENGTH = 32;

  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final SecureRandom secureRandom;

  public SessionTokenGenerator() {
    this(new SecureRandom());
  }

  /** Test seam — pass a deterministic {@code Random} for tests that need reproducibility. */
  SessionTokenGenerator(SecureRandom secureRandom) {
    this.secureRandom = secureRandom;
  }

  /** Generate a fresh base64url-encoded token (no padding). */
  public String generateRawToken() {
    byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
    secureRandom.nextBytes(bytes);
    return URL_ENCODER.encodeToString(bytes);
  }

  /**
   * SHA-256 of the raw token's UTF-8 bytes, hex-encoded. The hex string is what gets compared in
   * the unique index on {@code auth_sessions.token_hash}.
   */
  public String hash(String rawToken) {
    if (rawToken == null) {
      throw new IllegalArgumentException("rawToken must not be null");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is required by every JDK; this branch is unreachable in practice.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
