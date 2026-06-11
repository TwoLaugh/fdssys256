import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { OrderTimeline } from "../components/OrderTimeline";
import { StatStrip } from "../components/StatStrip";
import { TintChip } from "../components/TintChip";
import {
  addAllergy,
  adjustSlotTime,
  adjustWeeklyBudget,
  inviteMember,
  renameHousehold,
  useStore,
} from "../mock/store";
import type { MealSlotKey } from "../mock/types";

const STEPS = ["Household", "Members", "Allergies", "Lifestyle", "Targets"];
const SLOT_KEYS: MealSlotKey[] = ["breakfast", "lunch", "dinner"];

/**
 * Light 5-step onboarding wizard. Household name, invites and allergies
 * write straight to the store; targets are auto-seeded.
 */
export function Onboarding() {
  const navigate = useNavigate();
  const household = useStore((s) => s.household);
  const prefs = useStore((s) => s.preferences);
  const targets = useStore((s) => s.targets);

  const [step, setStep] = useState(0);
  const [name, setName] = useState(household.name);
  const [email, setEmail] = useState("");
  const [allergy, setAllergy] = useState("");

  const next = () => {
    if (step === 0) renameHousehold(name);
    if (step === STEPS.length - 1) {
      navigate("/");
      return;
    }
    setStep((s) => s + 1);
  };

  const sendInvite = () => {
    if (!/\S+@\S+\.\S+/.test(email.trim())) return;
    inviteMember(email.trim());
    setEmail("");
  };

  const submitAllergy = () => {
    addAllergy(allergy);
    setAllergy("");
  };

  return (
    <div className="auth-wrap">
      <div className="auth-card mp-card" style={{ width: "min(560px, calc(100vw - 48px))" }}>
        <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
          Set up · step {step + 1} of {STEPS.length}
        </span>
        <div style={{ marginTop: 16 }}>
          <OrderTimeline steps={STEPS} at={step} />
        </div>

        <div style={{ marginTop: 24, minHeight: 180 }}>
          {step === 0 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                What should I call your household?
              </span>
              <input
                type="text"
                className="text-input"
                style={{ width: "100%", marginTop: 16 }}
                value={name}
                onChange={(e) => setName(e.target.value)}
                aria-label="Household name"
              />
            </>
          )}

          {step === 1 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                Who eats with you?
              </span>
              <div style={{ marginTop: 14, display: "grid", gap: 6 }}>
                {household.members.map((m) => (
                  <div key={m.id} className="member-row">
                    <span className="member-dot" style={{ background: m.color }} />
                    <span className="member-name">{m.name}</span>
                    <span className="tier-badge">{m.role}</span>
                  </div>
                ))}
                {household.invites.map((inv) => (
                  <div key={inv.email} className="member-row pending">
                    <span className="member-dot pending" />
                    <span className="member-name" style={{ fontWeight: 400 }}>
                      {inv.email}
                    </span>
                    <span className="tier-badge">Pending invite</span>
                  </div>
                ))}
              </div>
              <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                <input
                  type="email"
                  className="text-input"
                  style={{ flex: 1 }}
                  placeholder="Invite by email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && sendInvite()}
                  aria-label="Invite by email"
                />
                <button className="btn btn-small" onClick={sendInvite}>
                  Invite
                </button>
              </div>
            </>
          )}

          {step === 2 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                Anything I must never plan?
              </span>
              <div className="pref-chips" style={{ marginTop: 14 }}>
                {prefs.allergies.map((a) => (
                  <TintChip key={a}>{a}</TintChip>
                ))}
                {prefs.dietary.map((d) => (
                  <TintChip key={d}>{d}</TintChip>
                ))}
              </div>
              <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                <input
                  type="text"
                  className="text-input"
                  style={{ flex: 1 }}
                  placeholder="Add an allergy, e.g. Sesame"
                  value={allergy}
                  onChange={(e) => setAllergy(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && submitAllergy()}
                  aria-label="Add an allergy"
                />
                <button
                  className="btn btn-small"
                  onClick={submitAllergy}
                  disabled={!allergy.trim()}
                >
                  Add
                </button>
              </div>
              <div className="grocery-footnote" style={{ marginTop: 12 }}>
                These become hard constraints — every plan is safety-filtered
                against them.
              </div>
            </>
          )}

          {step === 3 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                When do meals usually happen?
              </span>
              <div style={{ marginTop: 10 }}>
                {SLOT_KEYS.map((slot) => (
                  <div key={slot} className="target-row">
                    <span className="target-label" style={{ textTransform: "capitalize" }}>
                      {slot}
                    </span>
                    <span className="pantry-stepper">
                      <button
                        className="stepper-btn"
                        aria-label={`Earlier ${slot}`}
                        onClick={() => adjustSlotTime(slot, -1)}
                      >
                        −
                      </button>
                      <span className="target-value">
                        <span className="mp-num" style={{ fontSize: 16 }}>
                          {prefs.lifestyle.slotTimes[slot]}
                        </span>
                      </span>
                      <button
                        className="stepper-btn"
                        aria-label={`Later ${slot}`}
                        onClick={() => adjustSlotTime(slot, 1)}
                      >
                        +
                      </button>
                    </span>
                  </div>
                ))}
                <div className="target-row">
                  <span className="target-label">Weekly budget</span>
                  <span className="pantry-stepper">
                    <button
                      className="stepper-btn"
                      aria-label="Decrease weekly budget"
                      onClick={() => adjustWeeklyBudget(-1)}
                    >
                      −
                    </button>
                    <span className="target-value">
                      <span className="mp-num" style={{ fontSize: 16 }}>
                        £{prefs.lifestyle.weeklyBudget}
                      </span>
                    </span>
                    <button
                      className="stepper-btn"
                      aria-label="Increase weekly budget"
                      onClick={() => adjustWeeklyBudget(1)}
                    >
                      +
                    </button>
                  </span>
                </div>
              </div>
            </>
          )}

          {step === 4 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                Targets, auto-seeded from your household.
              </span>
              <div style={{ marginTop: 16 }}>
                <StatStrip
                  numeralSize={20}
                  cells={[
                    { label: "Calories", value: String(targets.calories) },
                    { label: "Protein", value: `${targets.protein} g` },
                    { label: "Carbs", value: `${targets.carbs} g` },
                    { label: "Fat", value: `${targets.fat} g` },
                  ]}
                />
              </div>
              <div className="grocery-footnote" style={{ marginTop: 12 }}>
                Fine-tune any time on the Nutrition page.
              </div>
            </>
          )}
        </div>

        <div className="modal-actions" style={{ marginTop: 24 }}>
          {step > 0 && (
            <button className="btn" onClick={() => setStep((s) => s - 1)}>
              Back
            </button>
          )}
          <button
            className="btn btn-primary"
            onClick={next}
            disabled={step === 0 && !name.trim()}
          >
            {step === STEPS.length - 1 ? "Finish" : "Next"}
          </button>
        </div>
      </div>
    </div>
  );
}
