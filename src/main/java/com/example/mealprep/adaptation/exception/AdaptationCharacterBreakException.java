package com.example.mealprep.adaptation.exception;

/**
 * Thrown by 01c's {@code CharacterPreservationGate} when the chosen candidate's preservation score
 * drops below the floor and the AI did NOT classify the change as a branch with high coherence.
 * Mapped to HTTP 422.
 */
public class AdaptationCharacterBreakException extends AdaptationException {

  public AdaptationCharacterBreakException(String message) {
    super(message);
  }
}
