# Ticket: refactor — 01 Split merge zones for parallel-friendly file layout

## Why this ticket exists

Wave 2 will spawn 4+ parallel agents per round (household, nutrition, provisions, recipe foundations etc.). Three files are currently shared "merge zones" — every agent edits them, conflicts compound, hand-merging eats the parallelism win. This ticket converts those zones into per-module files referenced from a thin shared root, so each parallel agent only adds new files and CI / Spring / the OpenAPI validator pull everything together at runtime.

The three zones:

1. **`src/main/resources/openapi/openapi.yaml`** — single 703-line file with all paths + schemas. Two parallel agents adding endpoints both edit `paths:` and `components.schemas:`.
2. **`src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`** — single `@RestControllerAdvice` with handlers for every module's exceptions. Two parallel agents adding new exception types both edit this class.
3. **`src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`** — single class with per-module repo-isolation rules. New modules each add another `@ArchTest` field.

## Validated assumptions (no need to re-research)

- **swagger-request-validator** is built atop swagger-parser. swagger-parser resolves external `$ref` when the spec is loaded by URL, but **not** when loaded as inline-string content. The current `OpenApiValidatorConfig` uses `createForInlineApiSpecification(loadSpec())` — that needs to change to a URL-based form for cross-file refs to resolve. Validated by reading the swagger-request-validator README: `OpenApiInteractionValidator.createFor(specUrlOrPayload)` accepts a file URL or inline payload.
- **Spring auto-detects `@RestControllerAdvice` from any package**. Multiple advice classes coexist; each handles its own exception types. Order can be controlled with `@Order` if needed but isn't required here (no overlapping handlers across modules).
- **`@SpringBootTest`-based ITs** auto-pick-up all `@RestControllerAdvice` beans. **`@WebMvcTest`-based slices** only register advice that's explicitly imported; today only `auth/SecurityChainTest` does this (`@Import({AuthSecurityConfig.class, GlobalExceptionHandler.class})`). After the split, that test imports the renamed cross-cutting handler — the auth-specific advice isn't relevant there since `AdminDecisionLogController` doesn't throw auth exceptions.

## Behavioural spec

### Part 1 — OpenAPI YAML split

#### Target layout

```
src/main/resources/openapi/
  openapi.yaml                # entry: info, servers, paths→$ref, components.schemas→$ref, common responses
  paths/
    auth.yaml                 # all /api/v1/auth/* path-items (5 endpoints)
    core.yaml                 # /api/v1/admin/decision-log/* (3 endpoints)
    ai.yaml                   # /api/v1/admin/ai/* (4 endpoints)
    preference.yaml           # /api/v1/preferences/hard-constraints/* (2 endpoints)
  schemas/
    auth.yaml                 # RegisterRequest, LoginRequest, LoginResponse, PasswordChangeRequest, UserDto
    core.yaml                 # DecisionLogDto, AncestryResponse
    ai.yaml                   # TaskType, ModelTier, AiCallLogDto, PromptTemplateDto, CostSummaryUserEntry, CostSummaryDto
    preference.yaml           # DietaryIdentityExceptionDto, DietaryIdentityDto, HardIntoleranceDto, AgeRestrictionDto, HardConstraintsDto, UpdateHardConstraintsRequest, HardConstraintsAuditEntryDto
    common.yaml               # ProblemDetail (used by every module)
```

**Convention for new modules going forward**: add a new `paths/<module>.yaml` and `schemas/<module>.yaml` plus two entries in the entry `openapi.yaml` (one under `paths`, one under `components.schemas`). No edits to other modules' files.

#### Entry-file shape

`src/main/resources/openapi/openapi.yaml` becomes:

```yaml
openapi: 3.0.3
info:
  title: MealPrep AI
  description: |
    Backend API for the MealPrep AI personal meal-planning system.
    See the architectural docs in `design/` and the LLDs in `lld/`.
  version: 0.1.0
  contact:
    name: MealPrep AI
servers:
  - url: http://localhost:8080
    description: Local development
  - url: /
    description: Same-origin (production deployment)
paths:
  # ===== auth =====
  /api/v1/auth/register:
    $ref: 'paths/auth.yaml#/register'
  /api/v1/auth/login:
    $ref: 'paths/auth.yaml#/login'
  /api/v1/auth/logout:
    $ref: 'paths/auth.yaml#/logout'
  /api/v1/auth/me:
    $ref: 'paths/auth.yaml#/me'
  /api/v1/auth/password:
    $ref: 'paths/auth.yaml#/password'
  # ===== core / decision-log =====
  /api/v1/admin/decision-log/{decisionId}:
    $ref: 'paths/core.yaml#/decisionById'
  /api/v1/admin/decision-log/trace/{traceId}:
    $ref: 'paths/core.yaml#/decisionsByTrace'
  /api/v1/admin/decision-log/{decisionId}/ancestry:
    $ref: 'paths/core.yaml#/decisionAncestry'
  # ===== admin / ai =====
  /api/v1/admin/ai/cost-summary:
    $ref: 'paths/ai.yaml#/costSummary'
  /api/v1/admin/ai/call-log:
    $ref: 'paths/ai.yaml#/callLog'
  /api/v1/admin/ai/prompt-templates:
    $ref: 'paths/ai.yaml#/promptTemplates'
  /api/v1/admin/ai/prompt-templates/{name}/{version}:
    $ref: 'paths/ai.yaml#/promptTemplateByNameAndVersion'
  # ===== preference =====
  /api/v1/preferences/hard-constraints:
    $ref: 'paths/preference.yaml#/hardConstraints'
  /api/v1/preferences/hard-constraints/audit-log:
    $ref: 'paths/preference.yaml#/hardConstraintsAuditLog'
components:
  securitySchemes:
    cookieAuth:
      type: apiKey
      in: cookie
      name: AUTH_SESSION
      description: |
        Session cookie issued by /api/v1/auth/login or /api/v1/auth/register.
        Auth-01a: anonymous access is rejected outside the whitelisted paths.
        Auth-roles-followup will tighten ROLE_ADMIN gating on admin endpoints.
  schemas:
    # auth
    RegisterRequest: { $ref: 'schemas/auth.yaml#/RegisterRequest' }
    LoginRequest: { $ref: 'schemas/auth.yaml#/LoginRequest' }
    LoginResponse: { $ref: 'schemas/auth.yaml#/LoginResponse' }
    PasswordChangeRequest: { $ref: 'schemas/auth.yaml#/PasswordChangeRequest' }
    UserDto: { $ref: 'schemas/auth.yaml#/UserDto' }
    # core
    DecisionLogDto: { $ref: 'schemas/core.yaml#/DecisionLogDto' }
    AncestryResponse: { $ref: 'schemas/core.yaml#/AncestryResponse' }
    # preference
    DietaryIdentityExceptionDto: { $ref: 'schemas/preference.yaml#/DietaryIdentityExceptionDto' }
    DietaryIdentityDto: { $ref: 'schemas/preference.yaml#/DietaryIdentityDto' }
    HardIntoleranceDto: { $ref: 'schemas/preference.yaml#/HardIntoleranceDto' }
    AgeRestrictionDto: { $ref: 'schemas/preference.yaml#/AgeRestrictionDto' }
    HardConstraintsDto: { $ref: 'schemas/preference.yaml#/HardConstraintsDto' }
    UpdateHardConstraintsRequest: { $ref: 'schemas/preference.yaml#/UpdateHardConstraintsRequest' }
    HardConstraintsAuditEntryDto: { $ref: 'schemas/preference.yaml#/HardConstraintsAuditEntryDto' }
    # ai
    TaskType: { $ref: 'schemas/ai.yaml#/TaskType' }
    ModelTier: { $ref: 'schemas/ai.yaml#/ModelTier' }
    AiCallLogDto: { $ref: 'schemas/ai.yaml#/AiCallLogDto' }
    PromptTemplateDto: { $ref: 'schemas/ai.yaml#/PromptTemplateDto' }
    CostSummaryUserEntry: { $ref: 'schemas/ai.yaml#/CostSummaryUserEntry' }
    CostSummaryDto: { $ref: 'schemas/ai.yaml#/CostSummaryDto' }
    # common (cross-module)
    ProblemDetail: { $ref: 'schemas/common.yaml#/ProblemDetail' }
  responses:
    # Cross-module re-usable responses kept inline (these are tiny and rarely change).
    BadRequest:
      description: Validation or syntactic error.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    Unauthorized:
      description: Missing or invalid authentication.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    NotFound:
      description: Resource not found.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    Conflict:
      description: Conflict with current state (optimistic-lock, duplicate, etc.).
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    UnprocessableEntity:
      description: Semantic error (constraint feasibility, etc.).
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
    TooManyRequests:
      description: Rate limit exceeded.
      headers:
        Retry-After:
          schema: { type: integer }
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetail' }
```

#### Per-module path-file shape (example: `paths/auth.yaml`)

```yaml
register:
  post:
    tags: [Auth]
    operationId: register
    summary: Create a new user account and auto-log in.
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/auth.yaml#/RegisterRequest' }
    responses:
      '201':
        description: User created; session cookie issued.
        headers:
          Set-Cookie:
            schema: { type: string }
            description: AUTH_SESSION cookie.
        content:
          application/json:
            schema: { $ref: '../schemas/auth.yaml#/UserDto' }
      '400':
        description: Validation or syntactic error.
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
      '409':
        description: Conflict.
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
login:
  post:
    tags: [Auth]
    operationId: login
    # ... full body copied verbatim from current openapi.yaml lines 41-73, with $refs adjusted
logout:
  post:
    tags: [Auth]
    operationId: logout
    # ... lines 75-87
me:
  get:
    tags: [Auth]
    operationId: getCurrentUser
    # ... lines 89-101
password:
  put:
    tags: [Auth]
    operationId: changePassword
    # ... lines 103-126
```

**Important `$ref` rule for per-module path files**: schema references go via the relative path `../schemas/<module>.yaml#/<Name>`. The common-responses (BadRequest etc.) currently re-used via `$ref: '#/components/responses/BadRequest'` won't resolve from inside `paths/auth.yaml` (different document root). Inline the response shape (as shown for `'400'` above) — these are 5 lines each, not worth a shared root reference. Acceptable duplication in service of file-level isolation.

#### Per-module schema-file shape (example: `schemas/auth.yaml`)

```yaml
RegisterRequest:
  type: object
  required: [username, password]
  properties:
    username:
      type: string
      minLength: 3
      maxLength: 32
      pattern: '^[a-zA-Z0-9_-]+$'
    password:
      type: string
      minLength: 12
      maxLength: 128
LoginRequest:
  type: object
  required: [username, password]
  properties:
    username: { type: string }
    password: { type: string }
# ... LoginResponse, PasswordChangeRequest, UserDto copied verbatim from openapi.yaml lines 406-427
```

Schemas that reference each other use **same-file** refs: `$ref: '#/HardIntoleranceDto'` for sibling DTOs in the same module file. Cross-module schema refs (rare; e.g., a `paths/foo.yaml` referencing `schemas/common.yaml#/ProblemDetail`) use the relative path.

#### Validator config change

`src/test/java/com/example/mealprep/testsupport/OpenApiValidatorConfig.java` — replace the `createForInlineApiSpecification` flow with URL-based loading:

```java
@TestConfiguration(proxyBeanMethods = false)
public class OpenApiValidatorConfig {

  private final String specClasspath;

  public OpenApiValidatorConfig(
      @Value("${mealprep.openapi.spec-classpath:openapi/openapi.yaml}") String specClasspath) {
    this.specClasspath = specClasspath;
  }

  @Bean
  public OpenApiInteractionValidator openApiValidator() {
    var url = getClass().getClassLoader().getResource(specClasspath);
    if (url == null) {
      throw new IllegalStateException("OpenAPI spec not found on classpath: " + specClasspath);
    }
    return OpenApiInteractionValidator.createFor(url.toString())
        .withLevelResolver(
            LevelResolver.create()
                .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                .build())
        .build();
  }
}
```

`createFor(url.toString())` accepts a `file:` URL and resolves external `$ref` against that location. The `loadSpec()` private method becomes unnecessary — delete it.

### Part 2 — Per-module exception handlers

#### Target layout

```
src/main/java/com/example/mealprep/
  config/
    GlobalExceptionHandler.java              # cross-cutting only (see below)
    ProblemDetailSupport.java                # NEW — shared constants + helpers
  auth/api/
    AuthExceptionHandler.java                # NEW — auth-specific exceptions
  ai/api/
    AiExceptionHandler.java                  # NEW — ai-specific exceptions
  preference/api/
    PreferenceExceptionHandler.java          # NEW — preference-specific exceptions
```

#### What stays in `GlobalExceptionHandler` (cross-cutting only)

Handlers for: `IllegalArgumentException`, `ConstraintViolationException`, `MethodArgumentNotValidException`, `OptimisticLockingFailureException`, `ResponseStatusException`, `NoResourceFoundException`, generic `Exception` (the 500 catch-all). Plus the nested `FieldError` record (only used by `MethodArgumentNotValidException`).

The global handler **loses** all of: `HardConstraintsNotFoundException`, `UsernameAlreadyExistsException`, `InvalidCredentialsException`, `AccountLockedException`, `LoginThrottledException`, `AiCostBudgetExceededException`, `AiUnavailableException`, `AiInvalidRequestException`, `AiInvalidResponseException`. These move to per-module advice.

The `clock` field, `withClock(Clock)` setter, `retryAfterSeconds(Instant)` and `clampToWholeSeconds(Duration)` helpers move to a shared utility class — see "ProblemDetailSupport" below — because both `AuthExceptionHandler` (for AccountLocked + LoginThrottled) and `AiExceptionHandler` (for AiCostBudgetExceeded) need them.

#### `ProblemDetailSupport` — shared utility

New file `src/main/java/com/example/mealprep/config/ProblemDetailSupport.java`:

```java
package com.example.mealprep.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Shared constants + helpers for {@link ProblemDetail} construction across all module-specific
 * {@code @RestControllerAdvice} classes.
 *
 * <p>Avoids duplicating the {@code PROBLEM_BASE} URI prefix and the {@link Duration}-to-seconds
 * clamping logic in every advice class.
 */
public final class ProblemDetailSupport {

  public static final String PROBLEM_BASE = "https://mealprep.example.com/problems/";

  private ProblemDetailSupport() {}

  /**
   * Build a ProblemDetail with the standard {@code type}/{@code title}/{@code instance} fields
   * populated. Caller adds any {@code setProperty(...)} extensions afterwards.
   */
  public static ProblemDetail build(
      HttpStatus status, String detail, String typeSlug, String title, String requestUri) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create(PROBLEM_BASE + typeSlug));
    pd.setTitle(title);
    pd.setInstance(URI.create(requestUri));
    return pd;
  }

  /**
   * Compute Retry-After in whole seconds from {@code now} until {@code target}, with a floor of 1
   * (we never advise zero). Returns 1 if {@code target} is null or in the past.
   */
  public static long retryAfterSeconds(Clock clock, Instant target) {
    if (target == null) {
      return 1L;
    }
    return clampToWholeSeconds(Duration.between(Instant.now(clock), target));
  }

  /** Clamp a duration to whole seconds, ceiling, with a floor of 1. */
  public static long clampToWholeSeconds(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      return 1L;
    }
    long seconds = duration.toSeconds();
    if (duration.minusSeconds(seconds).toNanos() > 0) {
      seconds += 1;
    }
    return Math.max(seconds, 1L);
  }
}
```

#### `GlobalExceptionHandler` after the split

```java
package com.example.mealprep.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Cross-cutting exception → {@link ProblemDetail} mapper. Module-specific exceptions live in their
 * own {@code <module>/api/<Module>ExceptionHandler.java} classes; only generic / framework-level
 * exceptions are mapped here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
    return ProblemDetailSupport.build(
        HttpStatus.BAD_REQUEST, ex.getMessage(), "validation-error", "Validation failed", req.getRequestURI());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest req) {
    return ProblemDetailSupport.build(
        HttpStatus.BAD_REQUEST, ex.getMessage(), "validation-error", "Validation failed", req.getRequestURI());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.BAD_REQUEST, "Validation failed", "validation-error", "Validation failed", req.getRequestURI());
    List<FieldError> fieldErrors = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(fe -> fieldErrors.add(new FieldError(fe.getField(), fe.getDefaultMessage())));
    pd.setProperty("errors", fieldErrors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
      OptimisticLockingFailureException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.CONFLICT, "Resource was updated concurrently; please retry.",
        "concurrent-update", "Concurrent update", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest req) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getReason());
    pd.setTitle(status.getReasonPhrase());
    pd.setInstance(java.net.URI.create(req.getRequestURI()));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResourceFound(
      NoResourceFoundException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.NOT_FOUND, "No handler for " + req.getRequestURI(),
        "not-found", "Not found", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
    return ProblemDetailSupport.build(
        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.",
        "internal-error", "Internal error", req.getRequestURI());
  }

  public record FieldError(String field, String message) {}
}
```

The `clock` / `withClock` machinery is gone from this class. Tests that previously called `new GlobalExceptionHandler().withClock(fixed)` will need to be checked — the only ones that did are auth tests for `AccountLockedException` / `LoginThrottledException`, which now exercise `AuthExceptionHandler` instead.

#### `AuthExceptionHandler` (new)

`src/main/java/com/example/mealprep/auth/api/AuthExceptionHandler.java`:

```java
package com.example.mealprep.auth.api;

import com.example.mealprep.auth.exception.AccountLockedException;
import com.example.mealprep.auth.exception.InvalidCredentialsException;
import com.example.mealprep.auth.exception.LoginThrottledException;
import com.example.mealprep.auth.exception.UsernameAlreadyExistsException;
import com.example.mealprep.config.ProblemDetailSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Auth-specific exception → {@link ProblemDetail} mapper. */
@RestControllerAdvice
public class AuthExceptionHandler {

  /**
   * Used for {@code Retry-After} computation on 423 responses. Defaults to system UTC; tests that
   * need a deterministic clock substitute via {@link #withClock(Clock)}.
   */
  private Clock clock = Clock.systemUTC();

  public AuthExceptionHandler withClock(Clock clock) {
    this.clock = clock;
    return this;
  }

  @ExceptionHandler(UsernameAlreadyExistsException.class)
  public ResponseEntity<ProblemDetail> handleUsernameAlreadyExists(
      UsernameAlreadyExistsException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.CONFLICT, ex.getMessage(),
        "username-taken", "Username already taken", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ProblemDetail> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.UNAUTHORIZED, "Invalid credentials",
        "invalid-credentials", "Invalid credentials", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<ProblemDetail> handleAccountLocked(
      AccountLockedException ex, HttpServletRequest req) {
    long retryAfterSeconds = ProblemDetailSupport.retryAfterSeconds(clock, ex.lockedUntil());
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.LOCKED, "Account locked",
        "account-locked", "Account locked", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.LOCKED)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(LoginThrottledException.class)
  public ResponseEntity<ProblemDetail> handleLoginThrottled(
      LoginThrottledException ex, HttpServletRequest req) {
    long retryAfterSeconds = ProblemDetailSupport.clampToWholeSeconds(ex.retryAfter());
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.TOO_MANY_REQUESTS, "Login throttled",
        "login-throttled", "Login throttled", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
```

#### `AiExceptionHandler` (new)

`src/main/java/com/example/mealprep/ai/api/AiExceptionHandler.java`:

```java
package com.example.mealprep.ai.api;

import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.config.ProblemDetailSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** AI-module-specific exception → {@link ProblemDetail} mapper. */
@RestControllerAdvice
public class AiExceptionHandler {

  @ExceptionHandler(AiCostBudgetExceededException.class)
  public ResponseEntity<ProblemDetail> handleAiCostBudgetExceeded(
      AiCostBudgetExceededException ex, HttpServletRequest req) {
    long retryAfterSeconds = ProblemDetailSupport.clampToWholeSeconds(ex.retryAfter());
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.TOO_MANY_REQUESTS, "AI cost budget exceeded",
        "ai-budget-exceeded", "AI budget exceeded", req.getRequestURI());
    pd.setProperty("spentPence", ex.spentPence());
    pd.setProperty("limitPence", ex.limitPence());
    pd.setProperty("windowSeconds", ex.window().toSeconds());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AiUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleAiUnavailable(
      AiUnavailableException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.SERVICE_UNAVAILABLE, "AI service unavailable",
        "ai-unavailable", "AI unavailable", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AiInvalidRequestException.class)
  public ResponseEntity<ProblemDetail> handleAiInvalidRequest(
      AiInvalidRequestException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.BAD_REQUEST, "AI request rejected",
        "ai-invalid-request", "AI request invalid", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AiInvalidResponseException.class)
  public ResponseEntity<ProblemDetail> handleAiInvalidResponse(
      AiInvalidResponseException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.BAD_GATEWAY, "AI response invalid",
        "ai-invalid-response", "AI response invalid", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
```

#### `PreferenceExceptionHandler` (new)

`src/main/java/com/example/mealprep/preference/api/PreferenceExceptionHandler.java`:

```java
package com.example.mealprep.preference.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Preference-module-specific exception → {@link ProblemDetail} mapper. */
@RestControllerAdvice
public class PreferenceExceptionHandler {

  @ExceptionHandler(HardConstraintsNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleHardConstraintsNotFound(
      HardConstraintsNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetailSupport.build(
        HttpStatus.NOT_FOUND, ex.getMessage(),
        "hard-constraints-not-found", "Hard constraints not found", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
```

#### Test impact

- `auth/SecurityChainTest.java` line 36 currently has `@Import({AuthSecurityConfig.class, GlobalExceptionHandler.class})`. After the split, the global handler still exists (with the cross-cutting handlers) and the test under `AdminDecisionLogController` doesn't trigger any auth-specific exceptions, so this import line stays as-is. Verify by re-running the test — no expected change in behaviour.
- Any auth ITs that exercise `AccountLockedException` / `LoginThrottledException` mappings (`ThrottlingAndLockoutIT`) are `@SpringBootTest`-style and pick up the new `AuthExceptionHandler` automatically.
- Same for AI tests on cost-budget responses, and preference tests on `HardConstraintsNotFoundException` mapping.

If any test was directly calling `new GlobalExceptionHandler().withClock(fixed)` (rare; usually tests inject the clock via the test context), the call site needs to switch to `new AuthExceptionHandler().withClock(fixed)`. Grep for `withClock(` to find any. The agent must verify this with the test suite.

### Part 3 — ArchUnit per-module split

#### Target layout

```
src/test/java/com/example/mealprep/
  archunit/
    ModuleBoundaryTest.java                   # cross-cutting only: springWebStaysInApi, jpaRepositoriesStayInDomainRepository, entitiesStayInDomain
  core/
    CoreBoundaryTest.java                     # NEW — coreReposAreInternalToCore
  auth/
    AuthBoundaryTest.java                     # NEW — authReposAreInternalToAuth
  preference/
    PreferenceBoundaryTest.java               # NEW — preferenceReposAreInternalToPreference
  ai/
    AiBoundaryTest.java                       # NEW — aiReposAreInternalToAi
```

**Convention for new modules going forward**: each new module ticket adds its own `<module>/<Module>BoundaryTest.java` with one `<module>ReposAreInternalTo<Module>` rule. No edits to `archunit/ModuleBoundaryTest.java`.

#### Per-module boundary-test shape (example: `core/CoreBoundaryTest.java`)

```java
package com.example.mealprep.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Core-module repository isolation. Other modules must not import {@code core.audit.repository},
 * {@code core.lock.repository}, etc. Cross-module callers go through {@code DecisionLogQueryService}
 * / {@code LockService}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class CoreBoundaryTest {

  @ArchTest
  static final ArchRule coreReposAreInternalToCore =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.core..domain.repository..")
          .as(
              "core repos are accessible only within the core module —"
                  + " cross-module callers go through DecisionLogQueryService / LockService.")
          .allowEmptyShould(true);
}
```

The other three module boundary tests (`auth/AuthBoundaryTest.java`, `preference/PreferenceBoundaryTest.java`, `ai/AiBoundaryTest.java`) follow the identical shape — only the package name and the rule body change. Copy the rule bodies verbatim from the current `ModuleBoundaryTest.java` lines 80-117.

#### `ModuleBoundaryTest` after the split

```java
package com.example.mealprep.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Cross-module / cross-cutting architectural rules. Per-module repository-isolation rules live in
 * {@code <module>/<Module>BoundaryTest.java} so that adding a new module doesn't require editing
 * a shared file.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleBoundaryTest {

  @ArchTest
  static final ArchRule springWebStaysInApi =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "..api..", "..config..", "com.example.mealprep.ai.domain.service.internal..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
          .as("Spring Web / Servlet types are an HTTP-layer concern; keep them in `<module>.api`.")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule jpaRepositoriesStayInDomainRepository =
      noClasses()
          .that()
          .resideOutsideOfPackage("..domain.repository..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.data.jpa.repository.JpaRepository")
          .as("Repositories live in `<module>.domain.repository`; nothing else may import them.")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule entitiesStayInDomain =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .and()
          .resideOutsideOfPackage("..api.mapper..")
          .should()
          .dependOnClassesThat()
          .areAnnotatedWith(jakarta.persistence.Entity.class)
          .as("Entities are an internal concern; controllers and DTOs must not depend on them.")
          .allowEmptyShould(true);
}
```

Per-module rules are deleted from this class; they live in the new per-module files.

## Files this ticket touches

```
NEW   src/main/resources/openapi/paths/auth.yaml
NEW   src/main/resources/openapi/paths/core.yaml
NEW   src/main/resources/openapi/paths/ai.yaml
NEW   src/main/resources/openapi/paths/preference.yaml
NEW   src/main/resources/openapi/schemas/auth.yaml
NEW   src/main/resources/openapi/schemas/core.yaml
NEW   src/main/resources/openapi/schemas/ai.yaml
NEW   src/main/resources/openapi/schemas/preference.yaml
NEW   src/main/resources/openapi/schemas/common.yaml
MOD   src/main/resources/openapi/openapi.yaml                                           (slimmed to entry-point with $refs)
MOD   src/test/java/com/example/mealprep/testsupport/OpenApiValidatorConfig.java        (createForInlineApiSpecification → createFor(url))

NEW   src/main/java/com/example/mealprep/config/ProblemDetailSupport.java
NEW   src/main/java/com/example/mealprep/auth/api/AuthExceptionHandler.java
NEW   src/main/java/com/example/mealprep/ai/api/AiExceptionHandler.java
NEW   src/main/java/com/example/mealprep/preference/api/PreferenceExceptionHandler.java
MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java             (cross-cutting only; module-specific handlers removed; Clock + helpers moved)

NEW   src/test/java/com/example/mealprep/core/CoreBoundaryTest.java
NEW   src/test/java/com/example/mealprep/auth/AuthBoundaryTest.java
NEW   src/test/java/com/example/mealprep/preference/PreferenceBoundaryTest.java
NEW   src/test/java/com/example/mealprep/ai/AiBoundaryTest.java
MOD   src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java               (cross-cutting only; per-module rules removed)
```

## Edge cases / gotchas

- **`$ref` path syntax in YAML**: relative paths from a `paths/auth.yaml` file resolve against that file's location. So `$ref: '../schemas/auth.yaml#/RegisterRequest'` walks up one level. From entry `openapi.yaml`, schemas references go via `$ref: 'schemas/auth.yaml#/RegisterRequest'` (no leading `./`).
- **Validator boot**: after the change, `OpenApiInteractionValidator.createFor(url.toString())` may emit different parser warnings. As long as it returns a validator (no exception) and the existing controller-IT contract assertions still pass, that's fine. If parser warnings escalate to errors, set the relevant `LevelResolver` overrides (consult swagger-request-validator README's level-resolver section). Don't loosen the schema-validity gate.
- **Spring picks up multiple `@RestControllerAdvice`** automatically; no `@Import` change needed for `@SpringBootTest`-style tests. For the one `@WebMvcTest` slice (`SecurityChainTest`), no change either since it only exercises the cross-cutting handler.
- **`PROBLEM_BASE` constant**: was a private field on `GlobalExceptionHandler`; now lives in `ProblemDetailSupport.PROBLEM_BASE`. Make it `public static final`.
- **`FieldError` record** stays nested in `GlobalExceptionHandler` — only used by `MethodArgumentNotValidException` mapping.
- **Spotless** will reformat the new files; run `./mvnw spotless:apply` before final verify (per the verify-loop convention).
- **CRLF on Windows**: the new YAML files should be UTF-8, LF line endings (matches the existing `openapi.yaml`).
- **No new dependencies** — swagger-request-validator-mockmvc already supports URL-based loading.
- **Migrations**: none; this is a code-only refactor.
- **OpenAPI version**: stays at 3.0.3 (don't bump to 3.1; `swagger-request-validator` doesn't support 3.1 fully).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on agent's worktree (full unit + IT + ArchUnit gate)
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] OpenAPI entry file is < 200 lines (was 703)
- [ ] `GlobalExceptionHandler.java` is < 130 lines (was 331), no module-specific imports remain
- [ ] All four `<Module>BoundaryTest.java` files exist and contain the expected rule
- [ ] No git status surprises beyond the `Files this ticket touches` list
- [ ] `target/`, `.idea/`, `.vscode/` not committed (gitignore already covers these)

Squash-merge with: `refactor: split openapi spec, exception handlers, archunit rules into per-module files`

## Why these specific splits and not others

We considered three candidate merge zones in `decisions/0001-pacing-and-bottlenecks.md` of the parent ai-workflow repo:

1. `application.properties` — also a merge zone, but the comment-block-per-module convention is already non-conflicting (each module appends its own block; agents merge cleanly via `git rerere` once trained). Not splitting.
2. `pom.xml` — touched rarely; new modules don't add dependencies typically. Not splitting.
3. `AuthSecurityConfig` / `SecurityFilterChain` — only touched by auth-related tickets; not a Wave-2-parallel zone.

The three this ticket addresses are the three that *every* parallel agent would touch in Wave 2 if left as-is.
