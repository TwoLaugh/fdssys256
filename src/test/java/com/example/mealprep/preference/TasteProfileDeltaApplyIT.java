package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.ArchiveItemRequest;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.entity.ActorType;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.entity.PreferenceArchiveEntry;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteProfileAuditLog;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.PreferenceArchiveRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.exception.TasteProfileBudgetExceededException;
import com.example.mealprep.preference.exception.TasteProfileNotFoundException;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Service-layer end-to-end for {@code TasteProfileServiceImpl.applyDeltas} against real Postgres:
 * the real {@code TasteProfileDeltaApplier} + {@code TasteProfileBudgetGuard} mutate the JSONB
 * document, the entity {@code documentVersion} bumps in lock-step, the version snapshot carries the
 * real {@code deltasApplied} JSON, the audit row is {@code AI} / {@code AI_DELTA_APPLIED}, and the
 * Archive / RePromote ops write / re-promote {@code preference_taste_profile_archive} rows.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class TasteProfileDeltaApplyIT {

  @Autowired private TasteProfileUpdateService updateService;
  @Autowired private TasteProfileQueryService queryService;
  @Autowired private PreferenceArchiveUpdateService archiveUpdateService;
  @Autowired private TasteProfileRepository tasteProfileRepository;
  @Autowired private TasteProfileVersionRepository versionRepository;
  @Autowired private TasteProfileAuditLogRepository auditLogRepository;
  @Autowired private PreferenceArchiveRepository archiveRepository;
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    auditLogRepository.deleteAll();
    versionRepository.deleteAll();
    archiveRepository.deleteAll();
    tasteProfileRepository.deleteAll();
  }

  private ObjectNode ingredient(String item, int evidence) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("item", item);
    node.put("evidenceCount", evidence);
    node.put("lastSignal", "2026-05-01");
    node.put("source", "FEEDBACK");
    return node;
  }

  private ApplyTasteProfileDeltasRequest request(UUID feedbackId, List<TasteProfileDelta> deltas) {
    String trace = "feedback-" + feedbackId;
    return new ApplyTasteProfileDeltasRequest(
        deltas, TasteProfileTrigger.BATCH, trace, trace, "cheap");
  }

  @Test
  void applyDeltas_add_roundTripsThroughJsonb_bumpsVersion_writesAuditAndVersionRows() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    updateService.initialise(userId);

    updateService.applyDeltas(
        userId,
        request(
            feedbackId,
            List.of(
                new TasteProfileDelta.Add(
                    "ingredientPreferences.disliked", ingredient("coriander", 3)))));

    TasteProfileDto dto = queryService.getTasteProfile(userId).orElseThrow();
    assertThat(dto.documentVersion()).isEqualTo(2);
    assertThat(dto.document().version()).isEqualTo(2);
    assertThat(dto.document().ingredientPreferences().disliked())
        .extracting(
            com.example.mealprep.preference.domain.document.TasteProfileDocument
                    .IngredientPreference
                ::item)
        .contains("coriander");
    assertThat(dto.tasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);

    TasteProfile profile = tasteProfileRepository.findByUserId(userId).orElseThrow();
    assertThat(profile.getFeedbackCursor()).isEqualTo("feedback-" + feedbackId);
    assertThat(profile.getLastTokenEstimate()).isNotNull().isPositive();
    assertThat(profile.getLastDeltaAppliedAt()).isNotNull();

    // INITIALIZED + AI_DELTA_APPLIED audit rows; v1 + v2 version snapshots.
    assertThat(auditLogRepository.count()).isEqualTo(2L);
    assertThat(versionRepository.count()).isEqualTo(2L);

    TasteProfileAuditLog aiAudit =
        auditLogRepository.findAll().stream()
            .filter(a -> a.getChangeType() == TasteProfileChangeType.AI_DELTA_APPLIED)
            .findFirst()
            .orElseThrow();
    assertThat(aiAudit.getActorType()).isEqualTo(ActorType.AI);
    assertThat(aiAudit.getPreviousDocumentVersion()).isEqualTo(1);
    assertThat(aiAudit.getNewDocumentVersion()).isEqualTo(2);
    assertThat(aiAudit.getTraceId()).isEqualTo(feedbackId);

    // Version snapshot carries the REAL delta array (not the empty manual-path array).
    var v2 =
        versionRepository.findAll().stream()
            .filter(v -> v.getDocumentVersion() == 2)
            .findFirst()
            .orElseThrow();
    assertThat(v2.getDeltasApplied().isArray()).isTrue();
    assertThat(v2.getDeltasApplied()).hasSize(1);
    assertThat(v2.getDeltasApplied().get(0).get("op").asText()).isEqualTo("ADD");
    assertThat(v2.getTrigger()).isEqualTo(TasteProfileTrigger.BATCH);
    assertThat(v2.getModelTierUsed()).isEqualTo("cheap");
  }

  @Test
  void applyDeltas_archive_removesFromDocument_andWritesArchiveRow() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);
    // Seed a live favourite to archive.
    updateService.applyDeltas(
        userId,
        request(
            UUID.randomUUID(),
            List.of(
                new TasteProfileDelta.Add(
                    "ingredientPreferences.favourites", ingredient("tahini", 6)))));

    updateService.applyDeltas(
        userId,
        request(
            UUID.randomUUID(),
            List.of(
                new TasteProfileDelta.Archive(
                    "ingredientPreferences.favourites", "tahini", "STALE"))));

    TasteProfileDto dto = queryService.getTasteProfile(userId).orElseThrow();
    assertThat(dto.document().ingredientPreferences().favourites())
        .extracting(
            com.example.mealprep.preference.domain.document.TasteProfileDocument
                    .IngredientPreference
                ::item)
        .doesNotContain("tahini");

    List<PreferenceArchiveEntry> archived = archiveRepository.findAllByUserId(userId);
    assertThat(archived).hasSize(1);
    PreferenceArchiveEntry entry = archived.get(0);
    assertThat(entry.getItemKey()).isEqualTo("tahini");
    assertThat(entry.getArchivedReason()).isEqualTo(ArchiveReason.STALE);
    assertThat(entry.getEvidenceCount()).isEqualTo(6);
    assertThat(entry.getItemPayload().get("item").asText()).isEqualTo("tahini");
  }

  @Test
  void applyDeltas_rePromote_restoresArchivedItemVerbatim_andFlipsRePromotedAt() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);
    // Pre-archive an item directly via the archive service (the re-emergence precondition).
    archiveUpdateService.archiveItem(
        userId,
        new ArchiveItemRequest(
            "ingredientPreferences.favourites",
            "miso",
            ingredient("miso", 9),
            9,
            LocalDate.parse("2026-04-01"),
            ArchiveReason.LOW_EVIDENCE));

    updateService.applyDeltas(
        userId,
        request(
            UUID.randomUUID(),
            List.of(new TasteProfileDelta.RePromote("ingredientPreferences.favourites", "miso"))));

    TasteProfileDto dto = queryService.getTasteProfile(userId).orElseThrow();
    var restored =
        dto.document().ingredientPreferences().favourites().stream()
            .filter(i -> i.item().equals("miso"))
            .findFirst()
            .orElseThrow();
    // Restored verbatim with its preserved evidence (9) — re-emergence, not a fresh Add.
    assertThat(restored.evidenceCount()).isEqualTo(9);

    PreferenceArchiveEntry entry = archiveRepository.findAllByUserId(userId).get(0);
    assertThat(entry.getRePromotedAt()).isNotNull();
  }

  @Test
  void applyDeltas_invalidDelta_rolledBack_priorVersionPreserved() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);

    assertThatThrownBy(
            () ->
                updateService.applyDeltas(
                    userId,
                    request(
                        UUID.randomUUID(),
                        List.of(
                            new TasteProfileDelta.Remove(
                                "ingredientPreferences.disliked", "ghost")))))
        .isInstanceOf(
            com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException.class);

    // Prior version preserved; no partial state.
    TasteProfileDto dto = queryService.getTasteProfile(userId).orElseThrow();
    assertThat(dto.documentVersion()).isEqualTo(1);
    assertThat(auditLogRepository.count()).isEqualTo(1L); // only INITIALIZED
    assertThat(versionRepository.count()).isEqualTo(1L);
  }

  @Test
  void applyDeltas_budgetExceeded_throws_andPriorVersionPreserved() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);

    // 50 long learned-insights (the max batch size) push the document ~5000 tokens, well over the
    // 2500-token budget — exercising the budget guard without tripping the >50 batch-count
    // validator.
    java.util.List<TasteProfileDelta> deltas = new java.util.ArrayList<>();
    for (int i = 0; i < 50; i++) {
      deltas.add(
          new TasteProfileDelta.Add(
              "learnedInsights", objectMapper.getNodeFactory().textNode("x".repeat(400) + i)));
    }

    assertThatThrownBy(() -> updateService.applyDeltas(userId, request(UUID.randomUUID(), deltas)))
        .isInstanceOf(TasteProfileBudgetExceededException.class);

    TasteProfileDto dto = queryService.getTasteProfile(userId).orElseThrow();
    assertThat(dto.documentVersion()).isEqualTo(1);
    assertThat(versionRepository.count()).isEqualTo(1L);
  }

  @Test
  void applyDeltas_emptyBatch_isNoOp() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);

    updateService.applyDeltas(userId, request(UUID.randomUUID(), List.of()));

    TasteProfileDto dto = queryService.getTasteProfile(userId).orElseThrow();
    assertThat(dto.documentVersion()).isEqualTo(1);
    assertThat(auditLogRepository.count()).isEqualTo(1L);
    assertThat(versionRepository.count()).isEqualTo(1L);
  }

  @Test
  void applyDeltas_missingProfile_throwsNotFound() {
    assertThatThrownBy(
            () ->
                updateService.applyDeltas(UUID.randomUUID(), request(UUID.randomUUID(), List.of())))
        .isInstanceOf(TasteProfileNotFoundException.class);
  }
}
