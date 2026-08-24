import type { ReactNode } from "react";

/**
 * Standard page header: Schibsted display title with an optional chip
 * beside it, a muted meta line beneath, and right-aligned actions.
 */
export function PageHeader({
  title,
  chip,
  meta,
  actions,
}: {
  title: string;
  chip?: ReactNode;
  meta?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-header">
      <div>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <h1 className="page-title">{title}</h1>
          {chip}
        </div>
        {meta && <div className="page-meta">{meta}</div>}
      </div>
      {actions && <div style={{ display: "flex", gap: 10 }}>{actions}</div>}
    </div>
  );
}
