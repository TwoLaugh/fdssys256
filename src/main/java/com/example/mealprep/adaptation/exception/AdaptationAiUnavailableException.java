package com.example.mealprep.adaptation.exception;

import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiException;
import com.example.mealprep.ai.exception.AiUnavailableException;

/**
 * Block-and-prompt surface for monthly-cap or hard-disabled AI per the style-guide's graceful-
 * degradation contract. Wraps a deferrable underlying AI failure — either an {@link
 * AiUnavailableException} (upstream 5xx / network / retries exhausted) or an {@link
 * AiCostBudgetExceededException} (per-user rolling cost cap; a rate concept, not a permanent
 * failure). Notification module surfaces "AI features paused" to the user. Mapped to HTTP 503.
 *
 * <p>The cause type is the {@code ai.exception} root {@link AiException} so both deferrable
 * subtypes route here; terminal subtypes ({@code AiInvalidResponseException}/{@code
 * AiInvalidRequestException}) route to {@link AdaptationAiResponseInvalidException} instead.
 */
public class AdaptationAiUnavailableException extends AdaptationException {

  public AdaptationAiUnavailableException(String message, AiException cause) {
    super(message, cause);
  }
}
