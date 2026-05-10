package com.example.mealprep.household.domain.service.internal;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Cryptographically random short-code generator for household invites.
 *
 * <p>Uses {@link SecureRandom} (NOT {@link java.util.Random} / {@code Math.random}) so codes are
 * unguessable. Output: 16 chars from a 31-char alphabet (uppercase letters + digits, with the
 * visually-ambiguous characters {@code 0/O/1/I/L} excluded for OCR/typo resistance). Effective
 * entropy: 16 × log2(31) ≈ 79 bits — well above the LLD's "alphanumeric, secure-random" floor.
 */
@Component
public class InviteCodeGenerator {

  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // 31 chars; no 0/O/1/I/L
  static final int CODE_LENGTH = 16;

  private final SecureRandom random = new SecureRandom();

  /** Generate a fresh 16-char invite code. Each call produces an independent draw. */
  public String generate() {
    StringBuilder out = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return out.toString();
  }
}
