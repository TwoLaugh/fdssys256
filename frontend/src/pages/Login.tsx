import { Link, useNavigate } from "react-router-dom";

/** Decorative login card — no real auth in the mock; Continue just enters. */
export function Login() {
  const navigate = useNavigate();
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
        <form
          style={{ display: "grid", gap: 10, marginTop: 22 }}
          onSubmit={(e) => {
            e.preventDefault();
            navigate("/");
          }}
        >
          <input
            type="email"
            className="text-input"
            placeholder="Email"
            defaultValue="irenveer@gmail.com"
            aria-label="Email"
          />
          <input
            type="password"
            className="text-input"
            placeholder="Password"
            aria-label="Password"
          />
          <button type="submit" className="btn btn-primary">
            Continue
          </button>
        </form>
        <div style={{ marginTop: 16, fontSize: 13 }}>
          <Link to="/onboarding" className="back-link">
            New here? Set up your household →
          </Link>
        </div>
        <div className="grocery-footnote" style={{ marginTop: 18 }}>
          Mock build — no real authentication.
        </div>
      </div>
    </div>
  );
}
