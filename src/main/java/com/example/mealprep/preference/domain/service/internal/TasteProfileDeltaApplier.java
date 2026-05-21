package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import org.springframework.stereotype.Component;

/**
 * Applies a batch of {@code TasteProfileDelta} operations to a {@link TasteProfileDocument}.
 *
 * <p><b>Stub in 01c.</b> The real implementation — eight per-op handlers plus the {@code
 * TasteProfileBudgetGuard} token-budget enforcement — ships in the deferred {@code
 * 01c-delta-applier} ticket. Until then, every call throws {@link UnsupportedOperationException}
 * with a pointer to the deferred ticket.
 *
 * <p>The bridge in {@code tickets/feedback/01g} compiles against this interface so the wiring lands
 * now and only the body needs to be filled in later.
 */
public interface TasteProfileDeltaApplier {

  /**
   * Reference message used by both the stub and any error path that hits a still-unimplemented op.
   */
  String DEFERRED_MESSAGE = "delta application landing in deferred ticket 01c-delta-applier";

  /**
   * Applies the deltas in {@code request} to {@code current} and returns the new document. Bumps
   * the document's internal {@code version} field and updates {@code lastUpdated} / {@code
   * basedOnFeedbackCount} as a side-effect encoded in the return value.
   *
   * @throws UnsupportedOperationException always, in 01c.
   */
  TasteProfileDocument apply(TasteProfileDocument current, ApplyTasteProfileDeltasRequest request);

  /**
   * Spring-managed stub. Throws on every call. Replaced in 01c-delta-applier with a real impl; the
   * {@code @Service} lookup remains identical, so no rewiring is required.
   */
  @Component
  class NoopStub implements TasteProfileDeltaApplier {
    @Override
    public TasteProfileDocument apply(
        TasteProfileDocument current, ApplyTasteProfileDeltasRequest request) {
      throw new UnsupportedOperationException(DEFERRED_MESSAGE);
    }
  }
}
