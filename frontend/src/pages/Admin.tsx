import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";

const STATUS_ROWS: Array<{ name: string; state: string; ok: boolean }> = [
  { name: "API", state: "Operational", ok: true },
  { name: "AI provider", state: "Operational · gpt-4.1-mini", ok: true },
  { name: "Job queue", state: "2 running · 0 stuck", ok: true },
  { name: "Price refresh", state: "Last run Tue 02:00 · 4 stale", ok: false },
];

/** Read-only system status card — allowlisted route, mock data. */
export function Admin() {
  return (
    <div>
      <PageHeader
        title="Admin"
        chip={<span className="mp-chip">Allowlisted</span>}
        meta="System status and AI cost summary · read-only in the mock"
      />

      <div style={{ marginTop: 24 }}>
        <StatStrip
          numeralSize={22}
          cells={[
            { label: "AI spend this month", value: "£4.12", sub: "cap £15.00" },
            { label: "AI calls", value: "1,284", sub: "30 days" },
            { label: "Avg latency", value: "1.9 s", sub: "p95 4.2 s" },
            { label: "Error rate", value: "0.4%", sub: "7-day window" },
          ]}
        />
      </div>

      <div className="mp-card side-card" style={{ marginTop: 22, maxWidth: 620 }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Subsystems
        </span>
        <div style={{ marginTop: 8 }}>
          {STATUS_ROWS.map((row) => (
            <div key={row.name} className="admin-status-row">
              <span
                style={{
                  color: row.ok ? "var(--mp-olive)" : "var(--mp-amber)",
                  fontWeight: 700,
                  width: 16,
                }}
              >
                {row.ok ? "✓" : "●"}
              </span>
              <span style={{ flex: 1, fontWeight: 600, fontSize: 14 }}>
                {row.name}
              </span>
              <span style={{ color: "var(--mp-muted)", fontSize: 13 }}>
                {row.state}
              </span>
            </div>
          ))}
        </div>
        <div className="grocery-footnote" style={{ marginTop: 14 }}>
          The call log and decision-log explorer land with live wiring.
        </div>
      </div>
    </div>
  );
}
