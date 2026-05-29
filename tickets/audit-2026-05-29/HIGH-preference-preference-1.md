# AUDIT preference/preference-1 — Profile Metadata tier entirely unimplemented (no table, entity, endpoints, or filter integration)

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | preference |
| Dimension | MISSING_CAPABILITY |
| Verification | verified-confirmed |
| **Triage (edit me)** | **DESCOPE?** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/preference/ (no ProfileMetadata anywhere); lld/preference.md:223-239, 542-543, 580, 619-620, 707

## What's wrong

The LLD specifies the full Profile Metadata tier: migration V20260501120300 creating preference_profile_metadata, a ProfileMetadata entity, ProfileMetadataController with GET/PUT /api/v1/preferences/profile-metadata, ProfileMetadataDto, the query methods getProfileMetadata/getProfileMetadataByUserIds, and PreferenceUpdateService.upsertProfileMetadata. A repo-wide grep for 'ProfileMetadata' returns NO files. There is no preference_profile_metadata migration (migration list confirms V20260601300000/300100/150000/150100/150200/160000/160100/170000 only). The HLD (design/preference-model.md:314-331) makes Profile Metadata first-class: age_group drives age-restriction auto-population and update thresholds; portion_scale, preference_volatility, update_confirmation_threshold feed planner/update logic. Flow 2 step 2 of the LLD says the filter loads ProfileMetadata 'for age-restriction context' — HardConstraintFilterServiceImpl never loads it. The soft-bundle that the LLD says carries profileMetadata (SoftPreferenceBundleDto) was redefined in the household package with only tasteProfile + lifestyleConfig fields.

## Recommendation

Either implement the Profile Metadata tier (table + entity + DTO + upsert + read endpoints, and wire age_group into the filter's age-restriction context) for v1, or, if it was deliberately descoped, update lld/preference.md to mark it deferred and record the decision. Confirm with the user whether age-restriction auto-population and portion_scale are needed for v1 planner correctness.

## Scope decision needed

MISSING_CAPABILITY: the design calls for it but it is absent/stubbed. Decide **FIX** for v1, or **DESCOPE** and document the deferral in the LLD/HLD so the v1 test pass does not assert it.
