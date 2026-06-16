/**
 * Minimal same-origin API client for live mode.
 *
 * Requests go to same-origin paths (`/api/v1/...`); the Vite dev server proxies
 * them to the Spring Boot backend (see vite.config.ts). Same-origin means the
 * session cookie flows automatically with `credentials: "include"` and there is
 * no CORS dependency on the backend profile.
 */

export class LiveApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
    message: string,
  ) {
    super(message);
    this.name = "LiveApiError";
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let parsed: unknown = null;
    try {
      parsed = await res.json();
    } catch {
      // non-JSON error body
    }
    throw new LiveApiError(res.status, parsed, `${method} ${path} -> ${res.status}`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const apiGet = <T>(path: string): Promise<T> => request<T>("GET", path);

export const apiSend = <T>(
  method: "POST" | "PATCH" | "PUT" | "DELETE",
  path: string,
  body?: unknown,
): Promise<T> => request<T>(method, path, body);

/**
 * GET that maps a 404 to `null` — the contract returns 404 for the many
 * "not initialised yet" empty states (no targets, no budget, no intake day),
 * which Today renders as empty cards rather than errors.
 */
export async function apiGetOrNull<T>(path: string): Promise<T | null> {
  try {
    return await apiGet<T>(path);
  } catch (e) {
    if (e instanceof LiveApiError && e.status === 404) return null;
    throw e;
  }
}
