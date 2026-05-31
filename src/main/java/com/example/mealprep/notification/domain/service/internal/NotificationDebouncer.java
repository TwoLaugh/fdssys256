package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.repository.NotificationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Finds and mutates the bundle target for a draft, per {@code lld/notification.md} §F9. A "bundle
 * target" is the most-recent open ({@code UNREAD}) notification of the same {@code (userId, kind)}
 * created within the user's debounce window, additionally matched on the draft's kind-specific
 * {@code bundlingKey} (e.g. {@code mealSlotId}, {@code planId}). When found, the existing row is
 * mutated rather than a new row inserted.
 *
 * <p>Most kinds <em>accumulate</em> (bundle_count++, append the new key, regenerate the body).
 * {@code PLANNER_REOPT_SUGGESTED} uses <em>replacement</em> semantics — a later re-opt overwrites
 * the payload/body (latest wins) and {@code bundle_count} stays 1.
 */
@Component
public class NotificationDebouncer {

  /**
   * Open rows scanned for a per-key bundle target. A per-key match can sit behind newer
   * different-key rows of the same {@code (user, kind)} (e.g. several distinct {@code mealSlotId}
   * defrost reminders), so we cannot inspect only the single newest row — a {@code LIMIT 1} lookup
   * would silently miss the older same-key open row and split the bundle. We fetch a bounded window
   * and scan it newest-first for the key match. The window is generous relative to the realistic
   * count of concurrently-open distinct keys for one user/kind inside a 30-minute debounce window.
   */
  static final int BUNDLE_SCAN_PAGE_SIZE = 50;

  private final NotificationRepository notificationRepository;
  private final ObjectMapper objectMapper;

  public NotificationDebouncer(
      NotificationRepository notificationRepository, ObjectMapper objectMapper) {
    this.notificationRepository = notificationRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * The most-recent open notification eligible to absorb this draft, or null when none.
   *
   * <p>Aggregate kinds (null {@code bundlingKey}) bundle onto the single newest open row. Per-key
   * kinds scan a bounded page of open rows newest-first for the one whose {@code bundle_keys}
   * contains {@code bundlingKey}; a newer open row of the same kind but a <em>different</em> key
   * must never hide an older same-key row (per {@code lld/notification.md} §F9).
   */
  public Notification findBundleTarget(
      java.util.UUID userId,
      NotificationKind kind,
      String bundlingKey,
      int debounceWindowMinutes,
      Instant now) {
    Instant since = now.minus(debounceWindowMinutes, ChronoUnit.MINUTES);
    if (bundlingKey == null) {
      // Aggregate kind — the single newest open row is the bundle target.
      List<Notification> open =
          notificationRepository.findOpenForBundling(userId, kind, since, PageRequest.of(0, 1));
      return open.isEmpty() ? null : open.get(0);
    }
    // Per-key kind — scan the bounded window newest-first for the key-matching row so a newer
    // different-key row does not hide an older same-key row.
    List<Notification> open =
        notificationRepository.findOpenForBundling(
            userId, kind, since, PageRequest.of(0, BUNDLE_SCAN_PAGE_SIZE));
    for (Notification candidate : open) {
      if (bundleKeysContain(candidate, bundlingKey)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Apply a draft to an existing bundle target. {@code REOPT_SUGGESTED} replaces; everything else
   * accumulates. The mutated entity's {@code @Version} increments on flush.
   */
  public void applyBundle(Notification target, NotificationDraft draft, Instant now) {
    if (target.getKind() == NotificationKind.PLANNER_REOPT_SUGGESTED) {
      // Replacement semantics — latest wins, count stays at 1.
      target.setPayload(draft.payload());
      target.setBody(draft.body());
      target.setTitle(draft.title());
      target.setSeverity(draft.severity());
      if (draft.bundlingKey() != null) {
        target.setBundleKeys(singletonKeyArray(draft.bundlingKey()));
      }
      return;
    }
    target.setBundleCount(target.getBundleCount() + 1);
    if (draft.bundlingKey() != null) {
      target.setBundleKeys(appendKey(target.getBundleKeys(), draft.bundlingKey()));
    }
    target.setBody(regenerateBody(target.getKind(), target.getBundleCount()));
  }

  private boolean bundleKeysContain(Notification notification, String key) {
    JsonNode keys = notification.getBundleKeys();
    if (keys == null || !keys.isArray()) {
      return false;
    }
    for (JsonNode element : keys) {
      if (key.equals(element.asText())) {
        return true;
      }
    }
    return false;
  }

  private ArrayNode appendKey(JsonNode existing, String key) {
    ArrayNode array;
    if (existing != null && existing.isArray()) {
      array = ((ArrayNode) existing).deepCopy();
    } else {
      array = objectMapper.createArrayNode();
    }
    array.add(key);
    return array;
  }

  ArrayNode singletonKeyArray(String key) {
    ArrayNode array = objectMapper.createArrayNode();
    array.add(key);
    return array;
  }

  /**
   * Regenerate the body from a kind-specific template. Copy is a placeholder per the LLD (UX phase
   * owns the wording); the bundle count is the load-bearing part the inbox renders.
   */
  private static String regenerateBody(NotificationKind kind, int bundleCount) {
    return switch (kind) {
      case PROVISION_ITEM_NEAR_EXPIRY -> bundleCount + " items are nearing expiry.";
      case PROVISION_ITEM_SPOILED -> bundleCount + " items have spoiled.";
      case PROVISION_DEFROST_REMINDER -> bundleCount + " items need defrosting.";
      case NUTRITION_INTAKE_DIVERGED -> bundleCount + " nutrition targets diverged.";
      case HEALTH_DIRECTIVE_RECEIVED -> bundleCount + " health directives received.";
      case PLANNER_PREP_REMINDER -> bundleCount + " meals need advance prep.";
      case PLANNER_REOPT_SUGGESTED -> "A re-optimisation is suggested.";
      case PLANNER_PLAN_GENERATED -> bundleCount + " plans were generated.";
      case STAPLE_REPLENISHMENT_NEEDED -> bundleCount + " staple items need replenishing.";
      case FEEDBACK_CONFIRMATION -> bundleCount + " of your feedback items were applied.";
    };
  }
}
