package com.example.mealprep.nutrition.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an accept flow completes (gate passed and applier ran) per
 * LLD line 904. The {@code userModified} flag tells listeners whether the applied instruction came
 * from the original payload or the user's accept-time override.
 *
 * <p>No rejected sibling event — LLD §Events doesn't declare one; v1 omits it.
 *
 * <p>{@code scopeKind = "nutrition-health-directive"}, {@code scopeId = directiveId}.
 */
public record HealthDirectiveAcceptedEvent(
    UUID userId,
    UUID directiveId,
    DirectiveType directiveType,
    String mapsToModel,
    String mapsToTier,
    boolean userModified,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "nutrition-health-directive";
  }

  @Override
  public UUID scopeId() {
    return directiveId;
  }
}
