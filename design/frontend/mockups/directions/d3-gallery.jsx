// Direction 3 — "Gallery": Swiss ultra-minimal. White, Schibsted Grotesk, hairline
// rules, oversized numerals, single red accent. Statuses as squares.
const D3 = {
  bg: "#fcfcfa",
  ink: "#141412",
  faint: "#97958e",
  line: "#e4e3dd",
  red: "#e8341c",
  fill: "#141412",
};

const d3Base = {
  fontFamily: "'Schibsted Grotesk', sans-serif",
  background: D3.bg,
  color: D3.ink,
  width: "100%",
  height: "100%",
  padding: "56px 64px",
  overflow: "hidden",
  position: "relative",
};

function D3Cap({ children, color = D3.faint, style = {} }) {
  return <span style={{ fontSize: 11, letterSpacing: "0.14em", textTransform: "uppercase", fontWeight: 700, color, ...style }}>{children}</span>;
}

function d3Sq(s) {
  // eaten = filled, cooked = half, planned = outline, affected = red
  const base = { width: 9, height: 9, display: "inline-block", flexShrink: 0 };
  if (s === "eaten") return <span style={{ ...base, background: D3.ink }}></span>;
  if (s === "cooked") return <span style={{ ...base, background: `linear-gradient(135deg, ${D3.ink} 50%, transparent 50%)`, border: `1.5px solid ${D3.ink}` }}></span>;
  if (s === "affected") return <span style={{ ...base, background: D3.red }}></span>;
  return <span style={{ ...base, border: `1.5px solid ${D3.faint}` }}></span>;
}

function d3Btn(primary) {
  return {
    fontFamily: "'Schibsted Grotesk', sans-serif", fontSize: 12.5, fontWeight: 700,
    letterSpacing: "0.08em", textTransform: "uppercase", padding: "11px 22px", cursor: "pointer",
    background: primary ? D3.ink : "transparent", color: primary ? D3.bg : D3.ink,
    border: `1.5px solid ${D3.ink}`, borderRadius: 0,
  };
}

function D3Week() {
  const W = window.MEAL.week;
  return (
    <div style={d3Base} data-screen-label="D3 Week plan">
      {/* Masthead */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <div style={{ display: "flex", alignItems: "baseline", gap: 20 }}>
            <span style={{ fontSize: 96, fontWeight: 800, letterSpacing: "-0.05em", lineHeight: 0.9 }}>8–14</span>
            <div>
              <div style={{ fontSize: 26, fontWeight: 700, letterSpacing: "-0.02em" }}>June</div>
              <D3Cap color={D3.red}>● Plan active</D3Cap>
            </div>
          </div>
          <div style={{ fontSize: 13.5, color: D3.faint, marginTop: 14 }}>This week’s plan · {W.meta}</div>
        </div>
        <div style={{ display: "flex", gap: 0 }}>
          <button style={{ ...d3Btn(false), borderRight: "none" }}>History</button>
          <button style={d3Btn(false)}>Re-optimise</button>
        </div>
      </div>

      {/* Stat strip */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", borderTop: `1.5px solid ${D3.ink}`, borderBottom: `1px solid ${D3.line}`, marginTop: 30 }}>
        {W.stats.map((s, i) => (
          <div key={i} style={{ padding: "18px 0 18px 0", paddingLeft: i ? 24 : 0, borderLeft: i ? `1px solid ${D3.line}` : "none" }}>
            <D3Cap color={s.warn ? D3.red : D3.faint}>{s.label}</D3Cap>
            <div style={{ fontSize: 24, fontWeight: 700, letterSpacing: "-0.02em", marginTop: 6, color: s.warn ? D3.red : D3.ink }}>{s.value}</div>
            {s.sub && <div style={{ fontSize: 12, color: D3.faint, marginTop: 3 }}>{s.sub}</div>}
          </div>
        ))}
      </div>

      {/* Fix proposal — red index card */}
      <div style={{ display: "grid", gridTemplateColumns: "10px 1fr", marginTop: 28, border: `1px solid ${D3.line}` }}>
        <div style={{ background: D3.red }}></div>
        <div style={{ padding: "22px 28px" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
            <D3Cap color={D3.red}>Suggested fix</D3Cap>
            <span style={{ fontSize: 12, color: D3.faint }}>{W.fix.sub}</span>
          </div>
          <div style={{ fontSize: 21, fontWeight: 700, letterSpacing: "-0.01em", marginTop: 8 }}>{W.fix.title}</div>
          <div style={{ marginTop: 16, display: "grid", gap: 9 }}>
            {W.fix.swaps.map((sw, i) => (
              <div key={i} style={{ display: "flex", alignItems: "baseline", gap: 14, fontSize: 15 }}>
                <D3Cap style={{ width: 86 }}>{sw.slot}</D3Cap>
                <span style={{ textDecoration: "line-through", color: D3.faint }}>{sw.from}</span>
                <span style={{ color: D3.red, fontWeight: 700 }}>→</span>
                <span style={{ fontWeight: 700 }}>{sw.to}</span>
                {sw.note && <span style={{ fontSize: 12, color: D3.faint }}>({sw.note})</span>}
              </div>
            ))}
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 18, borderTop: `1px solid ${D3.line}`, paddingTop: 16 }}>
            <span style={{ fontSize: 13, color: D3.faint, fontWeight: 500 }}>{W.fix.impact}</span>
            <div style={{ display: "flex", gap: 0 }}>
              <button style={{ ...d3Btn(false), borderRight: "none" }}>Dismiss</button>
              <button style={d3Btn(true)}>Accept changes</button>
            </div>
          </div>
        </div>
      </div>

      {/* Days as horizontal index rows */}
      <div style={{ marginTop: 32 }}>
        <div style={{ display: "grid", gridTemplateColumns: "150px 1fr 1fr 1fr", gap: "0 32px", paddingBottom: 8 }}>
          <span></span>
          <D3Cap>Breakfast</D3Cap>
          <D3Cap>Lunch</D3Cap>
          <D3Cap>Dinner</D3Cap>
        </div>
        {W.days.map((day, i) => (
          <div key={i} style={{
            display: "grid", gridTemplateColumns: "150px 1fr 1fr 1fr", gap: "0 32px", alignItems: "center",
            padding: "13px 0", borderTop: i === 0 ? `1.5px solid ${D3.ink}` : `1px solid ${D3.line}`,
          }}>
            <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
              <span style={{ fontSize: 30, fontWeight: 800, letterSpacing: "-0.04em", width: 44, color: day.today ? D3.red : D3.ink }}>{String(day.n).padStart(2, "0")}</span>
              <div>
                <span style={{ fontSize: 13.5, fontWeight: 700 }}>{day.d}</span>
                {day.today && <div><D3Cap color={D3.red} style={{ fontSize: 9.5 }}>Today</D3Cap></div>}
              </div>
            </div>
            {["b", "l", "din"].map((k) => {
              const meal = day[k];
              const affected = meal.s === "affected";
              return (
                <div key={k} style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 0 }}>
                  {d3Sq(meal.s)}
                  <span style={{
                    fontSize: 15, fontWeight: affected ? 700 : 500,
                    color: affected ? D3.red : meal.s === "eaten" ? D3.faint : D3.ink,
                    whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
                  }}>{meal.name}</span>
                  {meal.batch && <D3Cap style={{ fontSize: 9 }}>Batch</D3Cap>}
                </div>
              );
            })}
          </div>
        ))}
        <div style={{ display: "flex", gap: 28, marginTop: 18, alignItems: "center", borderTop: `1px solid ${D3.line}`, paddingTop: 16 }}>
          {[["eaten", "Eaten"], ["cooked", "Cooked"], ["planned", "Planned"], ["affected", "Affected by suggestion"]].map(([s, lab]) => (
            <span key={s} style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
              {d3Sq(s)}<span style={{ fontSize: 12, color: D3.faint, fontWeight: 500 }}>{lab}</span>
            </span>
          ))}
          <span style={{ fontSize: 12, color: D3.faint, fontWeight: 500 }}>BATCH — batch-cook link</span>
        </div>
      </div>
    </div>
  );
}

function D3Day() {
  const D = window.MEAL.day;
  return (
    <div style={d3Base} data-screen-label="D3 Daily dashboard">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <D3Cap color={D3.red}>Wednesday 10 June · Day 4 of 7 · Plan active</D3Cap>
          <h1 style={{ margin: "10px 0 0", fontSize: 56, fontWeight: 800, letterSpacing: "-0.04em", lineHeight: 1 }}>Good evening,<br></br>Iren</h1>
        </div>
        {/* Day progress — 7 squares */}
        <div style={{ textAlign: "right" }}>
          <div style={{ display: "flex", gap: 5 }}>
            {Array.from({ length: 7 }).map((_, i) => (
              <span key={i} style={{ width: 16, height: 16, background: i < 3 ? D3.ink : i === 3 ? D3.red : "transparent", border: `1.5px solid ${i < 4 ? (i === 3 ? D3.red : D3.ink) : D3.faint}` }}></span>
            ))}
          </div>
          <D3Cap style={{ marginTop: 8, display: "inline-block" }}>Week progress</D3Cap>
        </div>
      </div>

      {/* Meals — gallery rows */}
      <div style={{ marginTop: 36 }}>
        {D.meals.map((m, i) => (
          <div key={i} style={{
            display: "grid", gridTemplateColumns: "130px 1fr auto", gap: 28, alignItems: "center",
            padding: "22px 0", borderTop: i === 0 ? `1.5px solid ${D3.ink}` : `1px solid ${D3.line}`,
          }}>
            <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
              <span style={{ fontSize: 28, fontWeight: 800, letterSpacing: "-0.03em", fontVariantNumeric: "tabular-nums" }}>{m.time}</span>
            </div>
            <div>
              <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
                <D3Cap style={{ fontSize: 10 }}>{m.slot}</D3Cap>
                {m.batch && <D3Cap style={{ fontSize: 10, border: `1px solid ${D3.line}`, padding: "2px 6px" }}>Batch</D3Cap>}
              </div>
              <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: "-0.02em", marginTop: 4 }}>{m.name}</div>
              <div style={{ fontSize: 13.5, color: D3.faint, marginTop: 4 }}>{m.who}</div>
              {m.alert && <div style={{ fontSize: 13, color: D3.red, marginTop: 4, fontWeight: 600 }}>! {m.alert}</div>}
            </div>
            <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
              <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                {d3Sq(m.status === "planned" ? "planned" : m.status)}
                <D3Cap color={D3.ink}>{m.status}</D3Cap>
              </span>
              {m.action && <button style={d3Btn(m.status === "planned")}>{m.action}</button>}
            </div>
          </div>
        ))}
      </div>

      {/* Numbers row — nutrition as oversized figures */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", borderTop: `1.5px solid ${D3.ink}`, marginTop: 12 }}>
        {D.nutrition.map((n, i) => {
          const pct = Math.min(100, (n.val / n.max) * 100);
          return (
            <div key={i} style={{ padding: "20px 24px 20px 0", paddingLeft: i ? 24 : 0, borderLeft: i ? `1px solid ${D3.line}` : "none" }}>
              <D3Cap color={n.behind ? D3.red : D3.faint}>{n.label}{n.behind ? " · behind" : ""}</D3Cap>
              <div style={{ display: "flex", alignItems: "baseline", gap: 6, marginTop: 8 }}>
                <span style={{ fontSize: 40, fontWeight: 800, letterSpacing: "-0.04em", color: n.behind ? D3.red : D3.ink }}>{n.val.toLocaleString()}</span>
                <span style={{ fontSize: 14, color: D3.faint, fontWeight: 600 }}>/ {n.max.toLocaleString()}{n.label !== "Calories" ? " g" : ""}</span>
              </div>
              <div style={{ height: 3, background: D3.line, marginTop: 10 }}>
                <div style={{ height: 3, width: `${pct}%`, background: n.behind ? D3.red : D3.ink }}></div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Bottom strip: attention + budget + suggestion */}
      <div style={{ display: "grid", gridTemplateColumns: "1.4fr 1fr", gap: 48, borderTop: `1px solid ${D3.line}`, marginTop: 4, paddingTop: 22 }}>
        <div>
          <D3Cap>Needs attention</D3Cap>
          <div style={{ marginTop: 12, display: "grid", gap: 10 }}>
            {D.attention.map((a, i) => (
              <div key={i} style={{ display: "flex", gap: 12, fontSize: 14, lineHeight: 1.5, fontWeight: 500 }}>
                <span style={{ color: D3.red, fontWeight: 800 }}>{String(i + 1).padStart(2, "0")}</span>
                <span>{a.text}</span>
              </div>
            ))}
          </div>
          <div style={{ display: "flex", gap: 0, marginTop: 20 }}>
            <button style={{ ...d3Btn(false), borderRight: "none" }}>+ Log a snack</button>
            <button style={d3Btn(false)}>Review suggestion</button>
          </div>
        </div>
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
            <D3Cap>Week budget</D3Cap>
            <span style={{ fontSize: 24, fontWeight: 800, letterSpacing: "-0.03em" }}>{D.budget.spent} <span style={{ fontSize: 13, color: D3.faint, fontWeight: 600 }}>of {D.budget.total}</span></span>
          </div>
          <div style={{ height: 3, background: D3.line, marginTop: 10 }}>
            <div style={{ height: 3, width: `${D.budget.pct}%`, background: D3.ink }}></div>
          </div>
          <div style={{ fontSize: 12.5, color: D3.faint, marginTop: 8, fontWeight: 500 }}>{D.budget.note}</div>
          <div style={{ borderTop: `1px solid ${D3.line}`, marginTop: 18, paddingTop: 14 }}>
            <D3Cap color={D3.red}>Suggestion</D3Cap>
            <div style={{ fontSize: 15, fontWeight: 700, marginTop: 6, letterSpacing: "-0.01em" }}>{D.suggestion.title}</div>
            <div style={{ fontSize: 12.5, color: D3.faint, marginTop: 4 }}>{D.suggestion.sub}</div>
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { D3Week, D3Day });
