# AUDIT xcut-completeness/xcut-1 — Allergy / Tier-1 hard-constraint removal has no confirmation/safety interstitial (GAP-04 ruled BUILD, not implemented)

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | xcut-completeness |
| Dimension | MISSING_CAPABILITY |
| Verification | verified-confirmed |
| **Triage (edit me)** | **DESCOPE?** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/preference/api/controller/HardConstraintsController.java:62-69 (PUT) and src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java:267-326 (updateHardConstraints)

## What's wrong

e2e/pathways/hld-gaps.md 'Final rulings (2026-05-24)' rules GAP-04 = 'Build — removing a Tier-1 hard constraint (e.g. an allergy) requires a confirmation / safety interstitial' (the deterministic HardConstraintFilterService is the system's only allergy guardrail). The shipped PUT /api/v1/preferences/hard-constraints performs a plain replace: updateHardConstraints() simply does aggregate.setAllergies(new ArrayList<>(request.allergies())) and replaceIntolerances(...), writes an audit row, bumps @Version, and returns — no two-step confirm, no removal token, no safety flag. A removal of an allergy is indistinguishable from any other edit. grep for interstitial/confirmation/require across the preference module returns zero matches; the preference-model HLD was never updated to add the interstitial; no ticket exists for it (the C2-C6 'deferred-gap' tickets turned out to be the wave-A leaf tickets — reverters/soft-prefs/directive-apply/mark-depleted — none about GAP-04). The current behaviour (change merely logged) is exactly the pre-ruling state the gap doc said to remediate.

## Recommendation

Implement the ruled GAP-04 interstitial: require an explicit confirmation step (e.g. a confirm flag / two-phase token, or a dedicated removal endpoint) before a PUT may drop an allergy, medical diet, severe intolerance, or dietary-identity base. Gate only Tier-1 removals so ordinary edits stay one-step. Write the rule back into design/preference-model.md to keep HLD and code consistent.

## Scope decision needed

MISSING_CAPABILITY: the design calls for it but it is absent/stubbed. Decide **FIX** for v1, or **DESCOPE** and document the deferral in the LLD/HLD so the v1 test pass does not assert it.
