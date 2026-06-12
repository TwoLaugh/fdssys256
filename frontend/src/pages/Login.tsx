/**
 * Login page — rebuilt against the contract-complete page spec
 * (design/frontend/pages/login.md). One card, two modes (sign in / create
 * account). Generic 401 (no enumeration oracle), 409 username-taken,
 * 423 lockout + 429 throttle with Retry-After countdowns, register
 * auto-login → /onboarding. The /auth/me session probe is the SHELL's
 * concern (§5) — this page never calls it. No "forgot password": account
 * recovery explicitly does not exist in v1 (GAP-82).
 *
 * Mock demo paths: a username containing "locked" → 423; five consecutive
 * failures → 429; seeded credentials iren / plan-the-week-12.
 */

import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { login, register, useStore } from "../mock/store";

const USERNAME_PATTERN = /^[a-zA-Z0-9_-]{3,32}$/;

function useCountdown(until: number | null): number {
  const [, tick] = useState(0);
  useEffect(() => {
    if (!until) return;
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [until]);
  if (!until) return 0;
  return Math.max(0, Math.ceil((until - Date.now()) / 1000));
}

export function Login() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const sessionUser = useStore((s) => s.session.user);
  const [mode, setMode] = useState<"signin" | "register">("signin");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [usernameError, setUsernameError] = useState<string | null>(null);
  const [lockedUntil, setLockedUntil] = useState<number | null>(null);
  const [lockCopy, setLockCopy] = useState<string>("");

  const next = params.get("next");
  const remaining = useCountdown(lockedUntil);
  const locked = remaining > 0;

  useEffect(() => {
    if (!locked && lockedUntil) {
      setLockedUntil(null);
      setError(null);
    }
  }, [locked, lockedUntil]);

  const registerValid =
    USERNAME_PATTERN.test(username.trim()) &&
    password.length >= 12 &&
    password.length <= 128 &&
    password === confirm;

  const submit = () => {
    setError(null);
    setUsernameError(null);
    if (mode === "register") {
      // Pre-catch the 400s client-side (§3b): pattern + length only — length
      // is the only server password rule, so no complexity meter.
      if (!registerValid) return;
      const outcome = register(username, password);
      if (outcome === "taken") {
        setUsernameError("That username is taken.");
        return;
      }
      // 201 carries the session cookie — auto-login, then onboarding (a
      // fresh account by definition has no household).
      navigate("/onboarding");
      return;
    }
    const outcome = login(username, password);
    switch (outcome.kind) {
      case "ok":
        navigate(next && next.startsWith("/") ? next : "/");
        return;
      case "invalid":
        // ONE generic message — never "no such user" (no enumeration oracle).
        setError("Username or password is incorrect.");
        return;
      case "locked":
        setLockedUntil(Date.now() + outcome.retryAfterS * 1000);
        setLockCopy("Account temporarily locked after repeated failures");
        return;
      case "throttled":
        setLockedUntil(Date.now() + outcome.retryAfterS * 1000);
        setLockCopy("Too many attempts");
        return;
    }
  };

  return (
    <div className="auth-wrap">
      <div className="auth-card mp-card">
        <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
          MealPrep
        </span>
        <div style={{ marginTop: 10 }}>
          <span className="mp-serif" style={{ fontSize: 30 }}>
            Plan the week, then stop thinking about it.
          </span>
        </div>

        <div className="filter-row" style={{ marginTop: 18 }}>
          <button
            className={`filter-chip${mode === "signin" ? " active" : ""}`}
            onClick={() => {
              setMode("signin");
              setError(null);
              setUsernameError(null);
            }}
          >
            Sign in
          </button>
          <button
            className={`filter-chip${mode === "register" ? " active" : ""}`}
            onClick={() => {
              setMode("register");
              setError(null);
              setUsernameError(null);
            }}
          >
            Create account
          </button>
        </div>

        <form
          style={{ display: "grid", gap: 10, marginTop: 16 }}
          onSubmit={(e) => {
            e.preventDefault();
            if (!locked) submit();
          }}
        >
          <div>
            <input
              type="text"
              className="text-input"
              style={{ width: "100%" }}
              placeholder="Username"
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                setUsernameError(null);
              }}
              aria-label="Username"
              autoComplete="username"
            />
            {mode === "register" &&
              username.length > 0 &&
              !USERNAME_PATTERN.test(username.trim()) && (
                <div style={{ color: "var(--mp-amber)", fontSize: 12.5, marginTop: 4 }}>
                  3–32 characters; letters, digits, _ or - only.
                </div>
              )}
            {usernameError && (
              <div style={{ color: "var(--mp-red)", fontSize: 12.5, marginTop: 4 }}>
                {usernameError}
              </div>
            )}
          </div>
          <div>
            <input
              type="password"
              className="text-input"
              style={{ width: "100%" }}
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-label="Password"
              autoComplete={mode === "register" ? "new-password" : "current-password"}
            />
            {mode === "register" && (
              <div
                style={{
                  color: password.length >= 12 ? "var(--mp-olive)" : "var(--mp-muted)",
                  fontSize: 12.5,
                  marginTop: 4,
                }}
              >
                12 characters minimum.
              </div>
            )}
          </div>
          {mode === "register" && (
            <div>
              <input
                type="password"
                className="text-input"
                style={{ width: "100%" }}
                placeholder="Repeat password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                aria-label="Repeat password"
                autoComplete="new-password"
              />
              {confirm.length > 0 && confirm !== password && (
                <div style={{ color: "var(--mp-amber)", fontSize: 12.5, marginTop: 4 }}>
                  Passwords don't match.
                </div>
              )}
            </div>
          )}

          {error && (
            <div style={{ color: "var(--mp-red)", fontSize: 13 }}>{error}</div>
          )}
          {locked && (
            <div style={{ color: "var(--mp-red)", fontSize: 13 }}>
              {lockCopy} — try again in {remaining}s.
            </div>
          )}

          <button
            type="submit"
            className="btn btn-primary"
            disabled={locked || (mode === "register" && !registerValid)}
          >
            {locked
              ? `Try again in ${remaining}s`
              : mode === "register"
                ? "Create account"
                : "Continue"}
          </button>
        </form>

        {sessionUser && (
          <div style={{ marginTop: 14, fontSize: 13 }}>
            <Link to={next && next.startsWith("/") ? next : "/"} className="back-link">
              Signed in as {sessionUser.username} — continue →
            </Link>
          </div>
        )}

        <div className="grocery-footnote" style={{ marginTop: 16 }}>
          No password recovery exists in v1 (GAP-82) — nothing to render, not
          a dead link. Mock demo: username containing “locked” → 423; seeded
          sign-in iren / plan-the-week-12.
        </div>
      </div>
    </div>
  );
}
