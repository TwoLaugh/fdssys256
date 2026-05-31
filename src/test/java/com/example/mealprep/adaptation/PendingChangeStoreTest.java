package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationRollupDto;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeStore;
import com.example.mealprep.adaptation.event.PendingChangeCreatedEvent;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class PendingChangeStoreTest {

  @Test
  void create_supersedes_existing_pending_then_inserts_new() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    AdaptationConfig config = config();

    PendingChange existing = pending(UUID.randomUUID());
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.of(existing));
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));

    PendingChangeStore store = new PendingChangeStore(repo, events, config);
    UUID newId =
        store.create(
            job(),
            response(),
            ChangeDimension.SALT_LEVEL,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "v1",
            null);

    assertThat(newId).isNotNull();
    // The supersession status-flip and the supersededBy back-fill are written via plain
    // repository.flush() on the managed finder entity (routing through save()/merge() would
    // re-order the UPDATE after the INSERT — leaving two PENDING rows and tripping the partial
    // unique index / non-deferrable supersededBy FK). Only the new row goes through saveAndFlush.
    // `save` is never used; two flush() calls (status-flip, back-fill); one saveAndFlush (insert).
    verify(repo, never()).save(any(PendingChange.class));
    verify(repo, never()).saveAndFlush(existing);
    verify(repo, times(1)).saveAndFlush(any(PendingChange.class));
    verify(repo, times(2)).flush();
    assertThat(existing.getStatus()).isEqualTo(PendingChangeStatus.SUPERSEDED);
    assertThat(existing.getSupersededBy()).isEqualTo(newId);

    ArgumentCaptor<PendingChangeCreatedEvent> evCap =
        ArgumentCaptor.forClass(PendingChangeCreatedEvent.class);
    verify(events).publishEvent(evCap.capture());
    assertThat(evCap.getValue().pendingChangeId()).isEqualTo(newId);
  }

  @Test
  void no_existing_pending_just_inserts() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));
    PendingChangeStore store = new PendingChangeStore(repo, events, config());

    UUID id =
        store.create(
            job(),
            response(),
            ChangeDimension.SALT_LEVEL,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "v1",
            null);

    assertThat(id).isNotNull();
    verify(repo, never()).save(any(PendingChange.class));
    verify(repo, times(1)).saveAndFlush(any(PendingChange.class));
    verify(events).publishEvent(any(PendingChangeCreatedEvent.class));
  }

  @Test
  void supersede_sets_resolvedAt_on_existing_row() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    PendingChange existing = pending(UUID.randomUUID());
    // Sanity: fixture leaves resolvedAt null so we can prove the store sets it.
    assertThat(existing.getResolvedAt()).isNull();
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.of(existing));
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));

    PendingChangeStore store = new PendingChangeStore(repo, events, config());
    store.create(
        job(),
        response(),
        ChangeDimension.SALT_LEVEL,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "v1",
        null);

    // Kills the VoidMethodCall mutant that removes setResolvedAt(now).
    assertThat(existing.getResolvedAt()).isNotNull();
  }

  @Test
  void inserted_row_uses_finalDiffJson_when_present_and_carries_branch_fields() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());
    PendingChangeStore store = new PendingChangeStore(repo, events, config());

    var finalDiff = JsonNodeFactory.instance.objectNode().put("k", "v");
    RecipeAdaptationResponse resp =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.VERSION,
            "reasoned",
            "notes",
            BigDecimal.valueOf(0.91),
            BigDecimal.valueOf(0.8),
            null,
            finalDiff,
            List.of());

    store.create(
        job(), resp, ChangeDimension.SALT_LEVEL, UUID.randomUUID(), UUID.randomUUID(), "v7", null);

    ArgumentCaptor<PendingChange> cap = ArgumentCaptor.forClass(PendingChange.class);
    verify(repo).saveAndFlush(cap.capture());
    PendingChange saved = cap.getValue();
    // diffNode: finalDiffJson != null -> returns it (kills NegateConditional + NullReturn).
    assertThat(saved.getProposedDiff()).isEqualTo(finalDiff);
    // reasoning non-null path; promptTemplateVersion non-null path; PENDING (not retry).
    assertThat(saved.getReasoning()).isEqualTo("reasoned");
    assertThat(saved.getPromptTemplateVersion()).isEqualTo("v7");
    assertThat(saved.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
    // safe(confidence): non-null passes through unchanged.
    assertThat(saved.getConfidence()).isEqualByComparingTo("0.91");
  }

  @Test
  void inserted_row_defaults_when_response_fields_and_template_are_null() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());
    PendingChangeStore store = new PendingChangeStore(repo, events, config());

    // null reasoning, null finalDiffJson, null confidence, null promptTemplateVersion.
    RecipeAdaptationResponse resp =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.VERSION,
            null,
            "",
            null,
            BigDecimal.valueOf(0.8),
            null,
            null,
            List.of());

    store.create(
        job(), resp, ChangeDimension.SALT_LEVEL, UUID.randomUUID(), UUID.randomUUID(), null, null);

    ArgumentCaptor<PendingChange> cap = ArgumentCaptor.forClass(PendingChange.class);
    verify(repo).saveAndFlush(cap.capture());
    PendingChange saved = cap.getValue();
    // reasoning() == null -> "" ; promptTemplateVersion == null -> "v0".
    assertThat(saved.getReasoning()).isEmpty();
    assertThat(saved.getPromptTemplateVersion()).isEqualTo("v0");
    // diffNode: finalDiffJson == null -> empty object node (not null).
    assertThat(saved.getProposedDiff()).isNotNull();
    assertThat(saved.getProposedDiff().isObject()).isTrue();
    assertThat(saved.getProposedDiff().isEmpty()).isTrue();
    // safe(null) -> BigDecimal.ZERO.
    assertThat(saved.getConfidence()).isEqualByComparingTo("0");
  }

  @Test
  void impact_score_derived_from_chosen_candidate_rollup_so_high_delta_outranks_low_delta() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());
    PendingChangeStore store = new PendingChangeStore(repo, events, config());

    // Same confidence on both responses (0.90) so only the rollup magnitude differs.
    RecipeAdaptationResponse resp =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.VERSION,
            "r",
            "",
            BigDecimal.valueOf(0.90),
            BigDecimal.valueOf(0.8),
            null,
            JsonNodeFactory.instance.objectNode(),
            List.of());

    // High-magnitude candidate: big macro + cost + time deltas (saturating components).
    AdaptationCandidateDto high = candidate(BigDecimal.valueOf(400), BigDecimal.valueOf(3.0), 30);
    // Low-magnitude candidate: tiny deltas.
    AdaptationCandidateDto low = candidate(BigDecimal.valueOf(10), BigDecimal.valueOf(0.05), 1);

    store.create(
        job(), resp, ChangeDimension.SALT_LEVEL, UUID.randomUUID(), UUID.randomUUID(), "v1", high);
    store.create(
        job(), resp, ChangeDimension.PROTEIN, UUID.randomUUID(), UUID.randomUUID(), "v1", low);

    ArgumentCaptor<PendingChange> cap = ArgumentCaptor.forClass(PendingChange.class);
    verify(repo, times(2)).saveAndFlush(cap.capture());
    BigDecimal highScore = cap.getAllValues().get(0).getImpactScore();
    BigDecimal lowScore = cap.getAllValues().get(1).getImpactScore();

    // The big-delta change must rank strictly above the small-delta change — the prior hard-coded
    // 0.5 made both equal and the rank-at-read budget degenerate to recency/confidence only.
    assertThat(highScore).isGreaterThan(lowScore);
    // Neither is the old literal 0.5, and both fit numeric(4,3).
    assertThat(highScore).isNotEqualByComparingTo("0.5");
    assertThat(highScore.scale()).isEqualTo(3);
    assertThat(highScore).isLessThanOrEqualTo(new BigDecimal("0.999"));
  }

  @Test
  void impact_score_null_candidate_falls_back_to_half_confidence() {
    // No chosen candidate (NO_CHANGE / older wiring): score = confidence * 0.5, still confidence-
    // ordered rather than a flat constant.
    BigDecimal score = PendingChangeStore.deriveImpactScore(null, BigDecimal.valueOf(0.80));
    assertThat(score).isEqualByComparingTo("0.400");
  }

  private static AdaptationCandidateDto candidate(
      BigDecimal kcalDelta, BigDecimal costDelta, int timeDelta) {
    AdaptationRollupDto rollup =
        new AdaptationRollupDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            kcalDelta,
            java.util.Map.of(),
            costDelta,
            timeDelta,
            0,
            BigDecimal.valueOf(0.8),
            java.util.Set.of(),
            List.of());
    return new AdaptationCandidateDto(
        0,
        AdaptationClassification.VERSION,
        JsonNodeFactory.instance.objectNode(),
        rollup,
        "culinary",
        "nutrition",
        BigDecimal.valueOf(0.9),
        BigDecimal.valueOf(0.9),
        List.of());
  }

  private static AdaptationJob job() {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(JobSource.FEEDBACK)
        .priority(JobPriority.SYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.RUNNING)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }

  private static RecipeAdaptationResponse response() {
    return new RecipeAdaptationResponse(
        0,
        AdaptationClassification.VERSION,
        "swap salt",
        "",
        BigDecimal.valueOf(0.8),
        BigDecimal.valueOf(0.8),
        null,
        JsonNodeFactory.instance.objectNode(),
        List.of());
  }

  private static PendingChange pending(UUID id) {
    return PendingChange.builder()
        .id(id)
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .jobId(UUID.randomUUID())
        .traceId(UUID.randomUUID())
        .changeDimension(ChangeDimension.SALT_LEVEL)
        .proposedDiff(JsonNodeFactory.instance.objectNode())
        .proposedClassification(AdaptationClassification.VERSION)
        .baseVersionId(UUID.randomUUID())
        .baseBranchId(UUID.randomUUID())
        .reasoning("r")
        .nutritionalNotes("")
        .confidence(BigDecimal.valueOf(0.8))
        .impactScore(BigDecimal.valueOf(0.5))
        .promptTemplateVersion("v0")
        .status(PendingChangeStatus.PENDING)
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(86_400))
        .build();
  }

  private static AdaptationConfig config() {
    return new AdaptationConfig(
        5,
        10_000,
        8_000,
        12_000,
        3,
        3,
        14,
        new BigDecimal("0.50"),
        new BigDecimal("2.00"),
        null,
        30,
        "0 0 4 * * *",
        "0 30 4 * * *");
  }
}
