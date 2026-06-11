import { useEffect, useState } from "react";
import { Outlet } from "react-router-dom";
import { todayApi } from "../api";
import { FeedbackButton } from "./FeedbackButton";
import { Rail } from "./Rail";

/** App shell: left icon rail, routed page content, floating feedback button. */
export function Shell() {
  const [unreadCount, setUnreadCount] = useState(0);

  useEffect(() => {
    let cancelled = false;
    todayApi
      .getNotificationsSummary()
      .then((summary) => {
        if (!cancelled) setUnreadCount(summary.unread);
      })
      .catch(() => {
        // Badge is best-effort; the bell still navigates.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="app">
      <Rail unreadCount={unreadCount} />
      <main className="app-main">
        <Outlet />
      </main>
      <FeedbackButton />
    </div>
  );
}
