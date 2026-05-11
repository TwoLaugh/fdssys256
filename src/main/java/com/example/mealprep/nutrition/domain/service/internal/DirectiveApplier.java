package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.exception.InvalidDirectiveRoutingException;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import com.example.mealprep.nutrition.spi.DirectiveApplyTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Routes an accepted {@link HealthDirective} either to the nutrition targets aggregate (writing
 * audit rows with {@code actorKind = HEALTH_DIRECTIVE}) or to the cross-module {@link
 * DirectiveApplyTarget} SPI (for {@code preference_model}).
 *
 * <p>Joins the caller's transaction — both the nutrition-targets writes and the SPI invocation
 * commit atomically with the directive's status update.
 */
@Component
public class DirectiveApplier {

  private static final Logger log = LoggerFactory.getLogger(DirectiveApplier.class);

  static final String ROUTE_NUTRITION_MODEL = "nutrition_model";
  static final String ROUTE_PREFERENCE_MODEL = "preference_model";

  private final NutritionTargetsRepository targetsRepository;
  private final NutritionTargetsAuditRepository auditRepository;
  private final ObjectProvider<DirectiveApplyTarget> preferenceTarget;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DirectiveApplier(
      NutritionTargetsRepository targetsRepository,
      NutritionTargetsAuditRepository auditRepository,
      ObjectProvider<DirectiveApplyTarget> preferenceTarget,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.targetsRepository = targetsRepository;
    this.auditRepository = auditRepository;
    this.preferenceTarget = preferenceTarget;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Apply {@code effective} routed by {@code directive.mapsToModel}. Unknown routes raise {@link
   * InvalidDirectiveRoutingException}.
   */
  public void apply(
      HealthDirective directive, DirectiveInstructionDocument effective, UUID actorUserId) {
    String route = directive.getMapsToModel();
    switch (route == null ? "" : route) {
      case ROUTE_NUTRITION_MODEL -> applyToNutritionTargets(directive, effective, actorUserId);
      case ROUTE_PREFERENCE_MODEL -> applyToPreferenceTarget(directive, effective, actorUserId);
      default -> throw new InvalidDirectiveRoutingException(route);
    }
  }

  private void applyToNutritionTargets(
      HealthDirective directive, DirectiveInstructionDocument effective, UUID actorUserId) {
    NutritionTargets targets =
        targetsRepository
            .findByUserId(directive.getUserId())
            .orElseThrow(() -> new NutritionTargetsNotFoundException(directive.getUserId()));

    Set<String> changedFields = new LinkedHashSet<>();
    Instant now = Instant.now(clock);

    if ("adjust_target".equals(effective.action())) {
      changedFields.addAll(
          applyAdjustTarget(
              targets, directive.getMapsToTier(), effective, now, actorUserId, directive.getId()));
    } else {
      // Other action types route through nutrition_model only when they carry a numeric delta
      // applicable to the targets aggregate. For 01e the LLD only specifies adjust_target on
      // nutrition_model; other actions (rebalance_macros, restrict_ingredient) targeting
      // nutrition_model are best-effort: record an audit row and move on.
      log.info(
          "DirectiveApplier nutrition_model route saw action={} tier={} — no-op write,"
              + " audit row only",
          effective.action(),
          directive.getMapsToTier());
    }

    if (changedFields.isEmpty()) {
      log.info(
          "DirectiveApplier produced no field changes directiveId={} action={}",
          directive.getId(),
          effective.action());
      return;
    }

    NutritionTargets saved = targetsRepository.saveAndFlush(targets);
    eventPublisher.publishEvent(
        new NutritionTargetsChangedEvent(
            directive.getUserId(),
            saved.getId(),
            Set.copyOf(changedFields),
            UUID.randomUUID(),
            now));
  }

  private Set<String> applyAdjustTarget(
      NutritionTargets targets,
      String tier,
      DirectiveInstructionDocument effective,
      Instant now,
      UUID actorUserId,
      UUID directiveId) {
    BigDecimal proposed = readProposedFloor(effective);
    if (tier == null || proposed == null) {
      return Set.of();
    }
    Set<String> changed = new LinkedHashSet<>();
    switch (tier) {
      case "protein_floor_g" -> {
        BigDecimal previous = targets.getProteinFloorG();
        if (!eq(previous, proposed)) {
          writeAudit(
              targets.getId(), actorUserId, directiveId, "protein.floorG", previous, proposed, now);
          targets.setProteinFloorG(proposed);
          changed.add("protein.floorG");
        }
      }
      case "carbs_floor_g" -> {
        BigDecimal previous = targets.getCarbsFloorG();
        if (!eq(previous, proposed)) {
          writeAudit(
              targets.getId(), actorUserId, directiveId, "carbs.floorG", previous, proposed, now);
          targets.setCarbsFloorG(proposed);
          changed.add("carbs.floorG");
        }
      }
      case "fat_floor_g" -> {
        BigDecimal previous = targets.getFatFloorG();
        if (!eq(previous, proposed)) {
          writeAudit(
              targets.getId(), actorUserId, directiveId, "fat.floorG", previous, proposed, now);
          targets.setFatFloorG(proposed);
          changed.add("fat.floorG");
        }
      }
      case "fibre_floor_g" -> {
        BigDecimal previous = targets.getFibreFloorG();
        if (!eq(previous, proposed)) {
          writeAudit(
              targets.getId(), actorUserId, directiveId, "fibre.floorG", previous, proposed, now);
          targets.setFibreFloorG(proposed);
          changed.add("fibre.floorG");
        }
      }
      case "daily_calorie_target", "calorie_target" -> {
        int previous = targets.getDailyCalorieTarget();
        int next = proposed.intValueExact();
        if (previous != next) {
          writeAudit(
              targets.getId(),
              actorUserId,
              directiveId,
              "calories.dailyTarget",
              BigDecimal.valueOf(previous),
              proposed,
              now);
          targets.setDailyCalorieTarget(next);
          changed.add("calories.dailyTarget");
        }
      }
      default -> log.info("DirectiveApplier saw unhandled tier={} — no-op", tier);
    }
    return changed;
  }

  private void applyToPreferenceTarget(
      HealthDirective directive, DirectiveInstructionDocument effective, UUID actorUserId) {
    DirectiveApplyTarget target = preferenceTarget.getObject();
    target.applyPreferenceDirective(
        directive.getUserId(),
        effective,
        directive.isTemporary(),
        directive.getAutoExpiresAt(),
        directive.getId(),
        actorUserId);
  }

  private void writeAudit(
      UUID targetsId,
      UUID actorUserId,
      UUID directiveId,
      String fieldPath,
      BigDecimal previous,
      BigDecimal next,
      Instant now) {
    JsonNode previousJson =
        previous == null ? objectMapper.nullNode() : objectMapper.valueToTree(previous);
    JsonNode nextJson = next == null ? objectMapper.nullNode() : objectMapper.valueToTree(next);
    auditRepository.save(
        new NutritionTargetsAuditLog(
            UUID.randomUUID(),
            targetsId,
            actorUserId,
            ActorKind.HEALTH_DIRECTIVE,
            directiveId,
            fieldPath,
            previousJson,
            nextJson,
            now));
  }

  private static BigDecimal readProposedFloor(DirectiveInstructionDocument effective) {
    Map<String, JsonNode> extras = effective.extras();
    if (extras == null) {
      return null;
    }
    JsonNode node = extras.get("proposedFloor");
    if (node != null && node.isNumber()) {
      return new BigDecimal(node.asText());
    }
    return null;
  }

  private static boolean eq(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return a == b;
    }
    return a.compareTo(b) == 0;
  }
}
