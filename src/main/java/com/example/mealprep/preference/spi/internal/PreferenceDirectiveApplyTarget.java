package com.example.mealprep.preference.spi.internal;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.spi.DirectiveApplyTarget;
import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.preference.domain.service.internal.PreferenceServiceImpl;
import com.example.mealprep.preference.exception.InvalidDirectivePreferenceRouteException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Real {@link DirectiveApplyTarget} for {@code preference_model} health directives — the preference
 * side of nutrition/01j. As a plain {@code @Component} it out-ranks the nutrition module's {@code
 * NoopDirectiveApplyTarget} {@code @Bean @ConditionalOnMissingBean}, so with preference on the
 * classpath the {@code DirectiveApplier}'s {@code ObjectProvider.getObject()} resolves this bean.
 * (The Noop stays in place for nutrition-only test slices that don't load preference → 422.)
 *
 * <p>Translates an ingredient-restriction directive into a hard-constraint mutation: it loads the
 * user's current constraints, adds {@code instruction.target()} as an intolerance via {@link
 * PreferenceUpdateService#updateHardConstraints} (riding that method's per-field audit +
 * {@code @Version} bump + {@code HardConstraintsUpdatedEvent}), and, for a temporary directive,
 * stamps the added row's {@code source_directive_id} + {@code auto_expires_at} so the deferred
 * auto-expiry sweep can reverse it.
 *
 * <p>Per the SPI contract ({@code DirectiveApplyTarget} javadoc) this MUST join the caller's
 * transaction — there is no {@code @Transactional(REQUIRES_NEW)} here, so a downstream failure
 * rolls back the directive's status update too.
 */
@Component
public class PreferenceDirectiveApplyTarget implements DirectiveApplyTarget {

  private static final Logger log = LoggerFactory.getLogger(PreferenceDirectiveApplyTarget.class);

  /** Directive {@code action}s that map to a hard-constraint (intolerance) addition. */
  private static final Set<String> HARD_CONSTRAINT_ADD_ACTIONS =
      Set.of("restrict_ingredient", "eliminate_then_reintroduce");

  /** Severity descriptor for a directive-sourced intolerance (free-form, varchar(32)). */
  private static final String DIRECTIVE_SEVERITY = "avoid";

  private final PreferenceQueryService queryService;
  private final PreferenceUpdateService updateService;
  private final PreferenceServiceImpl preferenceServiceImpl;

  public PreferenceDirectiveApplyTarget(
      PreferenceQueryService queryService,
      PreferenceUpdateService updateService,
      PreferenceServiceImpl preferenceServiceImpl) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.preferenceServiceImpl = preferenceServiceImpl;
  }

  @Override
  public void applyPreferenceDirective(
      UUID userId,
      DirectiveInstructionDocument instruction,
      boolean temporary,
      Instant autoExpiresAt,
      UUID directiveId,
      UUID actorUserId) {
    String action = instruction == null ? null : instruction.action();
    if (action == null || !HARD_CONSTRAINT_ADD_ACTIONS.contains(action)) {
      // Unmapped action → a routing bug worth surfacing loudly (preserves the Noop's "clear error,
      // not silent no-op" intent). Mapped to 422 by PreferenceExceptionHandler.
      throw new InvalidDirectivePreferenceRouteException(
          "directive action '"
              + action
              + "' does not map to a preference hard-constraint mutation (directiveId="
              + directiveId
              + ")");
    }

    String target = instruction.target();
    if (target == null || target.isBlank()) {
      throw new InvalidDirectivePreferenceRouteException(
          "directive action '"
              + action
              + "' requires a non-blank target (directiveId="
              + directiveId
              + ")");
    }

    // Lazily initialise the omnivore-default aggregate if the user has none yet (idempotent). A
    // directive accept must not 404 just because the user never edited their hard constraints; the
    // restriction itself is the first hard-constraint write.
    HardConstraintsDto current =
        queryService.getHardConstraints(userId).orElseGet(() -> initialiseDefaults(userId));

    boolean alreadyPresent =
        current.intolerances() != null
            && current.intolerances().stream()
                .anyMatch(i -> target.equalsIgnoreCase(i.substance()));

    if (alreadyPresent) {
      // Dedup: the target is already a hard constraint. The add would be a no-op diff anyway; skip
      // the round-trip. (Re-accept is blocked upstream, but a user-authored duplicate could exist.)
      log.info(
          "preference directive apply: target '{}' already an intolerance userId={} directiveId={}"
              + " — no add",
          target,
          userId,
          directiveId);
      return;
    }

    List<HardIntoleranceDto> intolerances =
        current.intolerances() == null
            ? new ArrayList<>()
            : new ArrayList<>(current.intolerances());
    intolerances.add(
        new HardIntoleranceDto(target, DIRECTIVE_SEVERITY, directiveSourceNote(directiveId)));

    DietaryIdentityDto identity = current.dietaryIdentity();
    UpdateHardConstraintsRequest request =
        new UpdateHardConstraintsRequest(
            current.allergies() == null ? List.of() : current.allergies(),
            new DietaryIdentityDto(
                identity.base(),
                identity.labelForDisplay(),
                identity.exceptions() == null ? List.of() : identity.exceptions()),
            current.medicalDiets() == null ? List.of() : current.medicalDiets(),
            intolerances,
            current.ageRestrictions() == null ? List.of() : current.ageRestrictions(),
            current.version(),
            // System-driven directive apply: it only ADDS an intolerance to the current snapshot
            // (never removes a Tier-1), so the GAP-04 gate would not fire anyway — but this is an
            // authoritative system actor, not a user, so confirm explicitly to keep it one-step and
            // immune to any future edge that would otherwise surface a 409 mid-transaction.
            true);

    updateService.updateHardConstraints(userId, request, actorUserId);

    if (temporary) {
      // Stamp provenance on the just-added row so the auto-expiry sweep can reverse it.
      preferenceServiceImpl.stampTemporaryConstraint(userId, target, directiveId, autoExpiresAt);
    }

    log.info(
        "preference directive applied userId={} directiveId={} action={} target={} temporary={}",
        userId,
        directiveId,
        action,
        target,
        temporary);
  }

  private HardConstraintsDto initialiseDefaults(UUID userId) {
    log.info("preference directive apply: initialising default hard constraints userId={}", userId);
    return updateService.initialiseHardConstraints(userId);
  }

  private static String directiveSourceNote(UUID directiveId) {
    return "health directive " + directiveId;
  }
}
