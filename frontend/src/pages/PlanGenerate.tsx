import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { PageHeader } from "../components/PageHeader";
import {
  acceptCandidate,
  generatePlan,
  regenerate,
  useStore,
} from "../mock/store";
import type { PlanCandidate } from "../mock/types";

function CandidateCard({
  candidate,
  selected,
  onSelect,
}: {
  candidate: PlanCandidate;
  selected: boolean;
  onSelect: () => void;
}) {
  const c = candidate;
  return (
    <button
      type="button"
      className={`candidate-card${c.recommended ? " recommended" : ""}${
        selected ? " selected" : ""
      }`}
      onClick={onSelect}
      aria-pressed={selected}
    >
      {c.recommended && <span className="candidate-tag">RECOMMENDED</span>}
      <span className="mp-label">Candidate {c.id}</span>
      <div className="candidate-fit">
        <span
          className="mp-num"
          style={{
            fontSize: 34,
            color: c.recommended ? "var(--mp-terra)" : "var(--mp-ink)",
          }}
        >
          {c.fit}
        </span>
        <span className="candidate-fit-max">/ 100</span>
      </div>
      <div className="candidate-fit-sub">preference fit</div>
      <div className="candidate-rows">
        <div>
          <span className="candidate-key">Nutrition · </span>
          <span
            style={{
              color: c.nutrition.startsWith("on target")
                ? "var(--mp-olive)"
                : "var(--mp-amber)",
              fontWeight: 600,
            }}
          >
            {c.nutrition}
          </span>
        </div>
        <div>
          <span className="candidate-key">Cost · </span>
          <span style={{ fontWeight: 600 }}>{c.cost}</span>
          <div className="candidate-conf">{c.conf}</div>
        </div>
        <div>
          <span className="candidate-key">Variety · </span>
          <span style={{ fontWeight: 600 }}>{c.variety}</span>
        </div>
        <div>
          <span className="candidate-key">Prep load · </span>
          <span style={{ fontWeight: 600 }}>{c.prep}</span>
        </div>
      </div>
      {c.warn && <span className="warn-pill">{c.warn}</span>}
    </button>
  );
}

export function PlanGenerate() {
  const generation = useStore((s) => s.generation);
  const navigate = useNavigate();
  const [picked, setPicked] = useState<number | null>(null);

  // Kick off generation on first visit (mount-only). Candidates already on
  // screen from an earlier visit are kept; the store guards re-entry while
  // a run is in flight.
  const needsRun = generation.status === "idle";
  useEffect(() => {
    if (needsRun) generatePlan();
  }, [needsRun]);

  const generating = generation.status !== "ready";
  const recommended = generation.candidates.find((c) => c.recommended);
  const selected =
    generation.candidates.find((c) => c.id === picked) ?? recommended;

  return (
    <div>
      <PageHeader
        title={generation.title}
        meta={generation.context}
        actions={
          <>
            <button className="btn" onClick={() => navigate("/plan")}>
              Back to plan
            </button>
            <button className="btn" disabled title="Coming with live wiring">
              Adjust constraints
            </button>
          </>
        }
      />

      <div className="feasibility-band">
        <span style={{ color: "var(--mp-olive)", fontWeight: 700, fontSize: 14 }}>
          ✓
        </span>
        <span>{generation.feasibility}</span>
      </div>

      {generating ? (
        <div>
          <div className="candidate-grid">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="candidate-skeleton" />
            ))}
          </div>
          <div className="gen-wait mp-card">
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span className="advisor-dot" />
              <span
                className="mp-label"
                style={{ color: "var(--mp-terra-dark)" }}
              >
                Generating
              </span>
            </div>
            <div style={{ marginTop: 8 }}>
              <span className="mp-serif" style={{ fontSize: 21 }}>
                Sketching five ways through next week…
              </span>
            </div>
            <div className="gen-wait-sub">
              Balancing nutrition targets, budget, variety and prep load.
            </div>
          </div>
        </div>
      ) : (
        <>
          <div className="candidate-grid">
            {generation.candidates.map((c) => (
              <CandidateCard
                key={c.id}
                candidate={c}
                selected={selected?.id === c.id}
                onSelect={() => setPicked(c.id)}
              />
            ))}
          </div>

          {selected && (
            <div className="reasoning-card mp-card">
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span className="advisor-dot" />
                <span
                  className="mp-label"
                  style={{ color: "var(--mp-terra-dark)" }}
                >
                  Why candidate {selected.id}
                </span>
              </div>
              <div style={{ marginTop: 8, maxWidth: 880 }}>
                <span className="mp-serif" style={{ fontSize: 21 }}>
                  {selected.reasoning}
                </span>
              </div>
              <div className="reasoning-lineup">
                <span className="mp-label">
                  Dinner line-up · candidate {selected.id}
                </span>
                <div className="lineup-chips">
                  {selected.preview.map((p) => (
                    <span key={p} className="lineup-chip">
                      {p}
                    </span>
                  ))}
                </div>
              </div>
              <div className="reasoning-actions">
                <button className="btn" onClick={regenerate}>
                  Regenerate all
                </button>
                <button
                  className="btn btn-primary"
                  onClick={() => {
                    acceptCandidate(selected.id);
                    navigate("/plan");
                  }}
                >
                  Accept candidate {selected.id}
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
