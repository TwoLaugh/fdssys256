# Ticket: notification — OpenAPI `NotificationKind` enum is missing two shipped values (P2, codegen-breaking)

## Summary

The Java `NotificationKind` has 10 values; the OpenAPI enum lists 8. Missing:
**`STAPLE_REPLENISHMENT_NEEDED`** and **`FEEDBACK_CONFIRMATION`** — both actively emitted
([`NotificationKind.java` lines 32/39](../../src/main/java/com/example/mealprep/notification/domain/entity/NotificationKind.java),
resolver + debouncer + provisions/feedback listeners all produce them). **Generated frontend types
will fail to parse real rows of those kinds at runtime**, and the preferences `enabledKinds` map
will carry unknown keys. Flagged by
[`design/frontend/pages/notifications.md` §3c + §8 Q1](../../design/frontend/pages/notifications.md):
"Backend gap: add both to the contract enum."

**Fix:** add both values to the `NotificationKind` enum in
`src/main/resources/openapi/schemas/notification.yaml`, and add a **parity test** so the enum can
never drift again: Java `NotificationKind.values()` ⊆ the OpenAPI enum (parse the YAML in a unit
test — the project's contract-validator evidently only exercises kinds the tests emit).

## Edge-case checklist

- [ ] Both kinds present in the schema enum (list + summary + preferences map all reference the same `$ref` — verify single definition)
- [ ] Parity test fails if a future Java enum value is not in the YAML (and vice versa, if strict two-way is wanted — one-way Java→YAML minimum)
- [ ] swagger-request-validator passes on a response containing each new kind (one IT emitting a staple + a feedback-confirmation notification through the REST read)

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/notification.yaml        (two enum values)
NEW   src/test/java/com/example/mealprep/notification/NotificationKindContractParityTest.java
MOD   src/test/java/com/example/mealprep/notification/...         (response-validation IT covering the two kinds, if not already incidental)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Regenerated frontend types parse rows of both kinds; per-kind mute toggles render all 10

Squash-merge with: `fix(notification): add STAPLE_REPLENISHMENT_NEEDED + FEEDBACK_CONFIRMATION to the contract enum (+parity test)`

**Not in scope:** `actionTargetUri` namespace, multi-status filters, preferences-PUT
required-fields doc alignment — P3, see
[`platform-p3-clarifications.md`](platform-p3-clarifications.md).
