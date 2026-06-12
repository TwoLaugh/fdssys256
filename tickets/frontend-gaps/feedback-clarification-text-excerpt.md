# Ticket: feedback — `textExcerpt` on clarification + correction DTOs (N+1 on the inbox) (P2)

## Summary

Neither `ClarificationQueryDto` nor `MisclassificationCorrectionDto` carries any of the original
feedback text. The clarifications inbox needs a "from: …" quote per card and the corrections log
wants the same context — today that is **one `GET /feedback/{feedbackEntryId}` per visible row**
(bounded by page size, but a straight N+1). Flagged by
[`design/frontend/pages/activity.md` §5a + §8 Q5](../../design/frontend/pages/activity.md).

**Fix:** add `textExcerpt` (first 160 chars of the entry's `text`, server-truncated with an
ellipsis flag or plain truncation — pin one) to both DTOs, denormalised at read time via the
existing entry join (both rows already reference `feedbackEntryId`).

### OpenAPI excerpt

```yaml
# schemas/feedback.yaml — ClarificationQueryDto + MisclassificationCorrectionDto
textExcerpt:
  type: string
  maxLength: 160
  description: 'Leading excerpt of the originating feedback text (display context).'
```

## Edge-case checklist

- [ ] Entry text ≤ 160 chars → verbatim; longer → truncated at 160 (no mid-grapheme cut — substring on code points is fine for v1, note it)
- [ ] List reads stay single-query (join/projection, not per-row fetch)
- [ ] Deleted/missing parent entry (shouldn't happen — FK) → defensive empty string rather than 500
- [ ] Full text still available via the existing entry GET (excerpt is context, not a replacement)

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/feedback.yaml                          (field on both DTOs)
MOD   src/main/java/com/example/mealprep/feedback/api/dto/... + mappers          (two DTOs)
MOD   src/main/java/com/example/mealprep/feedback/domain/...                     (projection joins)
MOD   src/test/java/com/example/mealprep/feedback/...                            (truncation + single-query assertions)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Inbox cards + corrections log render context quotes with zero extra calls

Squash-merge with: `feat(feedback): textExcerpt on ClarificationQueryDto + MisclassificationCorrectionDto`

**Not in scope:** typing `destinationResult` / publishing the diff schema — P3, see
[`recipe-adaptation-p3-clarifications.md`](recipe-adaptation-p3-clarifications.md).
