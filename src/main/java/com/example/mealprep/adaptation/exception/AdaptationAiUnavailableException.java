package com.example.mealprep.adaptation.exception;

import com.example.mealprep.ai.exception.AiUnavailableException;

/**
 * Block-and-prompt surface for monthly-cap or hard-disabled AI per the style-guide's graceful-
 * degradation contract. Wraps the underlying {@link AiUnavailableException}; Notification module
 * surfaces "AI features paused" to the user. Mapped to HTTP 503.
 */
public class AdaptationAiUnavailableException extends AdaptationException {

  public AdaptationAiUnavailableException(String message, AiUnavailableException cause) {
    super(message, cause);
  }
}
