import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { LIVE } from "./live/flag";
import { hydrateToday } from "./live/hydrate";
import { ensureDevSession } from "./live/session";
import "./styles/global.css";

const container = document.getElementById("root");
if (!container) {
  throw new Error("Root element #root not found");
}

function mount() {
  createRoot(container!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

if (LIVE) {
  // Live mode: fill the store from the real backend before first render, so the
  // page paints real data rather than fixtures-then-flash. A plain DOM splash
  // covers the (sub-second) fetch; createRoot clears it on mount.
  container.innerHTML =
    '<div style="padding:48px;font-family:system-ui,sans-serif;color:#6b6256">Loading your kitchen…</div>';
  ensureDevSession()
    .then(hydrateToday)
    .then(mount)
    .catch((e: unknown) => {
      console.error("Live hydration failed", e);
      container.innerHTML =
        '<div style="padding:48px;font-family:system-ui,sans-serif;color:#a23b2e">' +
        "Couldn't reach the backend on :8080. Is it running?<br/><br/>" +
        `<code>${String(e)}</code></div>`;
    });
} else {
  mount();
}
