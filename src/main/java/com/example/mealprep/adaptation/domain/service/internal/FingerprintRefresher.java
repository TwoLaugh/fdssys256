package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.entity.AdaptationFingerprint;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives + caches a recipe-version's character fingerprint on branch-creation, then pushes it
 * through {@link RecipeWriteApi#updateCharacterFingerprint}. v1 derivation runs inline within the
 * adaptation prompt (LLD §Decisions §3, line 964) — the {@code RecipeAdaptationResponse} from 01e
 * already carries the fingerprint when {@code classification = BRANCH}; this class never makes a
 * second AI call.
 *
 * <p>Idempotent on {@code body_hash}: a second call for the same normalised body returns the
 * existing row without touching the catalogue. The {@code adaptation_fingerprints} row is the
 * derivation-provenance cache; the catalogue's {@code recipe_versions.character_fingerprint} is the
 * read path.
 *
 * <p>Per ticket 01f §FingerprintRefresher and {@code lld/adaptation-pipeline.md} §Entities (line
 * 279) / §Decisions §3 (line 964).
 */
@Component
public class FingerprintRefresher {

  private static final Logger LOG = LoggerFactory.getLogger(FingerprintRefresher.class);

  private final AdaptationFingerprintRepository repo;
  private final RecipeWriteApi recipeWriteApi;
  private final ObjectMapper objectMapper;

  public FingerprintRefresher(
      AdaptationFingerprintRepository repo,
      RecipeWriteApi recipeWriteApi,
      ObjectMapper objectMapper) {
    this.repo = repo;
    this.recipeWriteApi = recipeWriteApi;
    this.objectMapper = objectMapper;
  }

  /**
   * UPSERT the fingerprint derivation row + push the typed fingerprint through the catalogue.
   *
   * @param fingerprintFromResponse the fingerprint the AI emitted inline in the BRANCH response
   * @param normalisedBody the normalised version body (hashed for the idempotency key)
   * @param derivedByJobId the pipeline job that produced this branch (nullable)
   * @return the persisted (or pre-existing, on idempotent skip) row
   */
  @Transactional
  public AdaptationFingerprint refreshOnBranch(
      UUID recipeId,
      UUID branchId,
      UUID versionId,
      JsonNode fingerprintFromResponse,
      String normalisedBody,
      UUID derivedByJobId) {
    String bodyHash = sha256(normalisedBody);

    Optional<AdaptationFingerprint> byHash = repo.findByBodyHash(bodyHash);
    if (byHash.isPresent()) {
      LOG.debug(
          "fingerprint idempotent-skip recipeId={} branchId={} bodyHash={}",
          recipeId,
          branchId,
          bodyHash);
      return byHash.get();
    }

    // UPSERT on the (recipeId, branchId) UNIQUE — reuse the existing row id if one is present so
    // Hibernate issues a merge/update instead of an insert that would trip the unique index.
    AdaptationFingerprint existing =
        repo.findByRecipeIdAndBranchId(recipeId, branchId).orElse(null);
    AdaptationFingerprint record =
        AdaptationFingerprint.builder()
            .id(existing == null ? UUID.randomUUID() : existing.getId())
            .recipeId(recipeId)
            .branchId(branchId)
            .versionId(versionId)
            .bodyHash(bodyHash)
            .fingerprint(fingerprintFromResponse)
            .derivedByJobId(derivedByJobId)
            .derivedAt(Instant.now())
            .build();
    repo.save(record);

    recipeWriteApi.updateCharacterFingerprint(
        versionId, mapToCharacterFingerprintDto(fingerprintFromResponse));
    LOG.info(
        "fingerprint refreshed recipeId={} branchId={} versionId={}",
        recipeId,
        branchId,
        versionId);
    return record;
  }

  /** Convert the raw fingerprint JSON tree into the recipe module's typed DTO. */
  public CharacterFingerprintDto mapToCharacterFingerprintDto(JsonNode node) {
    try {
      return objectMapper.treeToValue(node, CharacterFingerprintDto.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException(
          "fingerprint JSON does not match CharacterFingerprintDto: " + e.getMessage(), e);
    }
  }

  private static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
