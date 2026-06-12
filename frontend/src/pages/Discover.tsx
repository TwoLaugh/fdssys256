/**
 * Discover — rebuilt against the contract-complete page spec
 * (design/frontend/pages/discover.md): StartDiscoveryJobRequest start panel,
 * the QUEUED → RUNNING → SUCCEEDED | FAILED | PARTIAL job card with live
 * funnel counters, the scrape-log transparency drawer (the HLD audit
 * surface), Keep-as-promote results triage, job history, and the read-only
 * source registry.
 */

import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { OrderTimeline } from "../components/OrderTimeline";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import { TierMark } from "../components/TierMark";
import {
  cancelDiscoveryJob,
  hasLiveDiscoveryJob,
  openDiscoveryJob,
  promoteRecipe,
  skipDiscoveryRow,
  startDiscoveryJob,
  tierFor,
  useStore,
} from "../mock/store";
import type {
  DiscoveryJobDto,
  DiscoveryScrapeLogEntryDto,
  DiscoverySourceDto,
} from "../mock/types";
import { metaLine, shortWhen } from "./recipes/shared";

const CUISINES = ["Italian", "Mexican", "Korean", "Middle Eastern", "Japanese", "Thai"];
const MEAL_TYPES = ["BREAKFAST", "LUNCH", "DINNER", "SNACK"];
const DIETARY = ["vegetarian", "vegan", "gluten_free", "dairy_free"];
const TIME_CHIPS = [20, 30, 45] as const;

/* ---- copy maps (§6) -------------------------------------------------------------- */

function outcomeCopy(row: DiscoveryScrapeLogEntryDto): { text: string; tone: "olive" | "red" | "muted" } {
  switch (row.status) {
    case "SUCCESS":
      return { text: "saved", tone: "olive" };
    case "DUPLICATE":
      return { text: "you already have this one", tone: "muted" };
    case "HARD_CONSTRAINT_VIOLATION":
      return { text: "contains an ingredient you've excluded", tone: "red" };
    case "SKIPPED":
      if (row.skipReason === "AI_FILTER_REJECTED")
        return { text: "AI filter: not your taste", tone: "muted" };
      if (row.skipReason === "LOW_CONFIDENCE")
        return { text: "couldn't read it confidently enough", tone: "muted" };
      if (row.skipReason === "JOB_QUOTA_REACHED")
        return { text: "quota already met", tone: "muted" };
      return { text: "skipped", tone: "muted" };
    case "RATE_LIMITED":
      return { text: "slowed down to be polite", tone: "muted" };
    case "ROBOTS_DISALLOWED":
      return { text: "site disallows scraping", tone: "muted" };
    case "HTTP_ERROR":
      return { text: `page error (HTTP ${row.httpStatusCode ?? "?"})`, tone: "red" };
    case "EXTRACTION_FAILED":
      return { text: "no recipe found on the page", tone: "muted" };
  }
}

const robotsCopy = (row: DiscoveryScrapeLogEntryDto): string | null => {
  if (row.robotsTxtOutcome === "DISALLOWED") return "site asked us not to";
  if (row.robotsTxtOutcome === "UNAVAILABLE") return "robots.txt unreachable — skipped politely";
  return null; // ALLOWED renders nothing; SKIPPED = API source
};

const host = (url: string): string => {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
};

/* ---- start panel (§3) -------------------------------------------------------------- */

function StartPanel({ disabled }: { disabled: boolean }) {
  const sources = useStore((s) => s.discovery.sources);
  const [hints, setHints] = useState("");
  const [count, setCount] = useState(10);
  const [cuisines, setCuisines] = useState<string[]>([]);
  const [mealTypes, setMealTypes] = useState<string[]>([]);
  const [dietary, setDietary] = useState<string[]>([]);
  const [maxTime, setMaxTime] = useState<number | null>(null);
  const [advanced, setAdvanced] = useState(false);
  const [perSourceCap, setPerSourceCap] = useState("");
  const [pickedSources, setPickedSources] = useState<string[]>([]);

  const toggle = (xs: string[], set: (v: string[]) => void, v: string) =>
    set(xs.includes(v) ? xs.filter((x) => x !== v) : [...xs, v]);

  const start = () => {
    if (disabled) return;
    startDiscoveryJob({
      trigger: "USER_INITIATED", // this page's only value (§3)
      requestedCount: count,
      constraints: {
        schemaVersion: 1,
        requiredCuisines: cuisines.length > 0 ? cuisines : null,
        requiredMealTypes: mealTypes.length > 0 ? mealTypes : null,
        maxTotalTimeMins: maxTime,
        // Hard-constraint snapshot computed by the CALLER from the user's
        // allergies (client-trust hole for a safety filter — spec §9 Q3).
        mustExcludeIngredientMappingKeys: null,
        dietaryFlags: dietary.length > 0 ? dietary : null,
        preferenceHints: hints.trim() === "" ? null : [hints.trim()],
        maxRecipesPerSource: perSourceCap.trim() === "" ? null : Number(perSourceCap),
      },
      sourceKeys: pickedSources.length > 0 ? pickedSources : null,
      traceId: null,
    });
  };

  return (
    <div className="discover-controls">
      <input
        type="search"
        className="recipe-search"
        placeholder="What are you in the mood for? (free-form hints for the AI filter)"
        value={hints}
        onChange={(e) => setHints(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && start()}
        aria-label="Preference hints"
      />
      <div className="filter-row">
        <span className="mp-label">Cuisine</span>
        {CUISINES.map((c) => (
          <button key={c} className={`filter-chip${cuisines.includes(c) ? " active" : ""}`}
            onClick={() => toggle(cuisines, setCuisines, c)}>
            {c}
          </button>
        ))}
        <span className="mp-label" style={{ marginLeft: 14 }}>Max time</span>
        {TIME_CHIPS.map((t) => (
          <button key={t} className={`filter-chip${maxTime === t ? " active" : ""}`}
            onClick={() => setMaxTime(maxTime === t ? null : t)}>
            ≤ {t} min
          </button>
        ))}
      </div>
      <div className="filter-row">
        <span className="mp-label">Meals</span>
        {MEAL_TYPES.map((m) => (
          <button key={m} className={`filter-chip${mealTypes.includes(m) ? " active" : ""}`}
            onClick={() => toggle(mealTypes, setMealTypes, m)}>
            {m.toLowerCase()}
          </button>
        ))}
        <span className="mp-label" style={{ marginLeft: 14 }}>Dietary</span>
        {DIETARY.map((d) => (
          <button key={d} className={`filter-chip${dietary.includes(d) ? " active" : ""}`}
            onClick={() => toggle(dietary, setDietary, d)}>
            {d.replace("_", " ")}
          </button>
        ))}
      </div>
      <div className="filter-row">
        <span className="mp-label">How many</span>
        <span className="pantry-stepper">
          <button className="stepper-btn" aria-label="Fewer recipes"
            onClick={() => setCount((c) => Math.max(1, c - 1))}>−</button>
          <span className="pantry-qty mp-num">{count}</span>
          <button className="stepper-btn" aria-label="More recipes"
            onClick={() => setCount((c) => Math.min(50, c + 1))}>+</button>
        </span>
        <button className="filter-chip" onClick={() => setAdvanced((v) => !v)}>
          {advanced ? "Hide advanced" : "Advanced"}
        </button>
        <button className="btn btn-primary" style={{ marginLeft: "auto" }}
          onClick={start} disabled={disabled}>
          {disabled ? "A discovery is already running…" : "Start discovery"}
        </button>
      </div>
      {advanced && (
        <div className="filter-row" style={{ alignItems: "center" }}>
          <span className="mp-label">Per-source cap</span>
          <input
            type="number" className="text-input num-input" min={1} max={count}
            value={perSourceCap} aria-label="Max recipes per source"
            onChange={(e) => setPerSourceCap(e.target.value)}
          />
          <span className="mp-label" style={{ marginLeft: 14 }}>Sources (blank = all enabled)</span>
          {sources.filter((s) => s.enabled).map((s) => (
            <button key={s.sourceKey}
              className={`filter-chip${pickedSources.includes(s.sourceKey) ? " active" : ""}`}
              onClick={() => toggle(pickedSources, setPickedSources, s.sourceKey)}>
              {s.displayName}
            </button>
          ))}
        </div>
      )}
      <div className="grocery-footnote">
        Your allergy hard-constraints ride along as
        mustExcludeIngredientMappingKeys — computed by this client per the LLD
        (server-side injection is a flagged safety gap, spec §9 Q3).
      </div>
    </div>
  );
}

/* ---- result cards (§5) -------------------------------------------------------------- */

function ResultCard({ row }: { row: DiscoveryScrapeLogEntryDto }) {
  const recipe = useStore((s) => s.recipes.find((r) => r.id === row.recipeId));
  const skipped = useStore((s) => s.discovery.skippedRowIds.includes(row.id));
  if (!recipe) return null; // 404 join → drop the card
  const kept = recipe.catalogue === "USER";
  const conf = row.extractionConfidence ?? 0;
  return (
    <div className={`mp-card result-card${kept || skipped ? " resolved" : ""}`}>
      <div className="result-title">{recipe.name}</div>
      <div className="result-meta">
        {host(row.canonicalUrl ?? row.candidateUrl)} · {metaLine(recipe)}
      </div>
      <div className="result-foot">
        <span
          className="conf-pill"
          title={
            row.extractionMethod === "json_ld"
              ? "read from the page's recipe data"
              : `extraction method: ${row.extractionMethod ?? "unknown"}`
          }
        >
          <TierMark tier={tierFor(conf)} />
          <span>{conf.toFixed(2)} extraction</span>
        </span>
        {kept ? (
          <span className="result-kept">
            ✓ in your library · <Link to={`/recipes/${recipe.id}`}>open</Link>
          </span>
        ) : skipped ? (
          <span className="result-skipped">skipped (kept in the pool)</span>
        ) : (
          <span style={{ display: "flex", gap: 8 }}>
            <button className="btn btn-small" onClick={() => skipDiscoveryRow(row.id)}>
              Skip
            </button>
            <button
              className="btn btn-small btn-primary"
              title="POST /recipes/{id}/promote — the recipe is already persisted in the system catalogue; Keep just flips it to yours"
              onClick={() => promoteRecipe(recipe.id)}
            >
              Keep
            </button>
          </span>
        )}
      </div>
    </div>
  );
}

/* ---- scrape-log drawer (§6) ----------------------------------------------------------- */

function ScrapeLogTable({ rows }: { rows: DiscoveryScrapeLogEntryDto[] }) {
  return (
    <div className="table-scroll" style={{ marginTop: 10 }}>
      <table className="nv-table">
        <thead>
          <tr>
            <th>Page</th>
            <th>Source</th>
            <th>Outcome</th>
            <th>HTTP · latency</th>
            <th>Extraction</th>
            <th>When</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const copy = outcomeCopy(row);
            const robots = robotsCopy(row);
            return (
              <tr key={row.id}>
                <td style={{ maxWidth: 240 }}>
                  <div className="scrape-url" title={row.canonicalUrl ?? row.candidateUrl}>
                    {host(row.candidateUrl)}
                    {new URL(row.candidateUrl).pathname.slice(0, 36)}
                  </div>
                  {row.recipeId && (
                    <Link to={`/recipes/${row.recipeId}`} style={{ fontSize: 12 }}>
                      view recipe
                    </Link>
                  )}
                </td>
                <td>{row.sourceKey}</td>
                <td>
                  <span className={`scrape-outcome ${copy.tone}`}>{copy.text}</span>
                  {robots && <div className="version-meta">{robots}</div>}
                  {row.errorClass && (
                    <details className="micros-details" style={{ marginTop: 2 }}>
                      <summary style={{ fontSize: 11.5 }}>error detail</summary>
                      <div className="version-meta">
                        {row.errorClass}: {row.errorMessage}
                      </div>
                    </details>
                  )}
                </td>
                <td className="version-meta">
                  {row.httpStatusCode ?? "—"}
                  {row.latencyMs != null && ` · ${row.latencyMs} ms`}
                </td>
                <td className="version-meta">
                  {row.extractionMethod ?? "—"}
                  {row.extractionConfidence != null && ` · ${row.extractionConfidence.toFixed(2)}`}
                </td>
                <td className="version-meta">{row.occurredAt.slice(11, 16)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/* ---- job card (§4) ----------------------------------------------------------------------- */

const NO_ROWS: DiscoveryScrapeLogEntryDto[] = [];

function JobCard({ job }: { job: DiscoveryJobDto }) {
  // Stored-reference selector (useSyncExternalStore) — `?? NO_ROWS` is a
  // stable constant, never a fresh array per snapshot.
  const rowsStored = useStore((s) => s.discovery.scrapeLog[job.id]);
  const rows = rowsStored ?? NO_ROWS;
  const stopping = useStore((s) => s.discovery.cancelRequested === job.id);
  const [drawer, setDrawer] = useState(false);
  const successRows = rows.filter((r) => r.status === "SUCCESS" && r.recipeId);
  const cancelled = job.errorSummary === "cancelled by user"; // string contract (§9 Q2)
  const timelineAt = job.status === "QUEUED" ? 0 : job.status === "RUNNING" ? 1 : 2;
  const live = job.status === "QUEUED" || job.status === "RUNNING";

  const perSource = useMemo(() => {
    const m = new Map<string, { rows: number; saved: number }>();
    for (const r of rows) {
      const slot = m.get(r.sourceKey) ?? { rows: 0, saved: 0 };
      slot.rows += 1;
      if (r.status === "SUCCESS") slot.saved += 1;
      m.set(r.sourceKey, slot);
    }
    return [...m.entries()];
  }, [rows]);

  const c = job.constraints;
  const recap: string[] = [
    ...(c.preferenceHints ?? []).map((h) => `“${h}”`),
    ...(c.requiredCuisines ?? []),
    ...(c.requiredMealTypes ?? []).map((m) => m.toLowerCase()),
    ...(c.dietaryFlags ?? []).map((d) => d.replace("_", " ")),
    ...(c.maxTotalTimeMins != null ? [`≤ ${c.maxTotalTimeMins} min`] : []),
    ...((c.mustExcludeIngredientMappingKeys?.length ?? 0) > 0
      ? [`${c.mustExcludeIngredientMappingKeys?.length} hard-excluded ingredients`]
      : []),
  ];

  return (
    <div className="mp-card side-card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
        <span className="mp-label">Discovery job</span>
        <span style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
          {job.trigger !== "USER_INITIATED" && (
            <span className="mp-chip muted">
              {job.trigger === "SCHEDULED" ? "weekly sweep" : "planner cold-start"}
            </span>
          )}
          <span className={`mp-chip${job.status === "PARTIAL" ? " amber" : ""}`}>
            {cancelled ? "cancelled" : job.status}
          </span>
        </span>
      </div>

      <div className="job-query">
        asked for {job.requestedCount}
        {recap.length > 0 && (
          <span className="job-constraints"> · {recap.join(" · ")} (frozen at enqueue)</span>
        )}
      </div>

      <div style={{ marginTop: 14 }}>
        <OrderTimeline
          steps={["Queued", "Running", job.status === "PARTIAL" ? "Done (partial)" : cancelled ? "Stopped" : "Done"]}
          at={timelineAt}
        />
      </div>

      <div className="version-meta" style={{ marginTop: 10 }}>
        queued {shortWhen(job.queuedAt)} {job.queuedAt.slice(11, 16)}
        {job.startedAt && ` → started ${job.startedAt.slice(11, 16)}`}
        {job.completedAt &&
          ` → done ${job.completedAt.slice(11, 16)} (ran ${Math.max(
            1,
            Math.round(
              (Date.parse(job.completedAt) - Date.parse(job.startedAt ?? job.queuedAt)) / 1000,
            ),
          )} s)`}
      </div>

      {job.status === "QUEUED" && (
        <div style={{ marginTop: 14 }}>
          <span className="mp-serif" style={{ fontSize: 19 }}>
            Queued — waiting for a runner…
          </span>
        </div>
      )}
      {job.status === "RUNNING" && (
        <div style={{ marginTop: 14 }}>
          <span className="mp-serif" style={{ fontSize: 19 }}>
            {stopping
              ? "Stopping after the current page…"
              : "Working — every fetch lands in the audit log below as it happens."}
          </span>
        </div>
      )}

      {/* funnel counters */}
      <div style={{ marginTop: 14 }}>
        <StatStrip
          compact
          numeralSize={20}
          cells={[
            { label: "Candidates seen", value: String(job.candidatesSeen) },
            {
              label: "After AI filter",
              value: String(job.candidatesAfterFilter),
              sub: `kept ${job.candidatesAfterFilter} of ${job.candidatesSeen}`,
            },
            { label: "Saved", value: String(job.recipesIngested), sub: "into the pool" },
            {
              label: "Duplicates",
              value: String(job.recipesSkippedDuplicate),
              sub: "you already had",
            },
          ]}
        />
      </div>

      {/* per-source chips */}
      {job.sourcesRequested.length > 0 && (
        <div className="filter-row" style={{ marginTop: 12 }}>
          <span className="mp-label">Sources</span>
          {job.sourcesRequested.map((k) => {
            const ok = job.sourcesSucceeded.includes(k);
            const failed = job.sourcesFailed.includes(k);
            return (
              <span key={k} className={`source-chip${ok ? " ok" : failed ? " failed" : ""}`}>
                {k}
              </span>
            );
          })}
        </div>
      )}

      {job.status === "PARTIAL" && job.errorSummary && (
        <div className="import-warnings" style={{ marginTop: 12 }}>
          ⚠ some sources failed — {job.errorSummary}
        </div>
      )}
      {job.status === "FAILED" && (
        <div className={cancelled ? "inline-note" : "rf-errors"} style={{ marginTop: 12 }}>
          {cancelled
            ? "Cancelled by user — already-saved recipes are kept below (no CANCELLED status in the contract; the UI matches on the errorSummary string, spec §9 Q2)."
            : job.errorSummary}
        </div>
      )}

      {live && (
        <div style={{ marginTop: 14 }}>
          <button className="btn" onClick={() => cancelDiscoveryJob(job.id)} disabled={stopping}>
            {stopping ? "Stopping…" : "Cancel"}
          </button>
        </div>
      )}

      {/* results triage — scrape-log SUCCESS rows joined to recipes (§5) */}
      {successRows.length > 0 && (
        <>
          <div style={{ marginTop: 18 }}>
            <span className="mp-label">
              Results · {successRows.length} saved to the pool — Keep = add to your library
            </span>
          </div>
          <div className="result-grid">
            {successRows.map((row) => (
              <ResultCard key={row.id} row={row} />
            ))}
          </div>
          <div className="grocery-footnote" style={{ marginTop: 10 }}>
            Skip is a local dismissal only — the recipe stays in the pool and
            the planner may still draw on it (spec §9 Q5). Per-job “kept”
            counts aren't derivable from the contract (spec §9 Q6).
          </div>
        </>
      )}

      {/* per-source stat strip + transparency drawer */}
      {perSource.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <StatStrip
            compact
            numeralSize={18}
            cells={perSource.map(([k, v]) => ({
              label: k,
              value: String(v.rows),
              sub: `${v.saved} saved`,
            }))}
          />
        </div>
      )}
      {rows.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <button className="btn btn-small" onClick={() => setDrawer((v) => !v)}>
            {drawer ? "Hide fetch audit" : `Fetch audit — every attempt (${rows.length})`}
          </button>
          {drawer && <ScrapeLogTable rows={rows} />}
        </div>
      )}
    </div>
  );
}

/* ---- sources panel (§7b) ------------------------------------------------------------------ */

function SourceRow({ src }: { src: DiscoverySourceDto }) {
  return (
    <div className={`history-row${src.enabled ? "" : " source-disabled"}`}>
      <div style={{ minWidth: 0 }}>
        <div className="history-query">
          {src.displayName}
          <span className="version-meta" style={{ marginLeft: 8 }}>
            {src.kind === "SEARCH_API" ? "search API" : src.kind.toLowerCase().replace("_", " ")} ·{" "}
            {host(src.baseUrl)}
          </span>
        </div>
        <div className="history-meta">
          {!src.enabled && "unavailable (admin) · "}
          {src.failureStreak >= 5 && "paused after repeated failures · "}
          {src.lastSuccessAt && `last worked ${shortWhen(src.lastSuccessAt)}`}
          {src.lastFailureAt && ` · last failed ${shortWhen(src.lastFailureAt)}`}
          {src.notes && ` · ${src.notes}`}
        </div>
      </div>
      <span
        className="history-counts"
        title={`${src.requestsPerMinute}/min · ${src.requestsPerDay}/day · ${
          src.respectRobotsTxt ? "honours robots.txt" : "robots.txt n/a"
        } · ${src.userAgent}`}
      >
        {src.requestsPerMinute}/min
      </span>
    </div>
  );
}

/* ---- page ----------------------------------------------------------------------------------- */

export function Discover() {
  const discovery = useStore((s) => s.discovery);
  const live = useStore(hasLiveDiscoveryJob);
  const openJob = discovery.jobs.find((j) => j.id === discovery.openJobId);

  return (
    <div>
      <PageHeader
        title="Discover"
        meta="Autonomous discovery jobs — hard-filtered deterministically, AI-scored advisorily; every fetch is audited"
      />

      <StartPanel disabled={live} />

      <div className="discover-layout">
        <div>
          {openJob ? (
            <JobCard job={openJob} />
          ) : (
            <div className="page-loading">
              No job open — start a discovery or open one from the history.
            </div>
          )}
        </div>

        <div style={{ display: "grid", gap: 18, alignSelf: "start" }}>
          <div className="mp-card side-card">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Job history
            </span>
            <div style={{ marginTop: 10 }}>
              {discovery.jobs.map((j) => (
                <button
                  key={j.id}
                  className="history-row history-btn"
                  onClick={() => openDiscoveryJob(j.id)}
                >
                  <div style={{ minWidth: 0 }}>
                    <div className="history-query">
                      {(j.constraints.preferenceHints ?? []).join(" · ") ||
                        [
                          ...(j.constraints.requiredCuisines ?? []),
                          ...(j.constraints.dietaryFlags ?? []),
                        ].join(" · ") ||
                        "weekly sweep"}
                    </div>
                    <div className="history-meta">
                      {shortWhen(j.queuedAt)}
                      {j.trigger === "SCHEDULED" && " · weekly sweep"}
                      {j.trigger === "COLD_START" && " · planner cold-start"}
                      {j.errorSummary === "cancelled by user" && " · cancelled"}
                    </div>
                  </div>
                  <span style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
                    <span className="history-counts">
                      {j.candidatesSeen} found · {j.recipesIngested} saved
                    </span>
                    <span className={`mp-chip${j.status === "PARTIAL" ? " amber" : j.status === "FAILED" ? " muted" : ""}`}>
                      {j.status}
                    </span>
                  </span>
                </button>
              ))}
            </div>
            <div className="grocery-footnote" style={{ marginTop: 10 }}>
              “Kept” counts per job aren't shown — promotion isn't linked back
              to the discovery job in the contract (spec §9 Q6).
            </div>
          </div>

          <div className="mp-card side-card">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Sources · read-only
            </span>
            <div style={{ marginTop: 10 }}>
              {discovery.sources.map((src) => (
                <SourceRow key={src.sourceKey} src={src} />
              ))}
            </div>
            <div className="grocery-footnote" style={{ marginTop: 10 }}>
              User disable is unshipped (the DB has user_disabled but no
              endpoint or DTO flag — spec §9 Q4); admin enable/disable lives on
              the Admin surface.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
