package com.example.mealprep.core.origin;

/**
 * Origin of an inbound HTTP request as carried by the {@code X-Origin} header. Per {@code
 * design/origin-tracking-pattern.md} §Origin header — the initial set of values; extend as new
 * non-user surfaces appear.
 *
 * <p>Absence of {@code X-Origin} on a request resolves to {@link #USER} — the safest default, since
 * a user has the most-restricted permission set.
 *
 * @see ActorType for the coarser USER/AI/SYSTEM grouping used in audit rows
 */
public enum Origin {

  /** Direct user-initiated call. Default when no {@code X-Origin} header is present. */
  USER,

  /** Feedback classifier applying classifier output to user-scoped state via REST. */
  AI_FEEDBACK,

  /** Recipe adaptation pipeline writing pending changes via REST. */
  AI_ADAPTATION,

  /** Scheduled scanners (expiry, defrost, price refresh) acting on a user's behalf. */
  SYSTEM_SCHEDULED,

  /** Mid-week re-optimisation triggered by external events. */
  SYSTEM_REOPT,

  /** Discovery pipeline saving an imported recipe. */
  SYSTEM_DISCOVERY;

  /**
   * Project this fine-grained origin onto the audit-row {@link ActorType} (USER/AI/SYSTEM).
   *
   * <p>The mapping is intentionally lossy — audit rows only need to know "was this a person, an AI,
   * or a scheduled system process". The full origin is preserved separately in {@code
   * origin_trace}.
   */
  public ActorType toActorType() {
    return switch (this) {
      case USER -> ActorType.USER;
      case AI_FEEDBACK, AI_ADAPTATION -> ActorType.AI;
      case SYSTEM_SCHEDULED, SYSTEM_REOPT, SYSTEM_DISCOVERY -> ActorType.SYSTEM;
    };
  }
}
