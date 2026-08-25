/**
 * Onboarding wizard — rebuilt against the contract-complete page spec
 * (design/frontend/pages/onboarding.md). Five steps over endpoints that all
 * have richer homes elsewhere; wizard state is DERIVED, not stored — the §4
 * probe chain (households/current → hard-constraints → lifestyle-config →
 * targets) picks the first unsatisfied step on mount.
 *
 * Spec §5 G1 — BLOCKER: steps 3 & 4 PUT against aggregates that no REST
 * call can initialise (the backend's initialise flows are internal-only).
 * The mock upserts on first PUT so the wizard is demonstrable; the steps
 * carry a data-gap="G1" footnote so the live wiring fails loudly.
 */

import { useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { OrderTimeline } from "../components/OrderTimeline";
import { StatStrip } from "../components/StatStrip";
import { TintChip } from "../components/TintChip";
import {
  acceptInvite,
  addAllergy,
  adjustSlotTime,
  completeFreshSetupStep,
  createHousehold,
  createInvite,
  exitOnboarding,
  onboardingResumeStep,
  pushToast,
  replayOnboardingAsFresh,
  selectSlotTimes,
  useStore,
} from "../mock/store";
import type { AcceptInviteOutcome } from "../mock/store";
import type { MealSlotKey } from "../mock/types";
import { MOCK_NOW_MS } from "../live/dates";

const STEPS = ["Household", "Invite", "Allergies", "Lifestyle", "Targets"];
const SLOT_KEYS: MealSlotKey[] = ["breakfast", "lunch", "dinner"];

const JOIN_ERROR: Record<Exclude<AcceptInviteOutcome, "ok">, string> = {
  badRequest: "Enter the code you were sent.",
  forbidden: "This invite was issued for a different account.",
  notFound: "Code not recognised — check for typos.",
  alreadyInHousehold: "You're already in a household — continuing.",
  gone: "This invite expired or was revoked — ask for a new one.",
};

/** Client-computed macro suggestions for step 5 — the wizard's job; micros
 *  are omitted from the POST and DRI-seeded server-side (§3). */
function suggestTargets(goal: "maintain" | "lose" | "gain") {
  const calories = goal === "lose" ? 1800 : goal === "gain" ? 2400 : 2100;
  return {
    calories,
    proteinG: Math.round((calories * 0.25) / 4),
    carbsG: Math.round((calories * 0.45) / 4),
    fatG: Math.round((calories * 0.3) / 9),
    fibreG: 30,
    satFatG: Math.round((calories * 0.1) / 9),
  };
}

/** The G1 blocker footnote both gap steps render (§5 G1). */
function GapFootnote() {
  return (
    <div className="grocery-footnote" style={{ marginTop: 12 }} data-gap="G1">
      (backend gap: this PUT 404s on a fresh account — no REST initialise
      exists; the mock upserts on first write. See onboarding spec §5 G1.)
    </div>
  );
}

export function Onboarding() {
  const navigate = useNavigate();
  const user = useStore((s) => s.session.user);
  const fresh = useStore((s) => s.session.freshSetup);
  const resume = useStore(onboardingResumeStep);
  const constraints = useStore((s) => s.preferences.hardConstraints);
  const slotTimes = useStore(selectSlotTimes);
  const targets = useStore((s) => s.targets);

  const [stepOverride, setStepOverride] = useState<number | null>(null);
  const [name, setName] = useState("");
  const [joinMode, setJoinMode] = useState(false);
  const [joinCode, setJoinCode] = useState("");
  const [joinError, setJoinError] = useState<string | null>(null);
  const [inviteCode, setInviteCode] = useState<string | null>(null);
  const [allergy, setAllergy] = useState("");
  const [identity, setIdentity] = useState("omnivore");
  const [intolerance, setIntolerance] = useState("");
  const [intolerances, setIntolerances] = useState<string[]>([]);
  const [novelty, setNovelty] = useState(20);
  const [batchCooking, setBatchCooking] = useState(true);
  const [goal, setGoal] = useState<"maintain" | "lose" | "gain">("maintain");
  const [calorieTweak, setCalorieTweak] = useState(0);

  // Entry condition is shell-routed (login.md §5): unauthenticated → login.
  if (!user) {
    return <Navigate to="/login?next=%2Fonboarding" replace />;
  }

  // §4: all probes satisfied and not replaying → nothing to onboard.
  if (resume === null && !fresh) {
    return (
      <div className="auth-wrap">
        <div className="auth-card mp-card">
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            Set up
          </span>
          <div style={{ marginTop: 10 }}>
            <span className="mp-serif" style={{ fontSize: 26 }}>
              Everything's already configured.
            </span>
          </div>
          <div style={{ fontSize: 13.5, color: "var(--mp-muted)", marginTop: 10 }}>
            The resume probe found a household, hard constraints, lifestyle
            config and nutrition targets — the wizard has nothing to do
            (spec §4: state is derived, never stored).
          </div>
          <div className="modal-actions" style={{ marginTop: 18 }}>
            <button className="btn" onClick={() => replayOnboardingAsFresh()}>
              Replay as a fresh account (demo)
            </button>
            <button className="btn btn-primary" onClick={() => navigate("/")}>
              Go to Today
            </button>
          </div>
        </div>
      </div>
    );
  }

  const step = stepOverride ?? resume ?? 0;
  const suggestion = suggestTargets(goal);
  const finalCalories = suggestion.calories + calorieTweak;

  const advance = (to?: number) => setStepOverride(to ?? step + 1);

  const finish = () => {
    exitOnboarding();
    navigate("/");
  };

  /* ---- step submits (§3) ---- */

  const submitHousehold = () => {
    const ok = createHousehold(name);
    if (!ok && !fresh) {
      // 409 "already a member" → the user half-finished earlier: silently
      // advance to step 2 (§3 step 1a).
      advance(1);
      return;
    }
    if (ok) advance(1);
  };

  const submitJoin = () => {
    setJoinError(null);
    const outcome = acceptInvite(joinCode);
    if (outcome === "ok" || outcome === "alreadyInHousehold") {
      // Joiners skip straight to step 3: household + slots already exist,
      // and a joining member isn't primary anyway (§2).
      pushToast(outcome === "ok" ? "Joined the household" : JOIN_ERROR[outcome]);
      advance(2);
      return;
    }
    setJoinError(JOIN_ERROR[outcome]);
  };

  const submitInvite = () => {
    const created = createInvite({
      intendedRole: "member",
      expiresAt: new Date(MOCK_NOW_MS + 7 * 86_400_000).toISOString(),
    });
    if (created?.inviteCode) setInviteCode(created.inviteCode);
  };

  const submitAllergy = () => {
    if (!allergy.trim()) return;
    addAllergy(allergy);
    setAllergy("");
  };

  const submitConstraints = () => {
    // Full-replace UpdateHardConstraintsRequest with expectedVersion: 0 and
    // empty arrays for anything untouched (§3 step 3) — mock upsert (G1).
    completeFreshSetupStep("constraints");
    advance(3);
  };

  const submitLifestyle = () => {
    // UpdateLifestyleConfigRequest, expectedVersion: 0 — mock upsert (G1).
    completeFreshSetupStep("lifestyle");
    advance(4);
  };

  const submitTargets = () => {
    // POST /nutrition/targets/initialise — reuses UpdateTargetsRequest, so
    // the create call sends a meaningless expectedVersion: 0 and the full
    // aggregate (spec §5 G2). 409 "targets exist" → silently advance.
    if (!fresh || fresh.targets) {
      pushToast("409 — targets already initialised; continuing");
    } else {
      completeFreshSetupStep("targets");
      pushToast(`Targets initialised — ${finalCalories} kcal; micros DRI-seeded`);
    }
    finish();
  };

  return (
    <div className="auth-wrap">
      <div
        className="auth-card mp-card"
        style={{ width: "min(560px, calc(100vw - 48px))" }}
      >
        <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
          Set up · step {step + 1} of {STEPS.length}
        </span>
        <div style={{ marginTop: 16 }}>
          <OrderTimeline steps={STEPS} at={step} />
        </div>

        <div style={{ marginTop: 24, minHeight: 200 }}>
          {step === 0 && !joinMode && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                What should I call your household?
              </span>
              <input
                type="text"
                className="text-input"
                style={{ width: "100%", marginTop: 16 }}
                placeholder="e.g. Veer household (1–128 characters)"
                value={name}
                maxLength={128}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && name.trim() && submitHousehold()}
                aria-label="Household name"
              />
              <button
                className="link-btn"
                style={{ marginTop: 12 }}
                onClick={() => setJoinMode(true)}
              >
                …or join one with an invite code →
              </button>
            </>
          )}

          {step === 0 && joinMode && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                Got an invite code?
              </span>
              <input
                type="text"
                className="text-input"
                style={{ width: "100%", marginTop: 16 }}
                placeholder="MP-XXXX-XXXX"
                value={joinCode}
                maxLength={32}
                onChange={(e) => setJoinCode(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && joinCode.trim() && submitJoin()}
                aria-label="Invite code"
              />
              {joinError && (
                <div style={{ color: "var(--mp-red)", fontSize: 13, marginTop: 8 }}>
                  {joinError}
                </div>
              )}
              <button
                className="link-btn"
                style={{ marginTop: 12 }}
                onClick={() => setJoinMode(false)}
              >
                ← Create a new household instead
              </button>
            </>
          )}

          {step === 1 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                Who eats with you?
              </span>
              <div style={{ fontSize: 13.5, color: "var(--mp-muted)", marginTop: 8 }}>
                “Just me” is the default — skip ahead if it's only you. Codes
                are share-by-hand in v1 (no email send).
              </div>
              {inviteCode ? (
                <div className="mp-card" style={{ marginTop: 14, padding: 14 }}>
                  <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
                    One-time code — you won't see it again
                  </span>
                  <div className="mp-num" style={{ fontSize: 22, marginTop: 6 }}>
                    {inviteCode}
                  </div>
                  <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
                    <button
                      className="btn btn-small"
                      onClick={() => {
                        void navigator.clipboard
                          ?.writeText(`${location.origin}/invite?code=${inviteCode}`)
                          .catch(() => undefined);
                        pushToast("Share link copied");
                      }}
                    >
                      Copy share link
                    </button>
                    <button className="btn btn-small" onClick={() => setInviteCode(null)}>
                      Invite another
                    </button>
                  </div>
                </div>
              ) : (
                <button className="btn" style={{ marginTop: 14 }} onClick={submitInvite}>
                  Create an invite (member · 7 days)
                </button>
              )}
            </>
          )}

          {step === 2 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                Anything I must never plan?
              </span>
              <div className="pref-chips" style={{ marginTop: 14 }}>
                {(constraints?.allergies ?? []).map((a) => (
                  <TintChip key={a}>{a}</TintChip>
                ))}
                {intolerances.map((i) => (
                  <TintChip key={i}>{i} (severe)</TintChip>
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
              <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
                <select
                  className="time-select"
                  value={identity}
                  onChange={(e) => setIdentity(e.target.value)}
                  aria-label="Dietary identity"
                >
                  {["omnivore", "vegetarian", "vegan", "pescatarian"].map((d) => (
                    <option key={d} value={d}>
                      {d}
                    </option>
                  ))}
                </select>
                <input
                  type="text"
                  className="text-input"
                  style={{ flex: 1 }}
                  placeholder="Severe intolerance (optional)"
                  value={intolerance}
                  onChange={(e) => setIntolerance(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && intolerance.trim()) {
                      setIntolerances((xs) => [...xs, intolerance.trim()]);
                      setIntolerance("");
                    }
                  }}
                  aria-label="Severe intolerance"
                />
              </div>
              <div className="grocery-footnote" style={{ marginTop: 12 }}>
                Submits the FULL-replace hard-constraints shape with empty
                arrays for whatever you didn't touch (expectedVersion 0).
                Additions never trigger the GAP-04 interstitial.
              </div>
              <GapFootnote />
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
                          {slotTimes[slot]}
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
                  <span className="target-label">New recipes per week</span>
                  <span className="pantry-stepper">
                    <button
                      className="stepper-btn"
                      aria-label="Less novelty"
                      onClick={() => setNovelty((n) => Math.max(0, n - 5))}
                    >
                      −
                    </button>
                    <span className="target-value">
                      <span className="mp-num" style={{ fontSize: 16 }}>
                        {novelty}%
                      </span>
                    </span>
                    <button
                      className="stepper-btn"
                      aria-label="More novelty"
                      onClick={() => setNovelty((n) => Math.min(100, n + 5))}
                    >
                      +
                    </button>
                  </span>
                </div>
                <div className="target-row">
                  <span className="target-label">Batch cooking</span>
                  <button
                    type="button"
                    className={`switch${batchCooking ? " on" : ""}`}
                    role="switch"
                    aria-checked={batchCooking}
                    aria-label="Batch cooking preferred"
                    onClick={() => setBatchCooking((b) => !b)}
                  >
                    <span className="switch-knob" />
                  </button>
                </div>
              </div>
              <div className="grocery-footnote" style={{ marginTop: 10 }}>
                Slot defaults were created with the household — this step only
                touches your personal lifestyle config (expectedVersion 0).
              </div>
              <GapFootnote />
            </>
          )}

          {step === 4 && (
            <>
              <span className="mp-serif" style={{ fontSize: 24 }}>
                Targets, suggested from your goal.
              </span>
              <div style={{ display: "flex", gap: 10, alignItems: "center", marginTop: 12 }}>
                <select
                  className="time-select"
                  value={goal}
                  onChange={(e) => {
                    setGoal(e.target.value as typeof goal);
                    setCalorieTweak(0);
                  }}
                  aria-label="Goal"
                >
                  <option value="maintain">Maintain</option>
                  <option value="lose">Lose weight</option>
                  <option value="gain">Build muscle</option>
                </select>
                <span className="pantry-stepper">
                  <button
                    className="stepper-btn"
                    aria-label="Fewer calories"
                    onClick={() => setCalorieTweak((t) => t - 50)}
                  >
                    −
                  </button>
                  <span className="target-value">
                    <span className="mp-num" style={{ fontSize: 16 }}>
                      {finalCalories}
                    </span>
                  </span>
                  <button
                    className="stepper-btn"
                    aria-label="More calories"
                    onClick={() => setCalorieTweak((t) => t + 50)}
                  >
                    +
                  </button>
                </span>
              </div>
              <div style={{ marginTop: 14 }}>
                <StatStrip
                  numeralSize={20}
                  compact
                  cells={[
                    { label: "Protein", value: `${suggestion.proteinG} g` },
                    { label: "Carbs", value: `${suggestion.carbsG} g` },
                    { label: "Fat", value: `${suggestion.fatG} g` },
                    { label: "Fibre", value: `${suggestion.fibreG} g` },
                  ]}
                />
              </div>
              <div className="grocery-footnote" style={{ marginTop: 12 }}>
                POSTs targets/initialise with the full aggregate incl. a
                per-meal distribution (expectedVersion 0 — the create reuses
                the update shape, spec §5 G2). Micronutrients are omitted —
                the server DRI-seeds them. Already initialised → 409, the
                wizard just moves on. Current store targets:{" "}
                {targets ? `${targets.calories.dailyTarget} kcal` : "none yet"}.
              </div>
            </>
          )}
        </div>

        <div className="modal-actions" style={{ marginTop: 24 }}>
          {step > 0 && (
            <button className="btn" onClick={() => advance(step - 1)}>
              Back
            </button>
          )}
          {step === 0 && (
            <button
              className="btn btn-primary"
              onClick={joinMode ? submitJoin : submitHousehold}
              disabled={joinMode ? !joinCode.trim() : !name.trim()}
            >
              {joinMode ? "Join household" : "Create household"}
            </button>
          )}
          {step === 1 && (
            <button className="btn btn-primary" onClick={() => advance(2)}>
              {inviteCode ? "Next" : "Just me — next"}
            </button>
          )}
          {step === 2 && (
            <button className="btn btn-primary" onClick={submitConstraints}>
              Save & next
            </button>
          )}
          {step === 3 && (
            <button className="btn btn-primary" onClick={submitLifestyle}>
              Save & next
            </button>
          )}
          {step === 4 && (
            <>
              <button className="btn" onClick={finish}>
                Skip — set targets later
              </button>
              <button className="btn btn-primary" onClick={submitTargets}>
                Initialise targets & finish
              </button>
            </>
          )}
        </div>

        <div style={{ marginTop: 14, fontSize: 12.5 }}>
          <Link to="/" className="back-link" onClick={() => exitOnboarding()}>
            Exit setup →
          </Link>
        </div>
      </div>
    </div>
  );
}
