/**
 * View-model types for the Today page, plus the data-source contract that
 * both the fixture (mock) and live implementations fulfil. The shape mirrors
 * the handful of backend calls Today needs: active plan with today's slots,
 * nutrition intake vs targets, notifications summary, week budget, and the
 * top pending change suggestion.
 */

export type MealStatus = "planned" | "cooked" | "eaten";

export interface TodayMeal {
  /** Wall-clock slot time, e.g. "08:00". */
  time: string;
  /** Slot name, e.g. "breakfast". */
  slot: string;
  name: string;
  /** Secondary line, e.g. "Just you · 380 kcal". */
  meta: string;
  status: MealStatus;
  /** Linked to a batch-cook. */
  batch?: boolean;
  /** Action button label ("Start cooking" / "Mark eaten"); none when eaten. */
  action?: string;
  /** Time-sensitive alert, e.g. "Defrost tofu by 15:00". */
  alert?: string;
}

export interface PlanToday {
  /** e.g. "Wednesday 10 June" */
  dateLabel: string;
  /** e.g. "week plan day 4 of 7" */
  progressLabel: string;
  /** Advisor-voice greeting, e.g. "Good evening, Iren" */
  greeting: string;
  planActive: boolean;
  meals: TodayMeal[];
}

export interface NutritionStat {
  label: string;
  /** Current intake in the stat's unit. */
  value: number;
  /** Daily target in the same unit. */
  target: number;
  /** Formatted intake, e.g. "1,420". */
  display: string;
  /** Formatted target including unit, e.g. "2,000" or "120 g". */
  targetDisplay: string;
  /** Time-adjusted pacing flag: behind where you should be by now. */
  behind?: boolean;
}

export type AttentionKind = "expiry" | "defrost" | "ai";

export interface AttentionItem {
  kind: AttentionKind;
  text: string;
}

export interface NotificationsSummary {
  unread: number;
  attention: AttentionItem[];
}

export interface WeekBudget {
  /** e.g. "£38.20" */
  spentDisplay: string;
  /** e.g. "£55" */
  totalDisplay: string;
  /** 0–100 */
  pct: number;
  /** e.g. "On track · 3 days left" */
  note: string;
}

export interface AdvisorSuggestion {
  /** Kicker label, e.g. "Suggestion · from your feedback". */
  label: string;
  /** Advisor-voice (serif italic) title. */
  title: string;
  /** Supporting line. */
  sub: string;
}

/** The handful of calls the Today page composes. */
export interface TodayDataSource {
  getActivePlanToday(): Promise<PlanToday>;
  getNutritionToday(): Promise<NutritionStat[]>;
  getNotificationsSummary(): Promise<NotificationsSummary>;
  getWeekBudget(): Promise<WeekBudget>;
  getTopPendingChange(): Promise<AdvisorSuggestion | null>;
}
