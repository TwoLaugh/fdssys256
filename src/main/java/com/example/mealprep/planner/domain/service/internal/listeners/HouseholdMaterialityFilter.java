package com.example.mealprep.planner.domain.service.internal.listeners;

import com.example.mealprep.household.event.HouseholdSettingsChangedEvent;
import com.example.mealprep.planner.domain.entity.Plan;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a {@link HouseholdSettingsChangedEvent} materially affects a plan (planner-01k §6
 * + §9).
 *
 * <p>The event carries the dotted {@code changedFieldPaths} that were written to the household
 * settings audit log in the same transaction. Per the ticket / LLD §triggers, a change is material
 * when it touches the planning surface:
 *
 * <ul>
 *   <li>slot structure — {@code slotDefaults.*}, {@code mealStructure.*}, {@code slots.*} (a meal
 *       type added/removed reshapes the plan skeleton),
 *   <li>batch-cook policy — {@code batch*} (changes how cook sessions are grouped),
 *   <li>eating window — {@code eatingWindow.*} (constrains slot timing),
 *   <li>household membership — {@code members.*} / {@code member*} (a user added/removed changes
 *       the eaters and the merged-preference set).
 * </ul>
 *
 * <p>Purely cosmetic changes — {@code displayName}, {@code timezone}, {@code locale} — are NOT
 * material; surfacing a re-opt for a rename would be thrash. An empty path set is a defensive no-op
 * (household-01b already suppresses identical-document re-submits).
 *
 * <p>Prefix matching is case-insensitive and segment-aware ({@code foo} matches {@code foo} and
 * {@code foo.bar} but not {@code foobar}).
 */
@Component
class HouseholdMaterialityFilter {

  private static final Logger log = LoggerFactory.getLogger(HouseholdMaterialityFilter.class);

  /** Dotted-path prefixes whose change reshapes the plan and warrants a re-opt suggestion. */
  private static final Set<String> MATERIAL_PREFIXES =
      Set.of(
          "slotdefaults",
          "mealstructure",
          "slots",
          "slotconfiguration",
          "batch",
          "batchpolicy",
          "batchcook",
          "eatingwindow",
          "members",
          "member",
          "membership");

  boolean isMaterial(HouseholdSettingsChangedEvent event, Plan plan) {
    Set<String> paths = event.changedFieldPaths();
    if (paths == null || paths.isEmpty()) {
      log.debug(
          "HouseholdSettingsChangedEvent for household {} carries no changed paths; immaterial for"
              + " plan {}",
          event.householdId(),
          plan.getId());
      return false;
    }
    for (String rawPath : paths) {
      if (rawPath == null || rawPath.isBlank()) {
        continue;
      }
      String firstSegment = firstSegment(rawPath);
      if (MATERIAL_PREFIXES.contains(firstSegment)) {
        return true;
      }
    }
    log.debug(
        "HouseholdSettingsChangedEvent for household {} touched only cosmetic paths {}; immaterial"
            + " for plan {}",
        event.householdId(),
        paths,
        plan.getId());
    return false;
  }

  /**
   * The leading path segment, lower-cased, with any array/index suffix stripped — so {@code
   * members[2].userId} and {@code members.0.id} both reduce to {@code members}. A bracket or a dot
   * (whichever comes first) terminates the segment.
   */
  private static String firstSegment(String dottedPath) {
    String lower = dottedPath.toLowerCase(Locale.ROOT).trim();
    int end = lower.length();
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      if (c == '.' || c == '[') {
        end = i;
        break;
      }
    }
    return lower.substring(0, end);
  }
}
