/**
 * Data-source selection.
 *
 * Mock (fixture) mode is the default whenever VITE_API_BASE is unset so the
 * app runs with zero backend. Point VITE_API_BASE at a running backend
 * (e.g. http://localhost:8080) to use the live API with session-cookie auth.
 */

import { liveTodayApi } from "./live";
import { mockTodayApi } from "./mock";
import type { TodayDataSource } from "./today";

export const useMock: boolean = !import.meta.env.VITE_API_BASE;

export const todayApi: TodayDataSource = useMock ? mockTodayApi : liveTodayApi;

export { ApiError } from "./client";
export * from "./today";
