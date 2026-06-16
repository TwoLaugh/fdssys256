import { useEffect } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { probeAdmin, useStore } from "../mock/store";
import { FeedbackButton } from "./FeedbackButton";
import { Rail } from "./Rail";
import { ToastHost } from "./ToastHost";

/**
 * App shell: left icon rail, routed page content, floating feedback button.
 *
 * Owns the two cross-cutting probes the page specs delegate here:
 * - Session guard (login.md §5): the /auth/me boot probe — a 401 redirects
 *   to /login?next={path}; the login page itself never calls /me.
 * - Admin probe (admin.md §5): fire admin/status once per session, silently;
 *   200 reveals the hidden /admin nav entry, 403 hides it for the session.
 */
export function Shell() {
  const user = useStore((s) => s.session.user);
  const adminProbe = useStore((s) => s.admin.probeOutcome);
  const location = useLocation();

  useEffect(() => {
    if (user && adminProbe === null) probeAdmin();
  }, [user, adminProbe]);

  if (!user) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }

  return (
    <div className="app">
      <Rail showAdmin={adminProbe === "admin"} />
      <main className="app-main">
        <Outlet />
      </main>
      <FeedbackButton />
      <ToastHost />
    </div>
  );
}
