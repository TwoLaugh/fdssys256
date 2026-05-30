package com.example.mealprep.auth.exception;

import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator.Reason;
import java.util.List;

/**
 * Thrown by the service layer when a chosen password fails a strength rule that the
 * {@code @ValidPassword} bean-validation annotation cannot see — specifically {@code
 * MATCHES_USERNAME} (the annotation has no access to the username) and {@code BREACHED}.
 *
 * <p>Carries the machine-readable {@link Reason} codes only. The HTTP detail string is a fixed
 * generic message; the codes surface as a {@code reasons[]} ProblemDetail extension so the UI can
 * render actionable feedback <b>without leaking block-list contents</b> (mapped in {@code
 * AuthExceptionHandler} to a 400). This replaces the previous raw {@code IllegalArgumentException},
 * whose message echoed the reason names (e.g. {@code [BREACHED]}) into the human-readable detail.
 */
public class WeakPasswordException extends RuntimeException {

  /** Fixed, non-leaking detail. The specific reasons live in {@link #reasons()} as codes. */
  public static final String GENERIC_DETAIL = "Password does not meet the strength policy.";

  private final List<Reason> reasons;

  public WeakPasswordException(List<Reason> reasons) {
    super(GENERIC_DETAIL);
    this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public List<Reason> reasons() {
    return reasons;
  }
}
