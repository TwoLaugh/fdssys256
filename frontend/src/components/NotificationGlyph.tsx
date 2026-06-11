import {
  AlertTriangle,
  BookOpen,
  CalendarDays,
  Clock3,
  ShoppingBasket,
  Sparkles,
  Truck,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { NotificationKind } from "../mock/types";

/**
 * Kind → icon + colour for notification rows. Colour semantics follow the
 * design language: expiry red (danger), pantry/defrost amber (time-
 * sensitive), suggestions terracotta, system kinds muted.
 */
export const KIND_META: Record<
  NotificationKind,
  { icon: LucideIcon; color: string; label: string }
> = {
  expiry: { icon: AlertTriangle, color: "var(--mp-red)", label: "Expiry alerts" },
  pantry: { icon: Clock3, color: "var(--mp-amber)", label: "Pantry & defrost" },
  ai: { icon: Sparkles, color: "var(--mp-terra)", label: "Advisor suggestions" },
  recipe: { icon: BookOpen, color: "var(--mp-terra)", label: "Recipe changes" },
  plan: { icon: CalendarDays, color: "var(--mp-muted)", label: "Plan updates" },
  grocery: {
    icon: ShoppingBasket,
    color: "var(--mp-muted)",
    label: "Groceries",
  },
  order: { icon: Truck, color: "var(--mp-muted)", label: "Orders" },
};

export function NotificationGlyph({ kind }: { kind: NotificationKind }) {
  const meta = KIND_META[kind];
  const Icon = meta.icon;
  return (
    <span className="notif-glyph" style={{ color: meta.color }}>
      <Icon size={16} strokeWidth={1.9} />
    </span>
  );
}

/** Day bucket for grouping rows — derived from the display time string. */
export function dayGroup(time: string): "Today" | "Yesterday" | "Earlier" {
  if (time.startsWith("Today") || time === "Just now") return "Today";
  if (time.startsWith("Yesterday")) return "Yesterday";
  return "Earlier";
}
