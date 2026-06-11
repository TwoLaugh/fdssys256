# Frontend information architecture (v1)

Grounded in the HLDs (`design/*.md`), the frontend-readiness roadmap
(`design/audits/2026-05-21-frontend-readiness-roadmap.md`), and the live API surface
(189 endpoints, `src/main/resources/openapi/openapi.yaml`). Companion doc:
[design-language.md](design-language.md). Interactive mockups: [mockups/](mockups/).

## Shape

**15 routes** — 12 everyday surfaces + login + onboarding + admin. Two **global
elements** live on every page, outside any route:

- **Feedback button** — the HLD mandates feedback-from-anywhere; opens the feedback
  modal (free text → routing confirmation, see mockup `#d6-feedback`).
- **Notification bell** — badge from `GET /notifications/summary`; opens a digest
  dropdown; full page at `/notifications`.

Navigation: left icon rail (desktop) / bottom tabs (mobile).

## Pages

| # | Route | Page | Contents | Primary endpoints |
|---|-------|------|----------|-------------------|
| 1 | `/login` | Login / register | Single page, two modes; session cookie auth | `auth/login`, `auth/register` |
| 2 | `/onboarding` | Onboarding wizard | 5 steps: household → invite members → allergies & dietary identity → lifestyle/slot config → nutrition targets (auto-seed) | `households`, `invites`, `preferences/hard-constraints`, `preferences/lifestyle-config`, `nutrition/targets/initialise` |
| 3 | `/` | Today (home) | Today's slots w/ states + next-action, nutrition vs targets, needs-attention digest, budget snapshot, suggestion teaser | `plans/active`, `nutrition/intake/{date}`, `notifications/summary`, `provisions/budget`, `adaptation/pending-changes` |
| 4 | `/plan` | Plan (week) | Week grid (days × slots), generate flow (feasibility → candidates → accept/reject), quality warnings, re-opt suggestions w/ diff preview, revert history, batch links, serve times | `plans/*` |
| 5 | `/recipes` | Recipe library | Browse/search/filter, user vs system catalogue, quality-tier badges, URL import (preview → confirm), archive | `recipes`, `recipes/imports/*` |
| 6 | `/recipes/:id` | Recipe detail | Photo hero, ingredients/steps, 4-axis ratings, versions + diff, substitutions, provenance, pending changes for this recipe | `recipes/{id}/*` |
| 7 | `/discover` | Discover | Start search job, live job status (poll), per-source scrape transparency, results → import | `discovery/*` |
| 8 | `/groceries` | Groceries | Shopping list w/ cost ± confidence + stale-price flags, mark-bought, orders lifecycle (quote → place → confirm → delivered), substitution resolution, price history | `grocery/*` |
| 9 | `/pantry` | Pantry | Inventory w/ expiry colour-coding, quantity adjust, spoiled/exhausted (→ triggers re-opt), waste log, equipment, weekly budget | `provisions/*` |
| 10 | `/nutrition` | Nutrition | Day view (confirm/override/skip slots, snacks), daily/weekly aggregates vs targets, food/mood journal, targets editor, ingredient needs-review | `nutrition/*` |
| 11 | `/preferences` | Taste & preferences | Taste-profile viewer w/ refresh/rollback/versions, hard constraints w/ GAP-04 confirm interstitial, lifestyle config, archive | `preferences/*` |
| 12 | `/activity` | Activity | Pending changes (top-3, before/after diff, accept/reject), feedback history w/ routing log + correction, clarifications inbox | `adaptation/*`, `feedback/*` |
| 13 | `/notifications` | Notifications | Full list by kind, read/dismiss/actioned, preferences (category mute, quiet hours) | `notifications/*` |
| 14 | `/settings` | Household & settings | Members + roles + invites, slot configuration, account/password | `households/*`, `auth/password` |
| 15 | `/admin` | Admin (allowlisted) | System status, AI cost summary, call log, decision-log explorer | `admin/*` |

## UX rules carried from the HLDs

These are design-level commitments, not suggestions:

- **Diff preview before any re-optimisation** — never silently change a plan
  (`design/meal-planner.md`).
- **Pinned slots are immutable** — eaten/cooking/cooked meals never regenerate.
- **Confidence-tiered feedback routing** — ≥0.8 route silently (with escape hatch),
  0.5–0.8 "I think you meant — correct me", <0.5 ask a clarification question
  (`design/feedback-system.md`).
- **Allergy-removal interstitial (GAP-04)** — backend returns 409; the UI must render
  an explicit confirmation, never a one-step edit.
- **Cost is always shown with confidence** — "£52 ± £4 · 83% confidence"; stale-price
  counts visible on the shopping list.
- **Data-quality tiers visible** — user_verified / imported / ai_generated /
  web_discovered badges on recipes.
- **Async jobs are pollable, never blind** — every long operation (plan generation,
  discovery, feedback routing, orders) has a status endpoint; the UI shows progress
  states. SSE push is a v1.5 backlog item; v1 polls.

## Sequencing

Per the readiness roadmap, Tier A (CORS, images, pagination, OpenAPI types) is done —
the frontend builds against `openapi.yaml` with codegen types from day one. Suggested
build order: shell + auth + Today → Plan (incl. generation flow) → Recipes →
Groceries/Pantry → Nutrition/Preferences → Activity/Notifications → Settings → Admin.
