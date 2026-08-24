# RUNBOOK-DEV — dogfood stack (dev profile, real persistence, no AI key)

Verified end-to-end 2026-07-20 on branch `experiment/dataset-recipe-pool`. Brings up the real
backend on the `dev` profile with a seeded ~54-recipe SYSTEM catalogue, a dogfood user, and plan
generation that works with NO OpenAI key (Stage C degrades to the deterministic top-composite
pick — logged as `Stage C: AI unavailable ... falling back to deterministic`; that is expected,
not an error).

## 0. Host prep (this box: 7.7 GB RAM, swap-bound)

- Free RAM check (PowerShell): `(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1KB`
  — want ≥ ~1 GB free before booting the JVM.
- If the CKAD kind cluster is running: `docker stop ckad-control-plane` (reversible:
  `docker start ckad-control-plane`).
- One JVM at a time — do not run the e2e stack simultaneously.

## 1. Postgres (pgvector) on :5434

:5432 is taken by `infra-postgres-1`, :5433 by `busapiprep-postgres-1`.

```bash
# first time
docker run -d --name mealprep-dev-db -e POSTGRES_DB=mealprep_dev -e POSTGRES_USER=mealprep_dev \
  -e POSTGRES_PASSWORD=mealprep_dev -p 5434:5432 pgvector/pgvector:pg16
# subsequent times
docker start mealprep-dev-db
```

## 2. Curated recipe pool JSON (one-time, already done)

`C:\Users\irenv\Claude\mp-data\dev_seed_pool.json` — 54 recipes curated from the 13k datahive
pool (per-recipe mealTypes + real per-serving macros/micros). Regenerate/re-tune with
`mp-data`'s datahive JSON + the curation script (session scratchpad `curate_dev_pool.py`;
selection: 12 breakfast, 12 high-protein mains, 22 balanced mains, 8 snacks, keyword-diverse).
Datahive is CC BY-NC: fine for personal local dogfood, must NOT ship — which is why this file
lives outside the repo and is read from disk, never from the classpath.

## 3. Backend on :8080 (dev profile)

From the repo root, in git-bash (`./mvnw`; there is no system mvn and no `mvnw.cmd`):

```bash
SPRING_PROFILES_ACTIVE=dev \
MEALPREP_DB_URL=jdbc:postgresql://localhost:5434/mealprep_dev \
MEALPREP_DB_USERNAME=mealprep_dev MEALPREP_DB_PASSWORD=mealprep_dev \
MEALPREP_DEV_RECIPE_POOL=C:/Users/irenv/Claude/mp-data/dev_seed_pool.json \
USDA_API_KEY=DEMO_KEY \
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m -Xms256m"
```

- **Do NOT set `OPENAI_API_KEY`.** Unset ⇒ the OpenAI client bean is absent ⇒ every AI touchpoint
  degrades gracefully (Stage C picks the deterministic top-composite candidate; Phase-2
  augmentation is skipped). Setting a real key would hit the placeholder model ids
  (`gpt-5.4-mini`/`gpt-5.5` in `application.properties`) — harmless now (Stage C/Phase 2 catch
  all `AiException`s and fall back) but pointless until real model ids are configured.
- `-Xmx768m` on purpose: a heap that stays resident beats a bigger one that swaps.
- Boot ≈ 2–5 min. Poll: `curl -s localhost:8080/actuator/health` → `{"status":"UP"}`.
- First boot on an empty DB logs the seeder:
  `dev recipe-pool seeder: created 54 of 54 recipe(s) ... mealType distribution {breakfast=13, lunch=35, dinner=35, snack=8}`
  Restarts skip it (`SYSTEM catalogue already has N recipe(s)`). To re-seed from scratch:
  `docker rm -f mealprep-dev-db` and start over (Flyway recreates the schema).
- Cold-start discovery is disabled in dev (`mealprep.planner.cold-start.enabled=false` in
  `application-dev.properties`) — the seeded pool IS the catalogue.

## 4. Seed the dogfood user (one-time per DB)

All requests share a cookie jar (`register` auto-logs-in via the `AUTH_SESSION` cookie).
Gotchas encoded below: household id = TOP-LEVEL `id` (not `members[].id`); every macro needs
`enforcement` + `isHardFloor`; `perMeal` uses `proteinTargetG`; mealSlot enum is
BREAKFAST/LUNCH/DINNER/SNACKS; the two preference PUTs upsert on first write with
`expectedVersion: 0`.

```bash
CJ=/c/tmp/mp-dev-cookies.txt; rm -f $CJ
B=http://localhost:8080

curl -s -c $CJ -H 'Content-Type: application/json' -X POST $B/api/v1/auth/register \
  -d '{"username":"dogfood","password":"Dogfood-pass-123"}'

HH_ID=$(curl -s -b $CJ -H 'Content-Type: application/json' -X POST $B/api/v1/households \
  -d '{"name":"Home"}' | python -c "import sys,json;print(json.load(sys.stdin)['id'])")

curl -s -b $CJ -H 'Content-Type: application/json' -X PUT $B/api/v1/preferences/hard-constraints \
  -d '{"allergies":[],"dietaryIdentity":{"base":"omnivore","labelForDisplay":"Omnivore","exceptions":[]},"medicalDiets":[],"intolerances":[],"ageRestrictions":[],"expectedVersion":0}'

curl -s -b $CJ -H 'Content-Type: application/json' -X PUT $B/api/v1/preferences/lifestyle-config \
  -d '{"document":{"mealStructure":{"weekday":{"meals":["breakfast","lunch","dinner"],"snacks":{"planned":false,"style":null,"notes":null}},"weekend":{"meals":["breakfast","lunch","dinner"],"snacks":{"planned":false,"style":null,"notes":null}},"recurringSkips":[]}},"expectedVersion":0}'

curl -s -b $CJ -H 'Content-Type: application/json' -X POST $B/api/v1/nutrition/targets/initialise -d '{
  "goal":"MAINTAIN",
  "calories":{"dailyTarget":2300,"toleranceUnder":200,"toleranceOver":200,"enforcement":"weekly_average","direction":"BOTH_BOUNDED"},
  "protein":{"targetG":140,"floorG":120,"enforcement":"SOFT","direction":"LOWER_FLOOR","isHardFloor":false},
  "carbs":{"targetG":240,"floorG":null,"enforcement":"SOFT","direction":"BOTH_BOUNDED","isHardFloor":false},
  "fat":{"targetG":80,"floorG":null,"enforcement":"SOFT","direction":"BOTH_BOUNDED","isHardFloor":false},
  "fibre":{"targetG":30,"floorG":null,"enforcement":"SOFT","direction":"LOWER_FLOOR","isHardFloor":false},
  "satFat":{"targetG":25,"floorG":null,"enforcement":"SOFT","direction":"UPPER_LIMIT","isHardFloor":false},
  "notes":"dogfood seed",
  "perMealDistribution":[
    {"mealSlot":"BREAKFAST","calorieTarget":500,"proteinTargetG":30},
    {"mealSlot":"LUNCH","calorieTarget":850,"proteinTargetG":50},
    {"mealSlot":"DINNER","calorieTarget":950,"proteinTargetG":60}],
  "microTargets":[],"eatingWindow":null,"activityAdjustments":[],"expectedVersion":0}'
```

Because hard-constraints + lifestyle-config now exist, the frontend onboarding steps 3–4 GET
them with 200 instead of 404.

## 5. Generate + accept a plan

```bash
MON=$(python -c "import datetime as d;t=d.date.today();print(t-d.timedelta(days=t.weekday()))")
curl -s -b $CJ -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -X POST $B/api/v1/plans/generate \
  -d "{\"householdId\":\"$HH_ID\",\"weekStartDate\":\"$MON\",\"forceRegenerateIfActive\":true}"
# → {"planId": "...", "status":"GENERATED", ...}; expect minutes on this host.
curl -s -b $CJ -X POST $B/api/v1/plans/<planId>/accept   # → ACTIVE
```

- Stage C logs the degrade line (see top) — expected with no key.
- A killed generation leaves a stale `core_lock_leases` row (`plan-week|<household>|<week>`,
  10-min TTL) that 409s new generations — wait it out or delete the row.
- Async alternative: `POST /api/v1/plans/generate/async` + poll the returned job.

## 6. Frontend (live) on :5176

`preview_start` the `mealprep-frontend-live` config from `C:\Users\irenv\Claude\.claude\launch.json`
(`VITE_LIVE=1`, Vite proxies `/api` → :8080), or from `frontend/`:
`cmd /c "set VITE_LIVE=1&& npm run dev -- --port 5176"`.

## Troubleshooting

- `localhost` DB connect flakes under Docker Desktop → use `127.0.0.1`/the URL above with
  `localhost` only if it resolves IPv4 first (see the Docker IPv6 loopback note).
- 409 on targets initialise → an `enforcement`/`isHardFloor` missing on some macro.
- Plan 409 CONFLICT right after a crash → stale lease (above).
- Seeder skipped but pool empty? Check the `MEALPREP_DEV_RECIPE_POOL` path is a Windows-style
  `C:/...` path (git-bash `/c/...` is not readable by the JVM).
