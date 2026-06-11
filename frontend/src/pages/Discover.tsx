import { useState } from "react";
import { OrderTimeline } from "../components/OrderTimeline";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import { TierMark } from "../components/TierMark";
import {
  keepDiscoveryResult,
  skipDiscoveryResult,
  startDiscovery,
  tierFor,
  useStore,
} from "../mock/store";
import type { DiscoveryResult, DiscoveryStep } from "../mock/types";

const CONSTRAINTS = [
  "Vegetarian",
  "Under 30 min",
  "Budget-friendly",
  "High protein",
  "Nut-free",
];

const STEP_LABELS = ["Queued", "Searching", "Filtering", "Done"];
const STEP_INDEX: Record<DiscoveryStep, number> = {
  QUEUED: 0,
  SEARCHING: 1,
  FILTERING: 2,
  DONE: 3,
};

const RUNNING_LINE: Record<DiscoveryStep, string> = {
  QUEUED: "Queued — waiting for a search slot…",
  SEARCHING: "Scanning four trusted sources for candidates…",
  FILTERING: "Filtering hits against your allergies and taste profile…",
  DONE: "",
};

function ResultCard({ result }: { result: DiscoveryResult }) {
  const tier = tierFor(result.conf);
  return (
    <div className={`mp-card result-card${result.status !== "new" ? " resolved" : ""}`}>
      <div className="result-title">{result.title}</div>
      <div className="result-meta">
        {result.domain} · {result.timeMin} min · {result.cuisine}
      </div>
      <div className="result-foot">
        <span className="conf-pill">
          <TierMark tier={tier} />
          <span>{result.conf.toFixed(2)} AI filter</span>
        </span>
        {result.status === "new" && (
          <span style={{ display: "flex", gap: 8 }}>
            <button
              className="btn btn-small"
              onClick={() => skipDiscoveryResult(result.id)}
            >
              Skip
            </button>
            <button
              className="btn btn-small btn-primary"
              onClick={() => keepDiscoveryResult(result.id)}
            >
              Keep
            </button>
          </span>
        )}
        {result.status === "kept" && (
          <span className="result-kept">✓ in your catalogue</span>
        )}
        {result.status === "skipped" && (
          <span className="result-skipped">skipped</span>
        )}
      </div>
    </div>
  );
}

export function Discover() {
  const discovery = useStore((s) => s.discovery);
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<string[]>([]);

  const job = discovery.job;
  const running = job !== null && job.step !== "DONE";

  const toggle = (c: string) =>
    setPicked((p) => (p.includes(c) ? p.filter((x) => x !== c) : [...p, c]));

  const start = () => {
    if (running) return;
    startDiscovery(query, picked);
  };

  return (
    <div>
      <PageHeader
        title="Discover"
        meta="Search trusted sources for new recipes — every hit is AI-filtered against your constraints"
      />

      <div className="discover-controls">
        <input
          type="search"
          className="recipe-search"
          placeholder="What are you in the mood for?"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && start()}
          aria-label="Discovery search"
        />
        <div className="filter-row">
          <span className="mp-label">Constraints</span>
          {CONSTRAINTS.map((c) => (
            <button
              key={c}
              className={`filter-chip${picked.includes(c) ? " active" : ""}`}
              onClick={() => toggle(c)}
            >
              {c}
            </button>
          ))}
          <button
            className="btn btn-primary"
            style={{ marginLeft: "auto" }}
            onClick={start}
            disabled={running}
          >
            {running ? "Searching…" : "Start discovery"}
          </button>
        </div>
      </div>

      <div className="discover-layout">
        <div>
          {job ? (
            <div className="mp-card side-card">
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
                <span className="mp-label">Discovery job</span>
                <span className="mp-chip">{job.step}</span>
              </div>
              <div className="job-query">
                “{job.query}”
                {job.constraints.length > 0 && (
                  <span className="job-constraints">
                    {" "}
                    · {job.constraints.join(" · ")}
                  </span>
                )}
              </div>
              <div style={{ marginTop: 16 }}>
                <OrderTimeline steps={STEP_LABELS} at={STEP_INDEX[job.step]} />
              </div>
              {running && (
                <div style={{ marginTop: 16 }}>
                  <span className="mp-serif" style={{ fontSize: 19 }}>
                    {RUNNING_LINE[job.step]}
                  </span>
                </div>
              )}
              {job.step === "DONE" && (
                <>
                  <div style={{ marginTop: 18 }}>
                    <span className="mp-label">
                      Sources searched · pages scanned
                    </span>
                    <div style={{ marginTop: 10 }}>
                      <StatStrip
                        numeralSize={20}
                        compact
                        cells={job.sources.map((src) => ({
                          label: src.domain,
                          value: String(src.hits),
                          sub: "pages scanned",
                        }))}
                      />
                    </div>
                  </div>
                  <div className="result-grid">
                    {job.results.map((r) => (
                      <ResultCard key={r.id} result={r} />
                    ))}
                  </div>
                  <div className="grocery-footnote" style={{ marginTop: 14 }}>
                    The AI filter scores each hit against your allergies, taste
                    profile and constraints — Keep imports it as web
                    discovered.
                  </div>
                </>
              )}
            </div>
          ) : (
            <div className="page-loading">
              No job running — pick constraints and start a discovery.
            </div>
          )}
        </div>

        <div className="mp-card side-card" style={{ alignSelf: "start" }}>
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Job history
          </span>
          <div style={{ marginTop: 10 }}>
            {discovery.history.map((h, i) => (
              <div key={`${h.query}-${i}`} className="history-row">
                <div style={{ minWidth: 0 }}>
                  <div className="history-query">“{h.query}”</div>
                  <div className="history-meta">{h.when}</div>
                </div>
                <span className="history-counts">
                  {h.found} found · {h.kept} kept
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
