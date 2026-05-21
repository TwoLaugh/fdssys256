# Ticket: recipe — 02a Image Storage (upload + serve + persist URL on Recipe)

## Summary

Add recipe image upload and storage. Per roadmap A2 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md) and the gap-audit entries C-A-053, C-B-026, C-E-022 in [`design/audits/2026-05-21-capability-inventory.md`](../../design/audits/2026-05-21-capability-inventory.md). The HLD is silent on image storage (the audit flagged it as MISSING-FROM-HLD added during user review); this ticket establishes the convention.

Three deliverables in one PR:
1. **Schema**: `recipe_recipes.image_url varchar(512)` column added via Flyway migration.
2. **Upload endpoint**: `POST /api/v1/recipes/{recipeId}/image` accepting multipart/form-data, writes the file to a config-driven local-FS directory, stores the relative path in the column.
3. **Serve endpoint**: `GET /api/v1/recipes/{recipeId}/image` streams the file with the correct content-type (or 404 if no image).

**Storage strategy decision**: **local filesystem in v1**, path stored relative to a config-driven base dir. Justification: no S3/cloud-storage cost or operational burden for v1; deferred-but-clean migration path is "swap `LocalFilesystemImageStore` for `S3ImageStore` behind the same `RecipeImageStore` SPI". Worth user review — if the user wants S3 from day one, the SPI lets the swap happen later without endpoint changes.

**Multipart convention** (new project-wide convention introduced here): multipart endpoints follow the same `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` shape used by Spring; the controller receives a `MultipartFile` parameter directly (not a wrapped DTO) because the single-file simplicity doesn't justify a DTO. Future multipart endpoints (avatar upload, etc.) should follow the same shape; consider extracting `MultipartUploadConventions.md` if a third multipart endpoint lands.

Closes: **C-A-053** (Image storage backend), **C-B-026** (User-image upload for recipe), **C-E-022** (Recipe image storage URL or upload).

This is the second recipe ticket after `recipe-01a` (initial aggregate) — and the first ticket that introduces an image upload pattern.

## Behavioural spec

### Schema change

1. Migration `V20260615140000__recipe_add_image_url.sql` adds `image_url varchar(512)` column to `recipe_recipes`. Nullable (recipes pre-existing this ticket have no image). No index — the column is read-by-id alongside the recipe and never filtered on.
2. Per `lld/recipe.md` line 95-ish (`recipe_recipes` table), the column lands at the end of the table (after `deleted_at`). Migration comment notes "added in tickets/recipe/02a-image-storage.md (Tier A frontend unblock)".
3. **No `OptimisticLockingFailureException` risk** — image upload uses a dedicated transactional write that touches only `image_url` + `updated_at`; the `@Version` field is incremented, so a concurrent recipe edit will throw 409 on whichever loser commits last. That's the desired semantics (the rare case of "user edits ingredient list while the upload is mid-flight" should fail safely).

### `RecipeImageStore` SPI

4. New package-private SPI `RecipeImageStore` at `com.example.mealprep.recipe.spi.RecipeImageStore`:
   ```java
   public interface RecipeImageStore {
     /**
      * Persists the uploaded bytes. Returns a stable storage key (filesystem path
      * relative to the configured base dir, or S3 object key when that lands).
      * Idempotent on identical (recipeId, content-hash) pairs — re-uploading the
      * same bytes returns the same key.
      */
     String store(UUID recipeId, MultipartFile file);

     /** Returns the byte stream + content-type. 404 (Optional.empty()) if missing. */
     Optional<StoredImage> load(String storageKey);

     record StoredImage(Resource resource, MediaType contentType) {}
   }
   ```
5. **`LocalFilesystemImageStore`** at `com.example.mealprep.recipe.spi.internal.LocalFilesystemImageStore` is the v1 implementation. Constructor-injects `RecipeImageStorageProperties` (see below). Storage layout:
   ```
   <base-dir>/recipes/<first-2-chars-of-recipeId>/<recipeId>-<hash>.<ext>
   ```
   - `first-2-chars-of-recipeId` shards directories so no single directory accumulates a million files. Cheap convention; future-proofs.
   - Filename includes the recipe id (for `ls`-based debugging) + a 16-char hex slice of `SHA-256(file bytes)` (collision-resistant for the rare same-recipe-different-image case + the idempotency property in §4).
   - Extension is whitelisted: `.jpg`, `.png`, `.webp` only.
6. **Hash-based dedup**: re-uploading the identical bytes for the same recipe returns the existing path. The endpoint detects the existing file before re-writing.

### Configuration

7. `RecipeImageStorageProperties` (`@ConfigurationProperties(prefix = "mealprep.recipe.image-storage") @Validated`):
   - `Path baseDir` (default `./data/recipe-images`) — must exist OR be creatable at bean init; bean init throws on permission failure.
   - `DataSize maxFileSize` (default `5MB`).
   - `List<String> allowedMimeTypes` (default `["image/jpeg", "image/png", "image/webp"]`).
   - `Duration cacheMaxAge` (default `PT24H`) — used by the GET endpoint's `Cache-Control` header (immutable-by-hash so 24h is fine).
8. Spring's multipart limit must accommodate 5MB; ensure `spring.servlet.multipart.max-file-size=5MB` and `spring.servlet.multipart.max-request-size=5MB` are set in `application.properties`. If they're already set higher, leave them; if lower, set them to 5MB.

### Endpoints

#### `POST /api/v1/recipes/{recipeId}/image`

9. `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`. Parameter: `@PathVariable UUID recipeId`, `@RequestPart("file") MultipartFile file`. Authentication required. Resolved-userId via `CurrentUserResolver` (the controller doesn't accept userId).
10. **Authorization**: the recipe must be owned by the calling user OR be in the SYSTEM catalogue with the caller having the `RECIPE_ADMIN` role. For v1 (no admin role yet — see `tickets/core/02b` for role infrastructure), enforce **owner-only**. If `recipe.catalogue=SYSTEM`, return 403 with `Cannot upload images to system catalogue recipes`. **Worth user review** — the alternative is to allow any authenticated user to upload to SYSTEM recipes; owner-only is the safer default.
11. Response: 200 with `RecipeImageDto { String imageUrl, long sizeBytes, String contentType }`. The `imageUrl` is the absolute URL form `/api/v1/recipes/{recipeId}/image` (the serve endpoint) — NOT the internal storage key. The frontend uses this URL directly in `<img src=...>` tags.
12. Error cases:
    - 400 `MethodArgumentNotValidException` if `file` is empty or absent
    - 413 `MaxUploadSizeExceededException` if file > 5MB (Spring's default exception, mapped to ProblemDetail by `GlobalExceptionHandler`)
    - 415 `UnsupportedMediaTypeException` if MIME type not in the whitelist
    - 404 `RecipeNotFoundException` if `recipeId` doesn't exist or is soft-deleted
    - 403 `RecipeAccessDeniedException` if the caller is not the recipe's owner (per §10)
13. **Side effects on persistence**:
    - Single `@Transactional` write: updates `recipe_recipes.image_url`, `recipe_recipes.updated_at`, bumps `recipe_recipes.optimistic_version` (`@Version` auto-bump).
    - Persists the file to disk **before** the transaction commits (so a rollback would leave an orphan file — acceptable, see §16).
    - Fires `RecipeImageUpdatedEvent(UUID recipeId, String imageUrl, UUID actorUserId, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. No listeners in this ticket; emitted for downstream consumers (e.g. notification module's "recipe updated" event chain).

#### `GET /api/v1/recipes/{recipeId}/image`

14. `@GetMapping(produces = { MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp" })`. Returns the file as a streaming `ResponseEntity<Resource>`. **No authentication required** — recipe images are public assets given anyone-can-read on recipes (per `recipe-01a` §12). **Worth user review** if the user wants images gated behind auth (the URL is unguessable but not secret).
15. Headers:
    - `Content-Type` per the stored MIME type
    - `Cache-Control: public, max-age=86400, immutable` (24h, immutable because content is hash-keyed)
    - `Content-Length` (set by Spring when serving a `Resource`)
16. 404 if `image_url IS NULL` on the recipe row OR the file is missing from the FS (orphan-tolerant — a rollback-orphaned file isn't pointed at by any row, so this case shouldn't surface in practice; the safety check is for restore-from-backup scenarios where the row was restored without the file).

### Validation

17. `@MimeTypeAllowlist` custom validator: a class-level validator on a wrapper record `MultipartImage(MultipartFile file)` is overkill for a single field — the validator runs inside the controller method body as an explicit guard call (`imageMimeValidator.assertAllowed(file)`). Throws `UnsupportedMediaTypeException`. Source MIME from `file.getContentType()` is browser-supplied; we cross-check by running a magic-byte probe via `org.apache.tika.Tika` (already on classpath, used by recipe HTML import per `recipe-01b`) on the first 512 bytes. **The Tika probe is authoritative; the browser-supplied MIME is a hint only.**
18. Filename safety: `MultipartFile.getOriginalFilename()` is **never trusted** for storage. The on-disk filename is `<recipeId>-<hash>.<ext>` where `<ext>` is derived from the **Tika-probed** MIME. The original filename is ignored.

### Cross-cutting

19. New module-local exceptions:
    - `RecipeAccessDeniedException(UUID recipeId, UUID actorUserId) extends RecipeException` — 403.
    - `UnsupportedRecipeImageMimeException(String detectedMime) extends RecipeException` — 415.
    - `RecipeImageStorageException(String message, Throwable cause) extends RecipeException` — 500 (disk full, permission denied; the bean-init permission check catches most of these but a mid-runtime FS failure must be mapped).
20. `RecipeExceptionHandler` (already exists per `recipe-01a`) gains the three new mappings — `RecipeAccessDeniedException → 403`, `UnsupportedRecipeImageMimeException → 415`, `RecipeImageStorageException → 500`.
21. **ArchUnit rule** added to `RecipeBoundaryTest`: `LocalFilesystemImageStore` resides in `recipe.spi.internal..`; the SPI interface `RecipeImageStore` resides in `recipe.spi..` and is `public`. Cross-module callers (e.g. notification when surfacing recipe image previews) inject `RecipeQueryService.getRecipe(recipeId).imageUrl()` — they never inject the store.

### Events

22. **Published**: `RecipeImageUpdatedEvent` (see §13). Carries the new URL so future listeners can fan out (e.g. CDN cache invalidation, planner UI cache flush).
23. **Consumed**: none.

## Database

```
NEW   src/main/resources/db/migration/V20260615140000__recipe_add_image_url.sql
```

Schema:
```sql
ALTER TABLE recipe_recipes
  ADD COLUMN image_url varchar(512);

COMMENT ON COLUMN recipe_recipes.image_url IS
  'Relative storage key (e.g. recipes/ab/<uuid>-<hash>.jpg). Resolved against mealprep.recipe.image-storage.base-dir at serve time. Added in tickets/recipe/02a-image-storage.md.';
```

No index. The column is read alongside the recipe row (already covered by the recipes primary-key index) and never filtered on.

## OpenAPI updates

Add 2 paths and 1 schema to `src/main/resources/openapi/paths/recipe.yaml`:

- **`POST /api/v1/recipes/{recipeId}/image`** — `requestBody: multipart/form-data` with a `file` part. Response 200: `RecipeImageDto`. 400, 403, 404, 413, 415 references to standard ProblemDetail responses. Security: `cookieAuth`.
- **`GET /api/v1/recipes/{recipeId}/image`** — Response 200 with `content` enumerating `image/jpeg`, `image/png`, `image/webp` each with `schema: { type: string, format: binary }`. Response 404. **No security** (anonymous-readable).
- **Schema `RecipeImageDto`** in `src/main/resources/openapi/schemas/recipe.yaml`:
  ```yaml
  RecipeImageDto:
    type: object
    required: [imageUrl, sizeBytes, contentType]
    properties:
      imageUrl: { type: string, format: uri, example: "/api/v1/recipes/{recipeId}/image" }
      sizeBytes: { type: integer, format: int64, minimum: 0 }
      contentType: { type: string, enum: [image/jpeg, image/png, image/webp] }
  ```
- **Existing `RecipeDto` schema gains an `imageUrl` field** (`type: string, format: uri, nullable: true`) — recipes returned from `GET /api/v1/recipes/{id}` and list endpoints now include the URL when set.

## Edge-case checklist

- [ ] Migration applies cleanly; `recipe_recipes.image_url` is nullable varchar(512) — verified via `information_schema.columns` in `FlywayMigrationIT`.
- [ ] Upload a 4MB JPEG → 200, file lands at `<base-dir>/recipes/<2-char-shard>/<uuid>-<hash>.jpg`, DB column populated.
- [ ] Upload a 6MB JPEG → 413 (`MaxUploadSizeExceededException`).
- [ ] Upload a `.exe` renamed to `.jpg` (MIME `image/jpeg` but magic bytes say PE32) → 415 (Tika probe wins).
- [ ] Upload an actual PNG → 200, extension `.png` on disk, `Content-Type: image/png` on GET.
- [ ] Upload an actual WebP → 200, extension `.webp`.
- [ ] Upload zero-byte file → 400 (MultipartFile.isEmpty() check).
- [ ] Upload to non-existent recipe id → 404.
- [ ] Upload to a soft-deleted recipe (`deleted_at IS NOT NULL`) → 404.
- [ ] Upload to a recipe owned by a different user → 403.
- [ ] Upload to a SYSTEM catalogue recipe by a non-admin → 403.
- [ ] Re-uploading identical bytes for the same recipe → returns the same `imageUrl`, no extra file on disk (idempotency via hash-based filename).
- [ ] Uploading different bytes for the same recipe → new file written, DB column updated to new path, old file remains on disk (no orphan cleanup in v1 — acknowledged technical debt; revisit if disk grows).
- [ ] GET on a recipe with no image → 404 `RecipeImageNotFoundException` (a new subclass of `RecipeException`, 404; alternatively reuse `RecipeNotFoundException` with a specific message — pick the subclass for clarity).
- [ ] GET on an existing recipe whose file is missing from disk → 404 (orphan-tolerant per §16).
- [ ] GET response includes `Cache-Control: public, max-age=86400, immutable`.
- [ ] GET response includes the correct `Content-Type` matching the stored MIME.
- [ ] GET works for anonymous requests (no auth cookie).
- [ ] Concurrent upload + recipe edit: the loser gets 409 `OptimisticLockingFailureException`; image still written if uploader wins, ignored if uploader loses.
- [ ] Disk permission failure mid-upload → 500 `RecipeImageStorageException` with no DB row update (transaction rolls back on the exception; file on disk may exist as an orphan).
- [ ] `RecipeImageStorageProperties` binds: setting `mealprep.recipe.image-storage.base-dir=/tmp/test-images` in a test profile redirects writes to that location.
- [ ] `mealprep.recipe.image-storage.max-file-size=2MB` rejects a 3MB upload with 413.
- [ ] `RecipeImageUpdatedEvent` fires exactly once per successful upload, `AFTER_COMMIT` — verified via test listener.
- [ ] `RecipeDto.imageUrl` is `null` for a recipe with no image; non-null after upload (verified via `RecipeServiceImplTest`).
- [ ] OpenAPI contract test: the `POST /image` and `GET /image` responses match the spec.
- [ ] ArchUnit: no class outside `recipe.spi..` imports `RecipeImageStore`. No class outside `recipe.spi.internal..` imports `LocalFilesystemImageStore`.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615140000__recipe_add_image_url.sql

NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeImageController.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeImageDto.java
NEW   src/main/java/com/example/mealprep/recipe/spi/RecipeImageStore.java
NEW   src/main/java/com/example/mealprep/recipe/spi/internal/LocalFilesystemImageStore.java
NEW   src/main/java/com/example/mealprep/recipe/config/RecipeImageStorageProperties.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeImageUpdatedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeAccessDeniedException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeImageNotFoundException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/UnsupportedRecipeImageMimeException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeImageStorageException.java

MOD   src/main/java/com/example/mealprep/recipe/domain/entity/Recipe.java             (add imageUrl column)
MOD   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeMapper.java          (map imageUrl)
MOD   src/main/java/com/example/mealprep/recipe/api/dto/RecipeDto.java                (add imageUrl field)
MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java       (3 new mappings)
MOD   src/main/resources/application.properties                                       (multipart size limits)
MOD   src/main/resources/openapi/paths/recipe.yaml                                    (2 new paths)
MOD   src/main/resources/openapi/schemas/recipe.yaml                                  (RecipeImageDto + RecipeDto.imageUrl)

NEW   src/test/java/com/example/mealprep/recipe/RecipeImageControllerIT.java          (Testcontainers + multipart upload)
NEW   src/test/java/com/example/mealprep/recipe/LocalFilesystemImageStoreTest.java    (unit — store/load/hash-dedup)
NEW   src/test/java/com/example/mealprep/recipe/testdata/RecipeImageTestFixtures.java (a tiny JPEG, PNG, WebP, a non-image)
MOD   src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java             (SPI placement rule)
```

Test fixture binaries: include a 1KB JPEG, a 1KB PNG, a 1KB WebP, and a 1KB binary blob masquerading as JPEG. Place under `src/test/resources/recipe-images/`.

Total: 10 new Java files + 1 migration + 7 mods + test fixtures. Estimated agent runtime 90-120 min.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe` entity, `recipe_recipes` table, `RecipeQueryService`, `RecipeExceptionHandler`.
- **Hard dependency**: `auth-01a` (merged) — session-cookie auth for the POST endpoint.
- **Soft dependency**: Tika is already on the classpath per `recipe-01b` (HTML import); if not, add `tika-core` to `pom.xml` and document.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract test)
- [ ] All edge-case items above ticked
- [ ] `RecipeImageControllerIT` covers happy + 4xx + 5xx paths against a Testcontainers Postgres
- [ ] Manual smoke documented in the PR: `curl -F file=@test.jpg -b session.cookie http://localhost:8080/api/v1/recipes/<id>/image` returns 200; subsequent GET returns the image
- [ ] OpenAPI: the new paths lint clean against the existing spec
- [ ] No raw filename from the multipart upload appears anywhere in storage or DB

## What's NOT in scope

- **S3 / cloud-storage backend** — defer to a follow-up ticket if usage justifies it; the SPI is structured to make the swap a single-class addition.
- **Image variants / thumbnails** — frontend can use CSS-side resizing for v1.
- **CDN integration** — same.
- **Orphan file cleanup** — track as known technical debt; not a v1 blocker since disk usage is small.
- **AI alt-text generation** — out of HLD scope.
- **Image moderation / NSFW detection** — UK consumer app for the user themselves; not a v1 concern.

Squash-merge with: `feat(recipe): 02a — image upload + serve + storage SPI (Tier A frontend unblock)`
