package com.example.mealprep.ai.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when {@code PromptTemplateLoader} INSERTs a new prompt template version. Implements
 * {@link ScopeChangedEvent} with {@code scopeKind="prompt-template"} and {@code
 * scopeId=template.id} so cache-invalidation listeners can subscribe by base type.
 */
public record PromptTemplateLoadedEvent(
    UUID templateId, String name, int version, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "prompt-template";
  }

  @Override
  public UUID scopeId() {
    return templateId;
  }
}
