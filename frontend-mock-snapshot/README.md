# Frozen mock snapshot — MealPrep frontend

**Point-in-time copy taken 2026-06-15.** This is a read-only reference of the
**full mock set** — all 15 routes rendering on the in-memory fixtures — captured
just as the live-API wiring began. It is **not maintained**: ongoing work
happens in [`../frontend`](../frontend), which is being wired to the real
backend page-by-page.

## Run it (pure mock, no backend needed)

```bash
npm install
npm run dev
```

Every page renders from the fixtures (`src/mock/`). `VITE_LIVE` is unset here,
so the `src/live/` layer that's present in the copy stays dormant — this snapshot
is the mock set only.

## Why this exists

The evolving `../frontend` shares one set of components between mock and live
modes (mock by default, `VITE_LIVE=1` for the wired set). As pages get wired and
shared components evolve, the exact look/behaviour of the original full mock set
could drift. This snapshot preserves it verbatim for reference and comparison.
