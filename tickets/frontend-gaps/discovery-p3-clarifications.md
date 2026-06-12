# Ticket: discovery — P3 semantic clarifications (combined)

Low-priority items from [`design/frontend/pages/discover.md` §9](../../design/frontend/pages/discover.md).
Resolve item-by-item; tick + annotate.

## Items

1. **No live progress signal** (discover §9 Q1). Status jumps QUEUED → RUNNING → terminal; the
   UI derives liveness from scrape-log row arrival while polling (~2 s). **Proposed:** accept for
   v1 — job-progress push rides the SSE channel (backlog task #172); no bespoke endpoint.
2. **Skip is semantically empty** (discover §9 Q5). A skipped result stays a live system-catalogue
   recipe the planner can schedule; the spec's default is local dismissal, with archive-on-skip
   ("keep it out of my plans") as the alternative. **Proposed:** product ruling; default
   local-dismiss (archive is one tap away via the recipe card if wanted). Pin the ruling in the
   page spec + HLD.
3. **Per-job "kept" count not derivable** (discover §9 Q6). Promotion isn't linked back to the
   discovery job; history rows drop the stat, the open card derives via recipe joins.
   **Proposed:** accept for v1; if wanted later, stamp `promotedAt`-style provenance or count via
   the scrape-log `recipeId` → catalogue join server-side.

## Acceptance / DoD

- [ ] Each item: decision recorded inline; ruling for item 2 reflected in `discover.md` + HLD

Squash-merge with: `docs(discovery): P3 clarifications from discover page spec`
