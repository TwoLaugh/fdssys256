# Ticket: provisions — 02a `@PastOrPresent LocalDate` timezone-skew tolerance

## Summary

Three provisions write DTOs validate a **client-supplied calendar date** with `@PastOrPresent` on a
bare `LocalDate`. Because a `LocalDate` carries no zone, Hibernate Validator resolves "now" through
its default `ClockProvider` (`Clock.systemDefaultZone()` — the **server's** zone). When a user in a
timezone **ahead of the server** submits a date that is "today" *for them*, that date can be the
server's "tomorrow" → the value looks like a future date → **spurious 400**.

Affected fields (all provisions, all the same pattern):

| DTO | Field | Semantics |
|---|---|---|
| [`LogWasteRequest.java:30`](../../src/main/java/com/example/mealprep/provisions/api/dto/LogWasteRequest.java) | `occurredOn` | when the user binned the item |
| [`UpsertSupplierProductRequest.java:28`](../../src/main/java/com/example/mealprep/provisions/api/dto/UpsertSupplierProductRequest.java) | `lastChecked` | when the shelf price was checked |
| [`GroceryOrderImportCommand.java:22`](../../src/main/java/com/example/mealprep/provisions/api/dto/GroceryOrderImportCommand.java) | `deliveredOn` | grocery delivery date |

**Ships:** a small custom constraint `@PastOrNextDay` (provisions `validation/` sub-package) that
accepts any date `<= today(serverZone) + 1 day`, replacing `@PastOrPresent` on the three fields.
`@NotNull` is unchanged. No DTO shape change, no client-contract change.

**Out of scope:** the "proper" zone-aware fix (client sends its offset / an `OffsetDateTime`) — see
§Alternatives. `Instant`-typed fields (e.g. [`CreateInviteRequest.expiresAt`](../../src/main/java/com/example/mealprep/household/api/dto/CreateInviteRequest.java) `@Future Instant`) are
**not** affected — an Instant is an absolute point in time with no zone ambiguity, so they stay as-is.

**Dependency / ordering:** standalone. No migration, no schema change. Touches only the three DTOs +
two new validation classes + their unit tests.

## Why this is a real production bug (not just a test artifact)

It first surfaced as a local E2E flake: the host test runner (BST, already past local midnight) sent
`occurredOn = LocalDate.now()` while the app container ran UTC (not yet midnight), so the app saw a
future date and returned 400 on the waste / supplier / grocery-import scenarios. CI is unaffected
because there the test runner and the app are **both UTC** and agree. But the same mechanism bites
real clients:

- **Who:** any user whose local calendar date is *ahead* of the server's zone. If the server runs UTC
  (the container default, and typical for prod): essentially all of the UK / Europe / Africa / Asia /
  Oceania. Users **west** of the server (the Americas, when server = UTC) never hit it — their local
  date is `<=` the server date, always past-or-present.
- **When:** a daily window starting at the user's local midnight, lasting roughly their UTC offset —
  ~1 h for the UK in summer (BST = UTC+1), up to ~14 h for the far Pacific (Kiribati = UTC+14).
- **Symptom:** "I tried to log that I threw this out today and it gave me an error." Transient,
  self-clearing once the server's date rolls — therefore miserable to reproduce from a bug report.

**Severity: low–moderate.** Narrow daily window, only on *today*-dated writes, only for eastern users;
but it is a confusing, clock-dependent validation failure on a core happy-path action.

## Behavioural spec

### 1. New constraint `@PastOrNextDay`

Mirror the existing per-module validation convention — annotation + validator in a `validation/`
sub-package (cf. [`auth/validation/ValidPassword.java`](../../src/main/java/com/example/mealprep/auth/validation/ValidPassword.java) + `ValidPasswordValidator.java`,
[`adaptation/validation/ValidPlannerHint.java`](../../src/main/java/com/example/mealprep/adaptation/validation/ValidPlannerHint.java) + `PlannerHintValidator.java`).

`src/main/java/com/example/mealprep/provisions/validation/PastOrNextDay.java`:

```java
/**
 * The annotated {@link java.time.LocalDate} must not be after <em>tomorrow</em> in the server's
 * zone. Replaces a bare {@code @PastOrPresent} on client-supplied calendar dates: a LocalDate has
 * no zone, so {@code @PastOrPresent} (which resolves "now" in the server zone) spuriously rejects a
 * date that is "today" for a user east of the server. No client on Earth is more than one calendar
 * day ahead of any server (max offset +14 h), so a +1-day ceiling fully absorbs the skew while
 * still rejecting genuinely far-future dates. Null is treated as valid ({@code @NotNull} handles
 * presence — same contract as {@code @PastOrPresent}).
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = PastOrNextDayValidator.class)
public @interface PastOrNextDay {
  String message() default "{provisions.validation.PastOrNextDay.message}";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
```

`PastOrNextDayValidator implements ConstraintValidator<PastOrNextDay, LocalDate>`:

```java
private final Clock clock; // Spring injects the shared `systemClock` bean via
                           // SpringConstraintValidatorFactory (Boot default). No-arg fallback to
                           // Clock.systemUTC() so the validator is still safe if instantiated
                           // outside the Spring context (e.g. a raw Validator in a unit test).
public boolean isValid(LocalDate value, ConstraintValidatorContext ctx) {
  if (value == null) return true;                       // @NotNull owns presence
  LocalDate ceiling = LocalDate.now(clock).plusDays(1); // server-zone today + 1
  return !value.isAfter(ceiling);
}
```

- **Clock source:** reuse the existing `Clock systemClock()` bean ([`AuthSecurityConfig.java:53`](../../src/main/java/com/example/mealprep/auth/config/AuthSecurityConfig.java)).
  Spring Boot's `LocalValidatorFactoryBean` uses a `SpringConstraintValidatorFactory`, so a
  constructor-injected `Clock` resolves. Keep the no-arg fallback constructor for non-Spring use.
- **Message:** add `provisions.validation.PastOrNextDay.message` to the validation messages bundle
  (e.g. `ValidationMessages.properties` if present; otherwise inline default `"must not be a future
  date"`). Keep the user-facing wording generic — do **not** mention timezones.

### 2. Apply to the three fields

Replace `@PastOrPresent` with `@PastOrNextDay` (leave `@NotNull` in place) on `occurredOn`,
`lastChecked`, `deliveredOn`. No other change to those DTOs.

### 3. Validation still rejects genuinely bad input

`today + 2` and beyond still fail (e.g. fat-fingering a 2027 date). Only the single-day skew band is
newly accepted. The existing inverted-range / other provisions validations are untouched.

## Alternatives considered

- **Client sends its zone / an `OffsetDateTime`** — the fully-correct fix: validate "is this in the
  past for *this user*?" against the user's own offset. Rejected for v1: it's a request-contract
  change across web + (future) mobile clients and the DTOs, for a low-severity transient bug. Worth
  revisiting if/when the API gains a per-request client-zone header. **Flagged for owner review.**
- **Pin the server JVM zone + document the fields as "server-local"** — does not fix the user-facing
  400, just makes it deterministic. Rejected.
- **Widen to `@FutureOrPresent`-style "no bound"** — would accept arbitrary future dates; loses the
  fat-finger guard. Rejected; the +1-day ceiling keeps the guard.

## Test plan

- **Unit (`PastOrNextDayValidatorTest`):** fixed `Clock` at `2026-05-25`; assert valid for
  `2026-05-24` (past), `2026-05-25` (today), `2026-05-26` (tomorrow / skew band); invalid for
  `2026-05-27` (today+2) and a far-future date; valid for `null`.
- **DTO-level:** a `Validator`-driven test on each of the three DTOs confirming a `today+1` value now
  passes where `@PastOrPresent` would have failed, and `today+2` still fails.
- **E2E:** the three provisions scenarios (`provisions.feature` waste / supplier / grocery-import)
  stop being sensitive to a host/container midnight split — but note the committed local-gate
  mitigation (pin the e2e container to the host zone) already removes the *local* flake; this ticket
  removes the *product* bug. No new E2E needed.

## Definition of done

- `@PastOrNextDay` + `PastOrNextDayValidator` added under `provisions/validation/`.
- The three fields use `@PastOrNextDay`; `@NotNull` retained.
- Unit + DTO-level validator tests green; full IT suite + ArchUnit (validator lives inside the
  provisions module boundary) + Spotless green on CI.
- Pitest: the validator's boundary (`isAfter(today+1)`) is a prime mutation target — add the
  `today+1` / `today+2` boundary cases so the gate is satisfied.
- A one-line note in [`tickets/core/01-decision-log.md`](../core/01-decision-log.md): "client-supplied
  LocalDate fields accept up to server-today+1 to absorb client TZ skew (provisions-02a)."
