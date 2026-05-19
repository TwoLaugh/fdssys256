package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.event.AdaptationCandidateProducedEvent;
import com.example.mealprep.adaptation.event.AdaptationEvent;
import com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobFailedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobStartedEvent;
import com.example.mealprep.adaptation.event.PendingChangeAcceptedEvent;
import com.example.mealprep.adaptation.event.PendingChangeCreatedEvent;
import com.example.mealprep.adaptation.event.PendingChangeRejectedEvent;
import com.example.mealprep.adaptation.event.PendingChangeSupersededEvent;
import com.example.mealprep.adaptation.event.PlannerHintEmittedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code scopeKind()} / {@code scopeId()} projections on all nine concrete {@link
 * AdaptationEvent} records. PIT flagged these as NO_COVERAGE: the {@code EmptyObjectReturnVals}
 * mutant turns {@code "recipe"} into {@code ""} and the {@code NullReturnVals} mutant turns the
 * {@code recipeId} return into {@code null}. Each assertion below fails under the respective
 * mutation, so this kills the 18 accessor mutants without any production change.
 */
class AdaptationEventScopeProjectionTest {

  private static final UUID RECIPE = UUID.randomUUID();
  private static final UUID TRACE = UUID.randomUUID();
  private static final Instant NOW = Instant.now();

  private static void assertScope(AdaptationEvent e, UUID expectedRecipe) {
    // scopeKind(): EmptyObjectReturnVals -> "" would fail this exact-equals.
    assertThat(e.scopeKind()).isEqualTo("recipe");
    // scopeId(): NullReturnVals -> null would fail this non-null + equals.
    assertThat(e.scopeId()).isNotNull().isEqualTo(expectedRecipe);
    assertThat(e.recipeId()).isEqualTo(expectedRecipe);
  }

  @Test
  void adaptationJobStartedEvent_projects_recipe_scope() {
    assertScope(
        new AdaptationJobStartedEvent(
            UUID.randomUUID(),
            RECIPE,
            UUID.randomUUID(),
            JobSource.IMPORT,
            JobPriority.SYNC,
            TRACE,
            NOW),
        RECIPE);
  }

  @Test
  void adaptationCandidateProducedEvent_projects_recipe_scope() {
    assertScope(
        new AdaptationCandidateProducedEvent(
            UUID.randomUUID(), RECIPE, 3, BigDecimal.valueOf(0.8), TRACE, NOW),
        RECIPE);
  }

  @Test
  void adaptationJobCompletedEvent_projects_recipe_scope() {
    assertScope(
        new AdaptationJobCompletedEvent(
            UUID.randomUUID(),
            RECIPE,
            OutcomeKind.VERSION_CREATED,
            UUID.randomUUID(),
            AdaptationClassification.VERSION,
            BigDecimal.valueOf(0.9),
            TRACE,
            NOW),
        RECIPE);
  }

  @Test
  void adaptationJobFailedEvent_projects_recipe_scope() {
    assertScope(
        new AdaptationJobFailedEvent(
            UUID.randomUUID(), RECIPE, JobFailureReason.AI_UNAVAILABLE, "boom", TRACE, NOW),
        RECIPE);
  }

  @Test
  void pendingChangeCreatedEvent_projects_recipe_scope() {
    assertScope(
        new PendingChangeCreatedEvent(
            UUID.randomUUID(),
            RECIPE,
            UUID.randomUUID(),
            ChangeDimension.PROTEIN,
            BigDecimal.valueOf(0.7),
            BigDecimal.valueOf(0.6),
            TRACE,
            NOW),
        RECIPE);
  }

  @Test
  void pendingChangeSupersededEvent_projects_recipe_scope() {
    assertScope(
        new PendingChangeSupersededEvent(
            UUID.randomUUID(), UUID.randomUUID(), RECIPE, ChangeDimension.SALT_LEVEL, TRACE, NOW),
        RECIPE);
  }

  @Test
  void pendingChangeAcceptedEvent_projects_recipe_scope() {
    assertScope(
        new PendingChangeAcceptedEvent(
            UUID.randomUUID(), RECIPE, UUID.randomUUID(), UUID.randomUUID(), true, TRACE, NOW),
        RECIPE);
  }

  @Test
  void pendingChangeRejectedEvent_projects_recipe_scope() {
    assertScope(
        new PendingChangeRejectedEvent(UUID.randomUUID(), RECIPE, UUID.randomUUID(), TRACE, NOW),
        RECIPE);
  }

  @Test
  void plannerHintEmittedEvent_projects_recipe_scope() {
    assertScope(
        new PlannerHintEmittedEvent(
            UUID.randomUUID(),
            RECIPE,
            UUID.randomUUID(),
            HintType.PREP_LEAD_TIME,
            HintSeverity.INFO,
            TRACE,
            NOW),
        RECIPE);
  }
}
