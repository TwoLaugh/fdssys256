import {
  Activity,
  BookOpen,
  CalendarDays,
  Compass,
  History,
  Package,
  Settings,
  ShoppingBasket,
  SlidersHorizontal,
  Sun,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { NavLink } from "react-router-dom";
import { BellMenu } from "./BellMenu";

interface RailItem {
  to: string;
  label: string;
  icon: LucideIcon;
}

const MAIN_ITEMS: RailItem[] = [
  { to: "/", label: "Today", icon: Sun },
  { to: "/plan", label: "Plan", icon: CalendarDays },
  { to: "/recipes", label: "Recipes", icon: BookOpen },
  { to: "/discover", label: "Discover", icon: Compass },
  { to: "/groceries", label: "Groceries", icon: ShoppingBasket },
  { to: "/pantry", label: "Pantry", icon: Package },
  { to: "/nutrition", label: "Nutrition", icon: Activity },
  { to: "/preferences", label: "Preferences", icon: SlidersHorizontal },
  { to: "/activity", label: "Activity", icon: History },
];

function RailLink({ item }: { item: RailItem }) {
  const Icon = item.icon;
  return (
    <NavLink
      to={item.to}
      end={item.to === "/"}
      className={({ isActive }) => `rail-item${isActive ? " active" : ""}`}
      title={item.label}
      aria-label={item.label}
    >
      <Icon size={19} strokeWidth={1.8} />
    </NavLink>
  );
}

export function Rail({ unreadCount }: { unreadCount: number }) {
  return (
    <nav className="rail" aria-label="Primary">
      {MAIN_ITEMS.map((item) => (
        <RailLink key={item.to} item={item} />
      ))}
      <div className="rail-spacer" />
      <BellMenu badge={unreadCount} />
      <RailLink item={{ to: "/settings", label: "Settings", icon: Settings }} />
    </nav>
  );
}
