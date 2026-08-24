/**
 * Date anchors for the Today page, live-aware.
 *
 * The mock fixtures are pinned to a fixed week (`2026-06-08`..). The real
 * backend serves a real-time plan, so in live mode these anchors resolve to the
 * actual clock — that's what makes `activePlanForWeek(CURRENT_WEEK_START)` find
 * the live ACTIVE plan and `intakeDays[MOCK_TODAY_ISO]` line up with today.
 *
 * Re-exported under the same names the mock seeds use, so consuming pages only
 * swap their import source — no logic change.
 */
import { LIVE } from "./flag";
import {
  MOCK_TODAY_ISO as FIXTURE_TODAY,
  TODAY_INDEX as FIXTURE_TODAY_INDEX,
  WEEK_DATES as FIXTURE_WEEK_DATES,
  WEEK_DAY_LABELS as FIXTURE_WEEK_DAY_LABELS,
} from "../mock/nutritionSeed";
import { CURRENT_WEEK_START as FIXTURE_WEEK_START } from "../mock/plannerSeed";

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

function iso(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Monday of the week containing `d` (backend weeks start Monday). */
function mondayOf(d: Date): Date {
  const copy = new Date(d);
  const offset = (copy.getDay() + 6) % 7; // Mon=0 .. Sun=6
  copy.setDate(copy.getDate() - offset);
  copy.setHours(0, 0, 0, 0);
  return copy;
}

function realWeekDates(): string[] {
  const mon = mondayOf(new Date());
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(mon);
    d.setDate(mon.getDate() + i);
    return iso(d);
  });
}

const liveWeek = LIVE ? realWeekDates() : null;

/** Mon..Sun ISO dates of the active week. */
export const WEEK_DATES: string[] = liveWeek ?? FIXTURE_WEEK_DATES;

/** Monday of the active week (matches the backend plan's weekStartDate). */
export const CURRENT_WEEK_START: string = liveWeek ? liveWeek[0] : FIXTURE_WEEK_START;

/** "Today" the timeline + intake lookups key off. */
export const MOCK_TODAY_ISO: string = liveWeek ? iso(new Date()) : FIXTURE_TODAY;

/** Static Mon..Sun labels (same in both modes). */
export const WEEK_DAY_LABELS: string[] = FIXTURE_WEEK_DAY_LABELS;

/** Index of "today" within WEEK_DATES (day strips default to this). */
export const TODAY_INDEX: number = liveWeek
  ? Math.max(0, WEEK_DATES.indexOf(MOCK_TODAY_ISO))
  : FIXTURE_TODAY_INDEX;

/** "Now" in epoch-ms for expiry/countdown math (real clock in live mode). */
export const MOCK_NOW_MS: number = liveWeek
  ? Date.now()
  : Date.parse(`${FIXTURE_TODAY}T18:00:00Z`);
