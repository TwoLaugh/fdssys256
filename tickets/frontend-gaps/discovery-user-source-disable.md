# Ticket: discovery — user source-disable endpoint + `userDisabled` on the DTO (P2)

## Summary

HLD/LLD promise: *"user can disable any source via Settings."* The plumbing half-exists — the
`discovery_sources` table has a `user_disabled` column
([`V20260615130000__discovery_create_discovery_sources.sql` line 18](../../src/main/resources/db/migration/V20260615130000__discovery_create_discovery_sources.sql)),
the entity maps it, and the admin disable verb deliberately does **not** touch it
([`DiscoveryServiceImpl.java` line 239](../../src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceImpl.java):
"admin-driven disable is distinct from user-driven") — but **no user endpoint exists and
`DiscoverySourceDto` doesn't expose the flag**. The sources panel ships read-only. Flagged by
[`design/frontend/pages/discover.md` §7b + §9 Q4](../../design/frontend/pages/discover.md).

**Fix:**

```
POST /api/v1/discovery/sources/{sourceKey}/user-disable   → 200 DiscoverySourceDto
POST /api/v1/discovery/sources/{sourceKey}/user-enable    → 200 DiscoverySourceDto
```

(idempotent verb pair, matching the admin enable/disable style) + `userDisabled: boolean` on
`DiscoverySourceDto`.

Note the deployment model: `user_disabled` is a column on the global source row — single-user
semantics (consistent with the rest of v1). A per-user join table is **not** in scope; if
multi-user households later need per-user source prefs, that is a v2 migration.

## Behavioural spec

- Effective availability for job-source resolution becomes `enabled && !userDisabled` — the
  default "all enabled sources" set excludes user-disabled rows; naming a user-disabled source in
  `sourceKeys[]` → 422 (same as naming an admin-disabled one — keep the messages distinct:
  "disabled by you" vs "unavailable (admin)").
- Admin `enable` currently *clears* `userDisabled` (per the service javadoc, "so a re-enabled
  source is visible again") — **keep documented behaviour explicit** in the OpenAPI text; the user
  panel re-renders from the returned DTO either way.
- Both verbs idempotent: re-disabling a disabled source → 200 unchanged.
- Unknown `sourceKey` → 404.
- These are **user** endpoints (any authenticated user), not under `/discovery/admin/**` — confirm
  the auth chain treats them as such.

## Edge-case checklist

- [ ] user-disable → excluded from default job-source resolution; explicit `sourceKeys` naming it → 422 with the user-disabled message
- [ ] user-enable restores it; idempotent re-taps 200
- [ ] Admin-disabled + user-enabled → still unavailable (`enabled` wins); DTO shows both flags so the panel can caption correctly
- [ ] Admin enable clears `userDisabled` (documented, asserted)
- [ ] Running jobs unaffected mid-flight (constraints/sources frozen at enqueue — unchanged)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/discovery/api/controller/DiscoverySourcesController.java   (two verbs)
MOD   src/main/java/com/example/mealprep/discovery/domain/service/DiscoveryService.java + internal/DiscoveryServiceImpl.java
MOD   src/main/java/com/example/mealprep/discovery/api/dto/... DiscoverySourceDto + mapper           (userDisabled)
MOD   src/main/java/com/example/mealprep/discovery/domain/service/internal/...                       (job source resolution: enabled && !userDisabled)
MOD   src/main/resources/openapi/paths/discovery.yaml + schemas/discovery.yaml
MOD   src/test/java/com/example/mealprep/discovery/...                                               (resolution + idempotency + admin interplay)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Sources panel's disable toggle wireable; disabled rows distinguish "by you" vs "admin"

Squash-merge with: `feat(discovery): user source-disable/enable verbs + userDisabled on DiscoverySourceDto`

**Not in scope:** per-user (join-table) source preferences — v2; admin verbs (shipped); the
weekly-sweep source selection policy beyond the effective-availability rule.
