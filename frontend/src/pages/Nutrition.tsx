/**
 * Nutrition page — rebuilt against the contract-complete page spec
 * (design/frontend/pages/nutrition.md). Four tabs over production DTO
 * shapes: Overview · Targets · Directives · Data quality.
 */

import { useState } from "react";
import { PageHeader } from "../components/PageHeader";
import { useStore } from "../mock/store";
import { DataQualityTab } from "./nutrition/DataQualityTab";
import { DirectivesTab } from "./nutrition/DirectivesTab";
import { OverviewTab } from "./nutrition/OverviewTab";
import { TargetsTab } from "./nutrition/TargetsTab";

const TABS = ["Overview", "Targets", "Directives", "Data quality"] as const;
type Tab = (typeof TABS)[number];

export function Nutrition() {
  const [tab, setTab] = useState<Tab>("Overview");
  const pendingDirectives = useStore(
    (s) =>
      s.nutrition.directives.filter((d) => d.status === "PENDING_REVIEW")
        .length,
  );
  const needsReview = useStore(
    (s) => s.nutrition.ingredientCache.filter((r) => r.needsReview).length,
  );

  const badge = (t: Tab): string => {
    if (t === "Directives" && pendingDirectives > 0)
      return ` · ${pendingDirectives}`;
    if (t === "Data quality" && needsReview > 0) return ` · ${needsReview}`;
    return "";
  };

  return (
    <div>
      <PageHeader
        title="Nutrition"
        meta="Planned vs actual intake · targets in absolute grams · directives reviewed, never auto-applied"
      />
      <div className="nutri-tabs" role="tablist" aria-label="Nutrition sections">
        {TABS.map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={`filter-chip${tab === t ? " active" : ""}`}
            onClick={() => setTab(t)}
          >
            {t}
            {badge(t)}
          </button>
        ))}
      </div>
      {tab === "Overview" && <OverviewTab />}
      {tab === "Targets" && <TargetsTab />}
      {tab === "Directives" && <DirectivesTab />}
      {tab === "Data quality" && <DataQualityTab />}
    </div>
  );
}
