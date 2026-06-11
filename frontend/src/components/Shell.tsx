import { Outlet } from "react-router-dom";
import { selectUnreadCount, useStore } from "../mock/store";
import { FeedbackButton } from "./FeedbackButton";
import { Rail } from "./Rail";

/** App shell: left icon rail, routed page content, floating feedback button. */
export function Shell() {
  const unreadCount = useStore(selectUnreadCount);

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
