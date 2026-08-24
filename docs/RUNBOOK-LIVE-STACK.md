# RUNBOOK-LIVE-STACK — e2e-profile live stack (pgvector + real backend + live frontend)

Brings up the stack the live-wired frontend runs against: pgvector Postgres in docker, the
real backend on the host JVM under `SPRING_PROFILES_ACTIVE=e2e` (only the AI is faked,
in-process, by `TestAiService`; no key, no spend), the live frontend on :5176, and a seeded
user with an ACTIVE plan so the Today page renders and persists real data.

Sibling runbook: `RUNBOOK-DEV.md` (repo root) is the `dev`-profile variant with a 54-recipe
catalogue seeded from a local JSON. This one differs in two ways: the catalogue comes from the
deterministic `e2e_curated_seed` discovery source (18 recipes, ingested at first plan
generation), and the `/test-support/**` control plane is live for programming the AI stub and
seeding fixtures.

Citation convention: a `(file[:line])` reference means the step was checked against this
branch's files on 2026-08-24. **UNVERIFIED** marks host state or observed timings that cannot
be confirmed from the repo; check them on the box before relying on them.

## Which compose file, and why neither boots this stack

- Root `docker-compose.yml` does NOT work for booting the app: it runs plain `postgres:16`
  (`docker-compose.yml:3`), and the schema needs `CREATE EXTENSION vector`
  (`src/main/resources/db/migration/V20260601100000__core_install_pgvector.sql`), which that
  image lacks (`e2e/docker-compose.yml:4-6` says the same).
- `docker compose -f e2e/docker-compose.yml up -d --build` is the prod-parity dockerised
  stack, but its own header warns against running it on a memory-constrained box
  (`e2e/docker-compose.yml:15`), and it maps its Postgres to host :5433
  (`e2e/docker-compose.yml:30`), which collides with `busapiprep-postgres-1` when that is up
  (UNVERIFIED, host state, check `docker ps`).

So on this box: DB in docker (below), app on the host JVM.

## 0. Host prep (this box: 7.7 GB RAM, swap-bound)

Same drill as `RUNBOOK-DEV.md` §0:

- Free RAM (PowerShell, not git-bash): `(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1KB`
  — want ≥ ~1 GB before booting the JVM.
- If the CKAD kind cluster is running: `docker stop ckad-control-plane` (reversible:
  `docker start ckad-control-plane`).
- One JVM at a time; do not run the dev stack simultaneously.
- Port check: `docker ps --format "{{.Names}} {{.Ports}}"`. Expected squatters: host :5432 =
  `infra-postgres-1`, :5433 = `busapiprep-postgres-1` (UNVERIFIED, host state; last seen
  2026-07). That is why this stack uses :5434.
- A heap that stays resident beats a bigger one that swaps: `-Xmx768m` completed a full
  width-20 beam here where `-Xmx1g` swapped (observed 2026-06, UNVERIFIED; same guidance in
  `RUNBOOK-DEV.md` §3). If generation logs `qualityWarning=true` (greedy fallback), suspect
  swapping first: free RAM, then restart with the small heap.

## 1. Postgres (pgvector) on :5434

Credentials mirror the e2e compose service (`e2e/docker-compose.yml:22-26`), image
`pgvector/pgvector:pg16`.

```bash
# first time
docker run -d --name mealprep-e2e-db -e POSTGRES_DB=mealprep_e2e -e POSTGRES_USER=mealprep_e2e \
  -e POSTGRES_PASSWORD=mealprep_e2e -p 5434:5432 pgvector/pgvector:pg16
# subsequent times
docker start mealprep-e2e-db
```

To re-seed from scratch: `docker rm -f mealprep-e2e-db` and start over (Flyway recreates the
schema on boot, `application-e2e.properties:21-22`).

## 2. Backend on :8080 under the e2e profile

From the repo root in git-bash (`./mvnw`; there is no system mvn and no `mvnw.cmd`):

```bash
SPRING_PROFILES_ACTIVE=e2e \
MEALPREP_DB_URL=jdbc:postgresql://127.0.0.1:5434/mealprep_e2e \
MEALPREP_DB_USERNAME=mealprep_e2e MEALPREP_DB_PASSWORD=mealprep_e2e \
USDA_API_KEY=DEMO_KEY \
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m -Xms256m"
```

- `127.0.0.1`, not `localhost`: Docker Desktop on this box does not serve IPv6 loopback, so
  `localhost` DB connections flake (host constraint; also `RUNBOOK-DEV.md` Troubleshooting).
- The e2e profile takes the datasource from the `MEALPREP_DB_*` env vars
  (`application-e2e.properties:16-18`). No AI key needed: `TestAiService` is `@Primary` under
  `e2e` (`TestAiService.java:68-70`) and stub keys are baked into the profile
  (`application-e2e.properties:39-40`). `USDA_API_KEY` defaults to `DEMO_KEY` anyway and
  degrades gracefully (`application-e2e.properties:49`).
- Cold-start discovery is pinned to the deterministic `e2e_curated_seed` source
  (`application-e2e.properties:58`), 18 curated recipes (`E2eSeedDiscoverySource.java:56`).
  The catalogue is EMPTY until the first plan generation triggers that ingest.
- Boot ≈ 2-5 min on this box (observed, UNVERIFIED). Poll:
  `curl -s localhost:8080/actuator/health` → `{"status":"UP"}` (`/actuator/health` is
  permit-all, `AuthSecurityConfig.java:143`).

## 3. Seed a user

The REST sequence (register → household → hard-constraints → lifestyle-config → targets
initialise) is identical to `RUNBOOK-DEV.md` §4; run those curl blocks verbatim against this
stack, they are profile-independent. They also define the `$CJ` (cookie jar) and `$HH_ID`
(household id) variables that step 5 below uses. The gotchas are encoded there: household id is the
top-level `id`, every macro needs `enforcement` + `isHardFloor`, `perMeal` uses
`proteinTargetG`, mealSlot enum is BREAKFAST/LUNCH/DINNER/SNACKS.

One delta that matters: the username. Pick credentials the frontend will auto-login with
(step 6). The launch config sets `VITE_DEV_USER=dogfood` / `VITE_DEV_PASS=Dogfood-pass-123`
(`C:\Users\irenv\Claude\.claude\launch.json`, `mealprep-frontend-live` entry); with those env
vars unset the frontend falls back to `iren-demo` / `demo-password-123`
(`frontend/src/live/session.ts:12-13`). Register whichever pair your frontend launch will use.

`POST /api/v1/auth/register` is permit-all (`AuthSecurityConfig.java:132`) and auto-logs-in
(`AuthController.java:71`) by setting the `AUTH_SESSION` cookie (`AuthProperties.java:42`), so
a cookie jar carried across the curl calls is all the auth you need.

## 4. AI stub: optional, not required for a plan

`POST /test-support/ai/canned {taskType, responseJson}` seeds a canned model response;
`DELETE /test-support/ai/canned` clears them (`E2eAiStubController.java:71-87`). The endpoint
is authenticated (deliberately not permitAll, `E2eAiStubController.java:33-48`), so use the
same cookie jar.

Generation completes WITHOUT any canned responses: every AI touchpoint degrades
deterministically on a missing stub. Stage C falls back to the top-scored candidate
(`StageCInvokerImpl.java:106-113`), Phase-2 augmentation is skipped
(`Phase2AugmenterImpl.java:98-104`), and the discovery candidate filter proceeds unfiltered
(`DiscoveryJobRunner.java:585-598`). Seed canned responses only when you want the AI-chosen
path exercised instead of the fallbacks. Shapes that match the deserialisation targets:

| taskType | responseJson | target type |
|---|---|---|
| `PLANNER_STAGE_C` | `{"chosenIndex":0,"reasoning":"..."}` | `StageCPickResponse.java:11` |
| `DISCOVERY_FILTERING` | `{"relevant":true,"confidence":"0.90","reason":"..."}` | `CandidateFilterResult.java:13` |
| `PLANNER_PHASE2_AUGMENTATION` | `{"augmentations":[],"refineDirectives":[]}` | `Phase2AugmentationResponse.java:14` |

Task-type names: `TaskType.java:19,26,27`.

## 5. Generate + accept a plan

```bash
MON=$(python -c "import datetime as d;t=d.date.today();print(t-d.timedelta(days=t.weekday()))")
curl -s -b $CJ -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -X POST http://localhost:8080/api/v1/plans/generate \
  -d "{\"householdId\":\"$HH_ID\",\"weekStartDate\":\"$MON\",\"forceRegenerateIfActive\":true}"
# → 201 + PlanDto, status GENERATED. First run also ingests the 18 e2e_curated recipes
#   (cold-start), so expect minutes on this box (observed ~2 min at best, UNVERIFIED).
curl -s -b $CJ -X POST http://localhost:8080/api/v1/plans/<planId>/accept   # → ACTIVE
```

- Endpoints: `POST /api/v1/plans/generate` (`PlansController.java:101-111`, Idempotency-Key
  header optional), `POST /api/v1/plans/{planId}/accept` (`PlansController.java:176`). Async
  alternative: `POST /api/v1/plans/generate/async` + poll the job
  (`PlansController.java:132-141`).
- A killed generation leaves a stale `core_lock_leases` row that 409s new generations for its
  10-min TTL; wait it out or delete the row (`RUNBOOK-DEV.md` §5).

## 6. Frontend (live) on :5176

`preview_start` the `mealprep-frontend-live` config from
`C:\Users\irenv\Claude\.claude\launch.json` (sets `VITE_LIVE=1` plus the dogfood credentials
and runs `npm run dev -- --port 5176 --strictPort`). Note the config points at the MAIN
checkout's `frontend/`, not a worktree. Manual equivalent from `frontend/`:

```
cmd /c "set VITE_LIVE=1&& npm run dev -- --port 5176"
```

- `VITE_LIVE=1` switches the store to hydrate from the real backend
  (`frontend/src/live/flag.ts:9`).
- The Vite dev server proxies `/api` and `/test-support` to :8080 same-origin, so the session
  cookie flows with no CORS setup (`frontend/vite.config.ts:12-17`).
- On boot the app probes `/api/v1/auth/me` and, if anonymous, logs in with the
  `VITE_DEV_USER`/`VITE_DEV_PASS` credentials (`frontend/src/live/session.ts:16-27`), then
  hydrates. Today renders the real plan; actions like "Start cooking" persist.

## Troubleshooting

- DB connect flakes → you used `localhost`; use `127.0.0.1` (host IPv6 loopback constraint).
- 409 on targets initialise → a macro is missing `enforcement`/`isHardFloor`
  (`RUNBOOK-DEV.md` §4).
- Plan-generate 409 right after a crash → stale lock lease (step 5).
- Frontend 401 loop → the registered user's credentials don't match what the frontend
  launches with (step 3 delta).
- git-bash `/tmp` is not visible to Windows python; pipe JSON via stdin or use `C:/tmp`.
  Bash tool cwd can drift; prefer absolute paths.
