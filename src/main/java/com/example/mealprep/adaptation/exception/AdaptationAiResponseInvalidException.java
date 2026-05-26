package com.example.mealprep.adaptation.exception;

import com.example.mealprep.ai.exception.AiException;

/**
 * Terminal Stage-C failure: the AI dispatch raised a non-deferrable {@code ai.exception.*} — a
 * malformed/unparseable model output ({@code AiInvalidResponseException}) or a caller-bug 4xx from
 * the upstream ({@code AiInvalidRequestException}). Unlike {@link AdaptationAiUnavailableException}
 * (a deferrable graceful-degrade signal that maps to {@code AI_UNAVAILABLE}), this is a permanent
 * failure for the job and routes to a terminal {@code LLM_ERROR} reason.
 *
 * <p>Wrapping the raw {@code ai.exception.*} here keeps {@code AdaptationServiceImpl.processJob}
 * able to terminalise the job via {@code handleFailure} — previously these bare AI exceptions
 * escaped the worker's adaptation-only catch blocks, leaving the job stuck {@code RUNNING} forever.
 */
public class AdaptationAiResponseInvalidException extends AdaptationException {

  public AdaptationAiResponseInvalidException(String message, AiException cause) {
    super(message, cause);
  }
}
