package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * SHA-256 fingerprint over the normalised body of a {@link ParsedRecipe}. Pure function — no state.
 * Algorithm per LLD line 529: sorted ingredient mapping keys + concatenated method instructions,
 * lowercased, whitespace-collapsed.
 *
 * <p>Two recipes that differ only in ingredient order, whitespace, or letter case produce the same
 * fingerprint. {@code null} ingredient mapping keys are filtered out (don't crash, don't
 * contribute).
 *
 * <p>Package-private — only the runner (01d) injects this.
 */
@Component
class ContentFingerprintHasher {

  private static final String SHA_256 = "SHA-256";

  /** Compute the 64-character hex SHA-256 fingerprint of {@code recipe}. */
  String fingerprint(ParsedRecipe recipe) {
    String ingredientKeysSorted =
        recipe.ingredients().stream()
            .map(ParsedRecipe.ParsedIngredient::ingredientMappingKey)
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .map(String::trim)
            .sorted()
            .collect(Collectors.joining("\n"));
    String methodInstructionsLower =
        recipe.method().stream()
            .map(ParsedRecipe.ParsedMethodStep::instruction)
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .map(s -> s.replaceAll("\\s+", " "))
            .map(String::trim)
            .collect(Collectors.joining("\n"));
    String input = ingredientKeysSorted + "\n---\n" + methodInstructionsLower;
    return sha256Hex(input);
  }

  private static String sha256Hex(String input) {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance(SHA_256);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is required by every JRE — this is unreachable in practice.
      throw new IllegalStateException("SHA-256 not available", e);
    }
    byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
