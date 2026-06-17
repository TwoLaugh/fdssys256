/**
 * Live (real-backend) plan-generation flow — the first live WRITE path (the rest of the live layer
 * is read-only hydration). Mirrors the async backend: submit returns a RUNNING job immediately, the
 * UI shows a processing state, and we poll the job until it is terminal so the user is never blocked
 * on the multi-second Stage-A→D run.
 */
import { apiGet, apiSend } from "./client";

export type PlanGenerationStatus = "RUNNING" | "COMPLETED" | "FAILED";

export interface PlanGenerationJob {
  jobId: string;
  status: PlanGenerationStatus;
  planId: string | null;
  errorCode: string | null;
  householdId: string;
  weekStartDate: string;
  replayed: boolean;
}

/** Schedule a generation; resolves the instant the backend accepts it (202 + RUNNING job). */
export function submitGeneration(
  householdId: string,
  weekStartDate: string,
  forceRegenerateIfActive: boolean,
  idempotencyKey: string,
): Promise<PlanGenerationJob> {
  return apiSendWithKey<PlanGenerationJob>(
    "/api/v1/plans/generate/async",
    { householdId, weekStartDate, forceRegenerateIfActive },
    idempotencyKey,
  );
}

/**
 * Poll the job until COMPLETED or FAILED, surfacing each tick to {@code onTick} so the UI can show
 * progress. Bounded by {@code maxMs} (default 4 min) so a wedged job can't poll forever.
 */
export async function pollGeneration(
  jobId: string,
  onTick: (job: PlanGenerationJob) => void,
  intervalMs = 2000,
  maxMs = 240_000,
): Promise<PlanGenerationJob> {
  const deadline = Date.now() + maxMs;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const job = await apiGet<PlanGenerationJob>(
      `/api/v1/plans/generate/jobs/${jobId}`,
    );
    onTick(job);
    if (job.status === "COMPLETED" || job.status === "FAILED") return job;
    if (Date.now() > deadline) {
      return { ...job, status: "FAILED", errorCode: "timeout" };
    }
    await sleep(intervalMs);
  }
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** POST that also sends an Idempotency-Key header (apiSend doesn't take custom headers). */
async function apiSendWithKey<T>(
  path: string,
  body: unknown,
  idempotencyKey: string,
): Promise<T> {
  const res = await fetch(path, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let parsed: unknown = null;
    try {
      parsed = await res.json();
    } catch {
      /* non-JSON */
    }
    throw new Error(`POST ${path} -> ${res.status} ${JSON.stringify(parsed)}`);
  }
  return (await res.json()) as T;
}

// apiSend is re-exported for callers that want the plain helper without a key.
export { apiSend };
