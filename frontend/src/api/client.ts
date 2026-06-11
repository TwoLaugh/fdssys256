/**
 * Tiny typed fetch wrapper for the MealPrep backend.
 *
 * - Base URL comes from VITE_API_BASE (default http://localhost:8080).
 * - Session-cookie auth: every request is sent with credentials included.
 * - JSON in / JSON out; non-2xx responses throw a typed ApiError.
 */

export class ApiError extends Error {
  readonly status: number;
  readonly statusText: string;
  /** Parsed JSON error body when the backend sent one, otherwise null. */
  readonly body: unknown;

  constructor(status: number, statusText: string, body: unknown) {
    super(`API request failed: ${status} ${statusText}`);
    this.name = "ApiError";
    this.status = status;
    this.statusText = statusText;
    this.body = body;
  }
}

export const API_BASE: string =
  import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
}

export async function api<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { method = "GET", body, signal } = options;

  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    credentials: "include",
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal,
  });

  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      // Non-JSON error body; keep null.
    }
    throw new ApiError(response.status, response.statusText, errorBody);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
