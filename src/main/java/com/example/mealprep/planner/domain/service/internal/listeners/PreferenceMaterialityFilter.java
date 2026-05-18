package com.example.mealprep.planner.domain.service.internal.listeners;

import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a {@link HardConstraintsUpdatedEvent} materially affects a plan (planner-01k §5 +
 * §9).
 *
 * <p>LLD-divergence note: the ticket names the source event {@code PreferenceUpdatedEvent} with a
 * mixed hard/soft {@code changedFields}. The merged preference module (preference-01a) instead
 * publishes a dedicated {@link HardConstraintsUpdatedEvent} that fires <em>only</em> on a
 * hard-constraint mutation (allergy add/remove, dietary-identity change, equipment exclusion, etc.)
 * and carries the changed field names in {@link HardConstraintsUpdatedEvent#fieldsChanged()}.
 * Soft-preference nudges (cuisine/protein bucket weight tweaks) are published on a different event
 * the planner does not consume. Consequently <strong>every</strong> event that reaches this filter
 * is, by construction, a hard-constraint change — which the ticket / HLD class as ALWAYS material
 * ("safer to over-trigger re-opt than to miss a constraint change", ticket gotcha). The only
 * non-material case is the degenerate empty-field-set event (defensive — preference-01a already
 * suppresses no-op updates, but we re-assert it rather than thrash a re-opt on an empty change).
 */
@Component
class PreferenceMaterialityFilter {

  private static final Logger log = LoggerFactory.getLogger(PreferenceMaterialityFilter.class);

  boolean isMaterial(HardConstraintsUpdatedEvent event, Plan plan) {
    Set<String> changed = event.fieldsChanged();
    if (changed == null || changed.isEmpty()) {
      // Degenerate no-op change — nothing to react to. preference-01a should never emit this, but
      // a defensive skip avoids a pointless re-opt suggestion.
      log.debug(
          "HardConstraintsUpdatedEvent for user {} carries no changed fields; immaterial for"
              + " plan {}",
          event.userId(),
          plan.getId());
      return false;
    }
    // Any hard-constraint field change (allergy / dietary identity / equipment exclusion / …) can
    // invalidate a planned recipe — always material per HLD §triggers (hard-constraint changes
    // auto-suggest re-opt, high priority).
    return true;
  }
}
