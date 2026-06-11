import { useNavigate } from "react-router-dom";
import { AdvisorPanel } from "../components/AdvisorPanel";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import { StatusMark } from "../components/StatusMark";
import { SwapLine } from "../components/SwapLine";
import { acceptReoptFix, dismissReoptFix, useStore } from "../mock/store";
import type { MealSlotKey, PlanSlot } from "../mock/types";

const SLOT_KEYS: MealSlotKey[] = ["breakfast", "lunch", "dinner"];

function PlanMealCell({ slot }: { slot: PlanSlot }) {
  const affected = slot.state === "affected";
  return (
    <div className="plan-cell">
      <StatusMark status={slot.state} />
      <span
        className={`plan-cell-name ${slot.state}`}
        title={slot.name}
      >
        {slot.name}
      </span>
      {slot.batch && <span className="batch-tag">BATCH</span>}
      {affected && <span className="visually-hidden">affected by suggestion</span>}
    </div>
  );
}

export function Plan() {
  const plan = useStore((s) => s.plan);
  const navigate = useNavigate();

  return (
    <div>
      <PageHeader
        title={plan.title}
        chip={<span className="mp-chip">Active</span>}
        meta={`${plan.range} · ${plan.meta}`}
        actions={
          <>
            <button className="btn" onClick={() => navigate("/plan/generate")}>
              Re-optimise
            </button>
            <button
              className="btn btn-primary"
              onClick={() => navigate("/plan/generate")}
            >
              Generate next week
            </button>
          </>
        }
      />

      <div style={{ marginTop: 26 }}>
        <StatStrip cells={plan.stats} />
      </div>

      {plan.fix && (
        <AdvisorPanel
          label="Suggested fix"
          headerRight={plan.fix.sub}
          title={plan.fix.title}
          impact={plan.fix.impact}
          acceptLabel="Accept changes"
          onAccept={acceptReoptFix}
          onDismiss={dismissReoptFix}
        >
          <div style={{ marginTop: 14, display: "grid", gap: 8 }}>
            {plan.fix.swaps.map((sw) => (
              <SwapLine
                key={sw.slotLabel}
                prefix={sw.slotLabel}
                from={sw.from}
                to={sw.to}
                note={sw.note}
              />
            ))}
          </div>
        </AdvisorPanel>
      )}

      <div style={{ marginTop: 30 }}>
        {/* Week grid scrolls horizontally on narrow windows (.plan-scroll). */}
        <div className="plan-scroll">
          <div className="plan-grid plan-grid-head">
            <span />
            <span className="mp-label">Breakfast</span>
            <span className="mp-label">Lunch</span>
            <span className="mp-label">Dinner</span>
          </div>
          {plan.days.map((day) => (
            <div
              key={day.day}
              className={`plan-grid plan-row${day.today ? " today" : ""}`}
            >
              <div className="plan-day">
                <span
                  className="mp-num"
                  style={{
                    fontSize: 17,
                    color: day.today ? "var(--mp-terra)" : "var(--mp-ink)",
                  }}
                >
                  {day.day} {day.date}
                </span>
                {day.today && <span className="plan-today-tag">TODAY</span>}
              </div>
              {SLOT_KEYS.map((key) => (
                <PlanMealCell key={key} slot={day.slots[key]} />
              ))}
            </div>
          ))}
        </div>
        <div className="plan-legend">
          <span>
            <span style={{ color: "var(--mp-olive)", fontWeight: 700 }}>✓</span>{" "}
            eaten
          </span>
          <span>
            <span style={{ color: "var(--mp-amber)" }}>●</span> cooked
          </span>
          <span>
            <span style={{ color: "var(--mp-mark-planned)" }}>○</span> planned
          </span>
          <span>
            <span style={{ color: "var(--mp-red)", fontWeight: 700 }}>✕</span>{" "}
            affected by suggestion
          </span>
          <span>
            <span className="batch-tag" style={{ fontSize: 11 }}>
              BATCH
            </span>{" "}
            batch-cook link
          </span>
        </div>
      </div>
    </div>
  );
}
