package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.entity.AdaptationFingerprint;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.service.internal.FingerprintRefresher;
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FingerprintRefresherTest {

  private final AdaptationFingerprintRepository repo =
      Mockito.mock(AdaptationFingerprintRepository.class);
  private final RecipeWriteApi recipeWriteApi = Mockito.mock(RecipeWriteApi.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final FingerprintRefresher refresher =
      new FingerprintRefresher(repo, recipeWriteApi, mapper);

  private JsonNode fingerprintJson() {
    return mapper
        .createObjectNode()
        .put("complexityTier", "MODERATE")
        .put("cuisineAnchor", "italian")
        .set("definingIngredients", mapper.createArrayNode().add("basil").add("tomato"));
  }

  @Test
  void happy_path_persists_row_and_pushes_through_catalogue() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(repo.findByBodyHash(any())).thenReturn(Optional.empty());
    when(repo.findByRecipeIdAndBranchId(recipeId, branchId)).thenReturn(Optional.empty());

    AdaptationFingerprint out =
        refresher.refreshOnBranch(
            recipeId, branchId, versionId, fingerprintJson(), "normal-body", UUID.randomUUID());

    assertThat(out.getRecipeId()).isEqualTo(recipeId);
    assertThat(out.getBranchId()).isEqualTo(branchId);
    assertThat(out.getBodyHash()).hasSize(64);
    verify(repo).save(any(AdaptationFingerprint.class));
    verify(recipeWriteApi).updateCharacterFingerprint(eq(versionId), any());
  }

  @Test
  void idempotent_skip_when_body_hash_already_present() {
    AdaptationFingerprint existing =
        AdaptationFingerprint.builder()
            .id(UUID.randomUUID())
            .recipeId(UUID.randomUUID())
            .branchId(UUID.randomUUID())
            .versionId(UUID.randomUUID())
            .bodyHash("hash")
            .fingerprint(fingerprintJson())
            .derivedAt(java.time.Instant.now())
            .build();
    when(repo.findByBodyHash(any())).thenReturn(Optional.of(existing));

    AdaptationFingerprint out =
        refresher.refreshOnBranch(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            fingerprintJson(),
            "same-body",
            UUID.randomUUID());

    assertThat(out).isSameAs(existing);
    verify(repo, never()).save(any());
    verify(recipeWriteApi, never()).updateCharacterFingerprint(any(), any());
  }

  @Test
  void upsert_reuses_existing_row_id_on_recipe_branch_conflict() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID existingId = UUID.randomUUID();
    AdaptationFingerprint existing =
        AdaptationFingerprint.builder()
            .id(existingId)
            .recipeId(recipeId)
            .branchId(branchId)
            .versionId(UUID.randomUUID())
            .bodyHash("old-hash")
            .fingerprint(fingerprintJson())
            .derivedAt(java.time.Instant.now())
            .build();
    when(repo.findByBodyHash(any())).thenReturn(Optional.empty());
    when(repo.findByRecipeIdAndBranchId(recipeId, branchId)).thenReturn(Optional.of(existing));

    AdaptationFingerprint out =
        refresher.refreshOnBranch(
            recipeId,
            branchId,
            UUID.randomUUID(),
            fingerprintJson(),
            "new-body",
            UUID.randomUUID());

    assertThat(out.getId()).isEqualTo(existingId);
  }

  @Test
  void maps_fingerprint_json_to_typed_dto() {
    CharacterFingerprintDto dto = refresher.mapToCharacterFingerprintDto(fingerprintJson());
    assertThat(dto.cuisineAnchor()).isEqualTo("italian");
    assertThat(dto.definingIngredients()).contains("basil", "tomato");
  }
}
