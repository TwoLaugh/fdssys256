/**
 * Invite accept — a standalone deep-link surface (settings.md §3d), NOT part
 * of /settings: the accepter by definition has no household yet, so the
 * settings scaffold (households/current) is the wrong host. Reached from a
 * shared link (/invite?code=…) while logged in; the shell guard handles the
 * 401 → /login?next= redirect.
 *
 * POST /invites/accept returns the accepter's new MEMBERSHIP, not the
 * household — follow with GET /households/current to render it.
 */

import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { acceptInvite, pushToast, useStore } from "../mock/store";
import type { AcceptInviteOutcome } from "../mock/store";

/** §3d status ladder → copy. */
const OUTCOME_COPY: Record<Exclude<AcceptInviteOutcome, "ok">, string> = {
  badRequest: "Enter the code you were sent.",
  forbidden: "This invite was issued for a different account.",
  notFound: "Code not recognised — check for typos.",
  alreadyInHousehold:
    "You're already in a household — leave it first (Settings → Leave household).",
  gone: "This invite expired or was revoked — ask for a new one.",
};

export function InviteAccept() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const householdName = useStore((s) => s.household.current?.name ?? null);
  const [code, setCode] = useState(params.get("code") ?? "");
  const [error, setError] = useState<string | null>(null);

  const submit = () => {
    setError(null);
    const outcome = acceptInvite(code);
    if (outcome === "ok") {
      pushToast("Invite accepted — welcome!");
      navigate("/settings");
      return;
    }
    setError(OUTCOME_COPY[outcome]);
  };

  return (
    <div style={{ display: "grid", placeItems: "start center", paddingTop: 60 }}>
      <div className="auth-card mp-card">
        <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
          Join a household
        </span>
        <div style={{ marginTop: 10 }}>
          <span className="mp-serif" style={{ fontSize: 26 }}>
            Someone saved you a seat at the table.
          </span>
        </div>
        <form
          style={{ display: "grid", gap: 10, marginTop: 20 }}
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <input
            className="text-input"
            placeholder="Invite code, e.g. MP-XXXX-XXXX"
            value={code}
            maxLength={32}
            onChange={(e) => setCode(e.target.value)}
            aria-label="Invite code"
          />
          {error && (
            <div style={{ color: "var(--mp-red)", fontSize: 13 }}>{error}</div>
          )}
          <button type="submit" className="btn btn-primary" disabled={!code.trim()}>
            Accept invite
          </button>
        </form>
        {householdName && (
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            You're currently in “{householdName}” — accepting another invite
            will be refused (409, one household per user in v1).
          </div>
        )}
        <div style={{ marginTop: 14, fontSize: 13 }}>
          <Link to="/settings" className="back-link">
            ← Back to settings
          </Link>
        </div>
      </div>
    </div>
  );
}
