/**
 * Dev session bootstrap for live mode.
 *
 * The real app will have a login page (login.md); until that's wired, the live
 * preview needs a session so the API calls aren't all 401. In live mode we
 * probe /auth/me and, if anonymous, log in as the seeded dev user. Credentials
 * come from Vite env (VITE_DEV_USER / VITE_DEV_PASS) and default to the local
 * seed account — this is a DEV-ONLY convenience, never shipped.
 */
import { apiGet, apiSend, LiveApiError } from "./client";

const DEV_USER = (import.meta.env.VITE_DEV_USER as string) || "iren-demo";
const DEV_PASS = (import.meta.env.VITE_DEV_PASS as string) || "demo-password-123";

/** Ensure an authenticated session exists; logs in as the dev user if not. */
export async function ensureDevSession(): Promise<void> {
  try {
    await apiGet("/api/v1/auth/me");
    return; // already authenticated (cookie present)
  } catch (e) {
    if (!(e instanceof LiveApiError) || e.status !== 401) throw e;
  }
  await apiSend("POST", "/api/v1/auth/login", {
    username: DEV_USER,
    password: DEV_PASS,
  });
}
