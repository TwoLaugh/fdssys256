# Ticket: household — members render as UUIDs (username on `HouseholdMemberDto`) (P2)

## Summary

`HouseholdMemberDto` has a nullable `displayName` and no `username`; there is no user-lookup
endpoint (auth exposes only `/me`). A fresh member who never set a `displayName` renders as a
**UUID stub** in the Settings members list, invite "issued for" targeting requires pasting a UUID,
and the same family hits the settings audit drawer (`actorUserId`) and every admin-page userId
column. Flagged by [`design/frontend/pages/settings.md` §8 Q2/Q6](../../design/frontend/pages/settings.md)
and [`admin.md` §7 Q3](../../design/frontend/pages/admin.md).

**Fix (both halves):**

1. Add read-only `username` to `HouseholdMemberDto` — household joins it from auth via an
   in-process read seam (auth needs a public `username(s)-by-id(s)` query service if none exists;
   batch variant to avoid N+1 on the members list).
2. **Auto-populate `displayName` from the accepter's username on invite accept** when the invitee
   has no display name — new members are never UUID stubs even before half 1 wires the UI.

### OpenAPI excerpt

```yaml
# schemas/household.yaml — HouseholdMemberDto
username:
  type: string
  description: 'Login username (read-only, joined from auth).'
```

## Edge-case checklist

- [ ] Members list: every row carries `username`; one batched auth read (no N+1)
- [ ] Invite accept with no displayName → displayName = username (audit row records the set)
- [ ] Invite accept with an explicit displayName flow (if any) → not overwritten
- [ ] Existing members with null displayName → `username` gives the UI a render fallback (no backfill migration needed)
- [ ] Module boundary: household → auth via public API only (boundary tests pass)
- [ ] Settings audit rows: actor still a UUID on the wire — UI can now resolve via the members list (note: a full audit-name join is out of scope)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/household/api/dto/... HouseholdMemberDto + mapper
MOD/NEW src/main/java/com/example/mealprep/auth/domain/service/... (public usernames-by-ids query seam, if absent)
MOD   src/main/java/com/example/mealprep/household/domain/service/internal/...  (invite-accept displayName default)
MOD   src/main/resources/openapi/schemas/household.yaml
MOD   src/test/java/com/example/mealprep/household/...   (+ boundary test expectations if the auth seam is new)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Settings members list renders names for every member, including a freshly-accepted invitee

Squash-merge with: `feat(household): username on HouseholdMemberDto + displayName default on invite accept`

**Not in scope:** a general user-lookup REST endpoint; admin-page username joins (admin reads stay
raw-UUID in v1 — same family, flagged); the v1.5 profile surface (task #173).
