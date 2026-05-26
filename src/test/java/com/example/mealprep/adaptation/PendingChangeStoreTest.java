package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class PendingChangeStoreTest {

  /**
   * Wire the store's {@code @Autowired @Lazy self} field to {@code store} itself so {@code
   * create(...)} can delegate to {@code self.attemptCreate(...)} without a Spring context (a null
   * {@code self} would NPE). {@code REQUIRES_NEW} does nothing under a plain unit test, but the
   * supersede→insert→retry logic runs exactly as in production and is verifiable on the mock.
   */
  private static PendingChangeStore newStore(
      PendingChangeRepository repo, ApplicationEventPublisher events, AdaptationConfig config) {
    PendingChangeStore store = new PendingChangeStore(repo, events, config);
    ReflectionTestUtils.setField(store, "self", store);
    return store;
  }

  @Test
  void create_supersedes_existing_pending_then_inserts_new() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    AdaptationConfig config = config();

    PendingChange existing = pending(UUID.randomUUID());
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.of(existing));
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));

    PendingChangeStore store = newStore(repo, events, config);
    UUID newId =
        store.create(
            job(),
            response(),
            ChangeDimension.SALT_LEVEL,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "v1");

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
  void race_retry_on_data_integrity_violation_settles_with_one_winning_pending() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    // Attempt 1 races the no-existing-PENDING path; its INSERT loses the unique-index race.
    // Attempt 2 (fresh tx) re-reads and now sees the concurrent winner's committed PENDING.
    PendingChange winner = pending(UUID.randomUUID());
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winner));
    // saveAndFlush is now ONLY the new-row INSERT (supersession + back-fill use plain flush()).
    // Call 1 = attempt-1 INSERT -> throws. Call 2 = attempt-2 INSERT -> succeeds.
    when(repo.saveAndFlush(any(PendingChange.class)))
        .thenThrow(new DataIntegrityViolationException("constraint"))
        .thenAnswer(inv -> inv.getArgument(0));

    PendingChangeStore store = newStore(repo, events, config());
    UUID newId =
        store.create(
            job(),
            response(),
            ChangeDimension.SALT_LEVEL,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "v1");
    assertThat(newId).isNotNull();

    // 2 saveAndFlush (both new-row INSERT attempts): attempt-1 throws, attempt-2 succeeds.
    ArgumentCaptor<PendingChange> cap = ArgumentCaptor.forClass(PendingChange.class);
    verify(repo, times(2)).saveAndFlush(cap.capture());
    // Last-writer-wins: the concurrent winner is superseded by the retry's new row...
    assertThat(winner.getStatus()).isEqualTo(PendingChangeStatus.SUPERSEDED);
    assertThat(winner.getSupersededBy()).isEqualTo(newId);
    // ...and the newly inserted row settles as PENDING (no more SUPERSEDED-flagged retry insert).
    PendingChange newRow =
        cap.getAllValues().stream()
            .filter(pc -> newId.equals(pc.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(newRow.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
    // Event is published once, for the surviving PENDING row only.
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
    PendingChangeStore store = newStore(repo, events, config());

    UUID id =
        store.create(
            job(),
            response(),
            ChangeDimension.SALT_LEVEL,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "v1");

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

    PendingChangeStore store = newStore(repo, events, config());
    store.create(
        job(), response(), ChangeDimension.SALT_LEVEL, UUID.randomUUID(), UUID.randomUUID(), "v1");

    // Kills the VoidMethodCall mutant that removes setResolvedAt(now).
    assertThat(existing.getResolvedAt()).isNotNull();
  }

  @Test
  void inserted_row_uses_finalDiffJson_when_present_and_carries_branch_fields() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());
    PendingChangeStore store = newStore(repo, events, config());

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
        job(), resp, ChangeDimension.SALT_LEVEL, UUID.randomUUID(), UUID.randomUUID(), "v7");

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
    PendingChangeStore store = newStore(repo, events, config());

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
        job(), resp, ChangeDimension.SALT_LEVEL, UUID.randomUUID(), UUID.randomUUID(), null);

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
  void retry_path_inserts_row_as_pending_not_superseded() {
    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    PendingChange winner = pending(UUID.randomUUID());
    when(repo.findByRecipeIdAndChangeDimensionAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winner));
    when(repo.saveAndFlush(any(PendingChange.class)))
        .thenThrow(new DataIntegrityViolationException("constraint"))
        .thenAnswer(inv -> inv.getArgument(0));
    PendingChangeStore store = newStore(repo, events, config());

    UUID newId =
        store.create(
            job(),
            response(),
            ChangeDimension.SALT_LEVEL,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "v1");

    // Only the new-row INSERTs go through saveAndFlush: attempt-1 (throws) + attempt-2 (succeeds).
    // The winner's status-flip and back-fill use plain flush().
    ArgumentCaptor<PendingChange> cap = ArgumentCaptor.forClass(PendingChange.class);
    verify(repo, times(2)).saveAndFlush(cap.capture());
    // Last-writer-wins: the retry ALWAYS inserts the new row as PENDING (the old SUPERSEDED-flagged
    // retry insert is gone). The superseded one is the prior winner, not the freshly inserted row.
    PendingChange newRow =
        cap.getAllValues().stream()
            .filter(pc -> newId.equals(pc.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(newRow.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
    assertThat(winner.getStatus()).isEqualTo(PendingChangeStatus.SUPERSEDED);
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
