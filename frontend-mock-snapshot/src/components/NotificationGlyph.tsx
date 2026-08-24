import {
  AlertTriangle,
  Check,
  ChefHat,
  Hourglass,
  HeartPulse,
  LayoutGrid,
  PencilLine,
  PieChart,
  Rows3,
  Snowflake,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { MOCK_NOW_MS } from "../mock/settingsAdminSeed";
import type { AnyNotificationKind, NotificationSeverity } from "../mock/types";

/**
 * Kind → icon / colour / label per the spec's §3c mapping table
 * (design/frontend/pages/notifications.md). D6 semantics: red only where the
 * thing itself is a harm (spoiled); amber = time-sensitive; terra = the
 * system suggests / you act; olive = done/confirmed.
 *
 * The last two kinds exist in the Java enum but NOT in the OpenAPI contract
 * (spec §8 Q1) — `gap: true` drives the page footnote.
 */
export const KIND_META: Record<
  AnyNotificationKind,
  { icon: LucideIcon; color: string; label: string; gap?: boolean }
> = {
  PROVISION_ITEM_NEAR_EXPIRY: {
    icon: Hourglass,
    color: "var(--mp-amber)",
    label: "Expiring soon",
  },
  PROVISION_ITEM_SPOILED: {
    icon: AlertTriangle,
    color: "var(--mp-red)",
    label: "Spoiled",
  },
  PROVISION_DEFROST_REMINDER: {
    icon: Snowflake,
    color: "var(--mp-amber)",
    label: "Defrost reminder",
  },
  NUTRITION_INTAKE_DIVERGED: {
    icon: PieChart,
    color: "var(--mp-amber)",
    label: "Intake diverged",
  },
  HEALTH_DIRECTIVE_RECEIVED: {
    icon: HeartPulse,
    color: "var(--mp-red)",
    label: "Health directive",
  },
  PLANNER_PREP_REMINDER: {
    icon: ChefHat,
    color: "var(--mp-amber)",
    label: "Prep reminder",
  },
  PLANNER_REOPT_SUGGESTED: {
    icon: PencilLine,
    color: "var(--mp-terra)",
    label: "Plan fix suggested",
  },
  PLANNER_PLAN_GENERATED: {
    icon: LayoutGrid,
    color: "var(--mp-olive)",
    label: "Plan generated",
  },
  STAPLE_REPLENISHMENT_NEEDED: {
    icon: Rows3,
    color: "var(--mp-terra)",
    label: "Staple running low",
    gap: true,
  },
  FEEDBACK_CONFIRMATION: {
    icon: Check,
    color: "var(--mp-olive)",
    label: "Feedback applied",
    gap: true,
  },
};

export function NotificationGlyph({ kind }: { kind: AnyNotificationKind }) {
  const meta = KIND_META[kind];
  const Icon = meta.icon;
  return (
    <span className="notif-glyph" style={{ color: meta.color }}>
      <Icon size={16} strokeWidth={1.9} />
    </span>
  );
}

/** Severity → title colour accent (URGENT is the only red on the page). */
export const SEVERITY_COLOR: Record<NotificationSeverity, string | undefined> = {
  INFO: undefined,
  ATTENTION: "var(--mp-amber)",
  URGENT: "var(--mp-red)",
};

/**
 * The server's `actionTargetUri` values are `/app/...` paths that do NOT
 * match the IA routes (spec §8 Q2) — this is the client-side mapping layer
 * the spec calls for until the backend resolver copy is updated.
 */
const APP_URI_ROUTES: Array<[RegExp, string]> = [
  [/^\/app\/provisions\/inventory/, "/pantry"],
  [/^\/app\/nutrition\/health-directives\//, "/nutrition"],
  [/^\/app\/nutrition\/intake\//, "/nutrition"],
  [/^\/app\/planner\/slots\//, "/plan"],
  [/^\/app\/plans\//, "/plan"],
  [/^\/app\/feedback\//, "/activity"],
];

export function resolveActionTarget(uri: string | null | undefined): string | null {
  if (!uri) return null;
  for (const [pattern, route] of APP_URI_ROUTES) {
    if (pattern.test(uri)) return route;
  }
  return null; // unmapped /app/* namespace — render no dead link
}

/** Relative "2h ago" stamps against the mock's fixed now (Wed 10 Jun 18:00Z). */
export function relativeTime(iso: string): string {
  const deltaMin = Math.max(0, Math.round((MOCK_NOW_MS - Date.parse(iso)) / 60_000));
  if (deltaMin < 1) return "just now";
  if (deltaMin < 60) return `${deltaMin}m ago`;
  const hours = Math.floor(deltaMin / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? "yesterday" : `${days}d ago`;
}
