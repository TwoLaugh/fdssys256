package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.adaptation.exception.AdaptationCharacterBreakException;
import com.example.mealprep.adaptation.exception.AdaptationException;
import com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException;
import com.example.mealprep.adaptation.exception.AdaptationJobNotFoundException;
import com.example.mealprep.adaptation.exception.AdaptationLowConfidenceException;
import com.example.mealprep.adaptation.exception.AdaptationTraceNotFoundException;
import com.example.mealprep.adaptation.exception.LockTimeoutException;
import com.example.mealprep.adaptation.exception.PendingChangeExpiredException;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
import com.example.mealprep.adaptation.exception.PendingChangeSupersededException;
import com.example.mealprep.adaptation.exception.RebaseExhaustedException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import org.junit.jupiter.api.Test;

/**
 * Smoke unit test for every adaptation-pipeline exception class introduced in 01a. Confirms each
 * extends {@link AdaptationException}, carries the message / cause it was given, and that the
 * AI-unavailable wrapper preserves its {@link AiUnavailableException} cause.
 */
class AdaptationExceptionSmokeTest {

  @Test
  void all_subclasses_extend_AdaptationException() {
    assertThat(new AdaptationJobNotFoundException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new PendingChangeNotFoundException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new AdaptationTraceNotFoundException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new PendingChangeNotPendingException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new PendingChangeExpiredException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new AdaptationLowConfidenceException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new AdaptationCharacterBreakException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new AdaptationHardConstraintViolationException("x"))
        .isInstanceOf(AdaptationException.class);
    assertThat(new PendingChangeSupersededException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new LockTimeoutException("x")).isInstanceOf(AdaptationException.class);
    assertThat(new RebaseExhaustedException("x")).isInstanceOf(AdaptationException.class);
  }

  @Test
  void ai_unavailable_wrapper_preserves_underlying_cause() {
    AiUnavailableException root = new AiUnavailableException("monthly cap");
    AdaptationAiUnavailableException wrapped =
        new AdaptationAiUnavailableException("AI paused", root);
    assertThat(wrapped.getCause()).isSameAs(root);
    assertThat(wrapped.getMessage()).isEqualTo("AI paused");
    assertThat(wrapped).isInstanceOf(AdaptationException.class);
  }

  @Test
  void rebase_exhausted_supports_message_and_cause_ctors() {
    RuntimeException cause = new RuntimeException("inner");
    RebaseExhaustedException ex = new RebaseExhaustedException("attempt 3", cause);
    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex.getMessage()).isEqualTo("attempt 3");
  }
}
