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
  WEEK_DATES as FIXTURE_WEEK_DATES,
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
