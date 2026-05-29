# AUDIT core/core-2 — lld/core.md is the authoritative design but is stale across ~8 decisions the implementation tickets superseded

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | core |
| Dimension | STALE_DOC |
| Verification | verified-confirmed |
| **Triage (edit me)** | **DOC-ONLY** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

lld/core.md §DTOs (l.180-195), §Entities (l.131-160), §Service Interfaces (l.254-301), §REST Controllers (l.311-335), §Validation (l.341-349)

## What's wrong

The shipped code (faithfully matching tickets/core/01-decision-log.md) contradicts the LLD on numerous load-bearing points: (1) WriteDecisionLogRequest in the LLD has @NotNull UUID decisionId 'caller-supplied for idempotency' plus ~10 Jakarta validation annotations and typed nested records; the shipped DecisionLogWriteRequest (DecisionLogWriteRequest.java) has NO decisionId field, NO validation annotations, and raw JsonNode fields. (2) LLD scope_id is varchar(128) string 'household:<uuid>:week:2026-W18'; shipped DecisionLog.scopeId is a UUID column. (3) LLD types scope_kind as EventScopeKind enum, triggered_by as TriggeredBy enum, chosen.source as ChoiceSource; shipped uses free-form String scopeKind/triggeredBy and opaque JsonNode chosen — none of TriggeredBy/EventScopeKind/ChoiceSource/DecisionScale exist (grep finds nothing). (4) LLD DecisionScale is {WEEK,RECIPE}; shipped DecisionLogScale adds OTHER. (5) LLD lists six admin endpoints incl. actor/recent pagination returning Page<>; controller ships three returning List/AncestryResponse. (6) LLD DataQuality is {GOLD,SILVER,BRONZE,UNVERIFIED} (l.160); shipped is {USER_VERIFIED,IMPORTED,AI_GENERATED,WEB_DISCOVERED}. The LLD reads as the spec for a system that was never built that way.

## Recommendation

Reconcile lld/core.md with shipped reality before the v1 test pass: update the DTO/entity/enum/endpoint/validation sections (or add an explicit 'superseded by tickets/core/01' addendum). A stale authoritative LLD will mislead the test pass into asserting behaviours the code never had.
