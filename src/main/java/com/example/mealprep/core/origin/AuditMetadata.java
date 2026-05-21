package com.example.mealprep.core.origin;

/**
 * Bundle of audit-row attribution columns persisted next to module-specific audit-log rows.
 * Returned by {@link #fromContext(OriginContext)} so services don't have to hand-pick the two
 * fields off the request-scoped context on every audit write.
 *
 * <p>Future per-module audit writers will accept this record alongside their existing fields, e.g.
 * {@code auditLog.write(..., AuditMetadata.fromContext(originContext))}. The {@code core-02b}
 * ticket does NOT modify the existing writers — that wiring lands per consumer (first one is {@code
 * feedback-01g}).
 *
 * @param actorType derived from {@link Origin}; never null
 * @param originTrace mirrors {@code X-Origin-Trace}; null for {@link ActorType#USER}
 */
public record AuditMetadata(ActorType actorType, String originTrace) {

  /**
   * Project the request-scoped {@link OriginContext} onto an audit row's attribution columns.
   * Equivalent to {@code new AuditMetadata(ctx.getOrigin().toActorType(), ctx.getOriginTrace())} —
   * the convenience saves an import + a tiny dance at every call site.
   */
  public static AuditMetadata fromContext(OriginContext context) {
    return new AuditMetadata(context.getOrigin().toActorType(), context.getOriginTrace());
  }

  /** Constant-style factory for callers that know the actor is a plain user (no origin trace). */
  public static AuditMetadata user() {
    return new AuditMetadata(ActorType.USER, null);
  }
}
