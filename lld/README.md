# LLD — Backend Phase

*Live tracker for the low-level design phase. Keeps a working picture of what's done, what's pending, and what's required to declare "ready to implement."*

The HLDs in [design/](../design/) are the source of truth for *what* the system does. The LLDs in this directory specify *how* each module is built — package layout, JPA entities, migrations, service interfaces, REST endpoints, validation, events, business-logic flows, transaction boundaries, test plan.

## Status at a glance

| Phase | State |
|---|---|
| HLDs (10 docs incl. grocery) | ✅ Complete |
| LLD style guide | ✅ Locked |
| Module LLDs | ✅ 14 of 14 complete (~11,300 lines of LLD content) |
| Tier 1 architectural decisions | ✅ All locked |
| Tier 3 editorial pass | ✅ Complete (2026-05-07) |
| Scoring sub-score formulas | ✅ Locked (2026-05-07) — embeddings-based preference, goal-driven asymmetric nutrition, target-based variety, linear time, locked cost/batch/provisions |
| Embedding pipeline | ✅ Locked (2026-05-07) — OpenAI text-embedding-3-small v1, pgvector, defer-and-pending |
| 9 LLM prompts | ✅ Drafted (3493 lines across [prompts/](prompts/) — 8 Drafted, 1 Drafted + trial-validated as pilot) |
| Hard pockets remaining (pack-size data, weight calibration) | Pending — user-driven track |

## Module LLDs

| LLD | Lines | Notes |
|---|---|---|
| [auth.md](auth.md) | 631 | Spring Security 6, BCrypt 12, NIST password policy, hash-only token storage, 30-day absolute TTL, registration auto-logs-in (locked override), `lastSeenAt` set at creation only |
| [ai.md](ai.md) | 612 | Dispatcher + cost log + prompt-template registry; graceful-degrade via `AiUnavailableException`; £10/month default cap |
| [household.md](household.md) | 501 | 5-event split, single-primary via DB partial unique index; **leaver data: orphan v1 (locked)**; empty households preserved on last-member removal |
| [notification.md](notification.md) | 640 | DeliveryChannel SPI; in-app v1; sealed payload tree; severity tiering; `ItemNearingExpiryEvent` resolved (matches provisions) |
| [nutrition.md](nutrition.md) | 1076 | Per-target `is_hard_floor` flag; defer-and-pending USDA mapping pattern; same-row planned+actual on intake slot |
| [preference.md](preference.md) | 794 | Pilot. Three tiers; `PantryTracking` flag in lifestyle config; `FeedbackProcessedEvent` listener removed (locked) |
| [provisions.md](provisions.md) | 747 | Single inventory table tracking_mode discriminator; soft-delete + 12-month retention sweep + partial indexes; expiry-aware merge on grocery import; cook-event idempotency table |
| [recipe.md](recipe.md) | 823 | Catalogue half. Substitutions as separate table; per-version metadata duplication; promote/demote as flip-in-place |
| [core.md](core.md) | 537 | Decision log + LockService; `SlotKind`/`MealKind`/`DataQuality`/`PreferenceTier` lifted into core.types |
| [grocery.md](grocery.md) | 1088 | Four tiers (Shopping List always; Manual Fulfilment; Grocery Order optional; Price History learned); confidence-weighted aggregates; four anti-staleness mechanisms |
| [discovery.md](discovery.md) | 626 | DiscoverySource SPI with 2-stage `search` + `fetchRecipe`; per-source rate-limiter + 5-strikes circuit breaker; content-fingerprint dedup; polite-by-default robots.txt |
| [feedback.md](feedback.md) | 951 | Async classification (202+poll); `<0.5` confidence pauses entire entry (locked); single-shot correction; 7-day TTL on clarification queries |
| [adaptation-pipeline.md](adaptation-pipeline.md) | 973 | Four trigger flows over a shared worker pipeline; 3-per-week budget enforced rank-at-read; block-and-prompt on AI unavailable |
| [planner.md](planner.md) | 1419 | **Plan storage Option B (normalised) — locked**; 7 sub-score interfaces with placeholder formulas (math TBD by user); Stage C + Phase 2 AiTask types specced (prompts deferred) |

[recipe-extraction-pipeline.md](recipe-extraction-pipeline.md) — 421 lines. Shared URL→ParsedRecipe pipeline used by recipe (user-driven import, Paprika-style preview) and discovery (autonomous). Five-layer extraction stack: JSON-LD → h-recipe → per-site registry → AI HTML extraction → validator. Two input modes: `FromUrl` (server fetches), `FromHtml` (in-app browser supplies). Cache + rate limiting + robots.txt politeness. Curated sources + Google Custom Search v1.

[implementation-playbook.md](implementation-playbook.md) — 576 lines. The contract for backend implementation: per-ticket spec template, 6-layer test strategy (unit / integration / contract / performance / e2e / mutation), CI gates, git workflow, ticket breakdown, agent dispatch model, frontend coordination. Locks tooling (Pitest, k6, swagger-request-validator, REST Assured, Testcontainers, GitHub Actions, Conventional Commits) and the hollow-test countermeasures (mutation testing as hard gate, no-mocking-within-module rule, behavioural-spec-before-impl rule, edge-case checklist per ticket).

[prompts/](prompts/) — 9 LLM prompt designs + conventions doc. ~3700 lines.

[style-guide.md](style-guide.md) — 659 lines.

## Tier 1 architectural decisions locked

| Decision | Resolution |
|---|---|
| `core` module | Yes — shared types, sealed event base interfaces, decision log, LockService |
| JSONB vs relational | JSONB for read-whole stable-shape config; relational for filter/join/FK targets |
| Recipe ownership | User-scoped (household later) |
| AI calls in-transaction | Outside DB transactions; defer-and-pending pattern with status markers |
| Sealed events vs single-with-enum | Sealed across the board |
| Bundle-DTO-for-planner | Convention — `<Module>ForPlannerBundleDto` per data-model module |
| AI cost cap | Graceful-degrade via `AiUnavailableException`; £10/month default; friction-gated raise |
| Pantry tracking disable | Household-level on/off in lifestyle config; per-item disable deferred |
| Inventory soft-delete | Soft-delete with reason + 12-month retention sweep + partial active indexes |
| Floor gate | Per-target `is_hard_floor` flag |
| Grocery price model | Learned from user history (paid/quote/manual/inflation_indexed); confidence-weighted; four anti-staleness mechanisms |
| Plan storage shape | Normalised (Option B) |

## Tier 3 editorial decisions locked (2026-05-07)

- **Provisions** — grocery-import expiry-aware merge (only when expiry dates match); cook-event idempotency table added; soft-delete reasoning kept
- **Household** — orphan model for leaver's per-user data; last-member removal preserves empty household
- **Recipe** — per-version metadata duplication accepted; flip-in-place promote/demote
- **Preference** — `FeedbackProcessedEvent` listener removed
- **Nutrition** — same-row planned+actual on intake slot
- **Auth** — `POST /register` auto-logs-in (overriding agent's separated-flows preference); `lastSeenAt` set at creation only, no per-request write; 30-day absolute TTL; BCrypt 12; SameSite=Lax mitigation in lieu of CSRF tokens for v1
- **Notification** — `ItemNearingExpiryEvent` name resolved (matches provisions publisher)
- **Core** — decision log lifts `scope_kind` / `scope_id` / `actor_user_id` from JSONB to columns
- **Feedback** — `<0.5` confidence pauses entire entry; 7-day clarification TTL; async classification (202+poll)

## Hard pockets — user track (not LLD work)

These don't fit the LLD-doc pattern; they live inside the LLDs that need them, but the design work is real.

| # | Pocket | Lives in | What it is |
|---|---|---|---|
| 1 | ~~Discovery scraping engineering~~ | ✅ Done — [recipe-extraction-pipeline.md](recipe-extraction-pipeline.md) | Five-layer extraction stack, curated + Google CSE, rate limits locked. Per-site extractor registry mechanism in scope; populating it is iterative as user reports surface site-specific bugs. |
| 2 | Pack-size heuristic table | `grocery.md` | Reference data per ingredient category |
| 3 | ~~9 LLM prompts~~ | ✅ Drafted in [prompts/](prompts/) — see prompts/README.md for the index |
| 4 | Weight calibration | `planner.md` | Post-launch tuning of the seven sub-score weights based on real plan acceptance/rejection data |

### LLM prompts to design (9 distinct prompt-engineering exercises)

Each is its own track — eval cases, cost discipline, version pinning. AiTask types are spec'd in the relevant LLDs; prompt content is the work.

| Prompt | Module | Tier |
|---|---|---|
| Taste-profile delta updates | feedback / preference boundary | Mid |
| USDA ingredient mapping | nutrition | Cheap |
| Free-text intake parsing | nutrition | Cheap |
| Feedback classification + routing | feedback | Cheap |
| Recipe adaptation | adaptation-pipeline | Mid |
| Recipe URL extraction (with HTML fallback) | recipe | Mid |
| Recipe discovery filtering | discovery | Mid |
| Planner Stage C — pick of N | planner | Frontier |
| Planner Phase 2 — creative augmentation | planner | Frontier |

## Definition of done — backend ready to implement

A module is implementation-ready when its LLD answers, with no `TBD`:

- [x] Package layout (all 14 LLDs)
- [x] JPA entities (fields, types, nullability, audit, version, indexes)
- [x] Flyway migrations (DDL, named indexes with SQL-comment justifications)
- [x] Service interface methods (signature, return type, behaviour, transaction boundary)
- [x] REST endpoints (method, path, request/response, status codes, validation, OpenAPI tags)
- [x] Events published or consumed (record class, payload fields, listener placement)
- [x] Business-logic flows in prose
- [x] Failure modes per concern
- [x] Test plan (class names + one-line specs)
- [x] Out-of-scope section bounding the doc

A LLM prompt is implementation-ready when it has:

- [ ] System message and user-message template (file-based, versioned)
- [ ] Input context shape (Java type)
- [ ] Output structured format (Java type, with parsing fallback)
- [ ] Eval set (~10-20 representative inputs with expected outputs / acceptance criteria)
- [ ] Cost budget per call
- [ ] Failure mode behaviour (what the calling module does on `AiUnavailable`)

## Pointers

- HLDs: [../design/](../design/)
- Style guide: [style-guide.md](style-guide.md)
- Architectural anchor: [../design/system-overview.md](../design/system-overview.md)
- Cross-cutting wiring: [../design/technical-architecture.md](../design/technical-architecture.md)
- Optimisation loop pattern (used by planner + adaptation pipeline): [../design/optimisation-loop.md](../design/optimisation-loop.md)
