// Direction 2 — "Ledger": dark green-black chef's instrument. Archivo + JetBrains Mono,
// brass accents, ruled precision matrix, ticker-style stats.
const D2 = {
  bg: "#0d1310",
  panel: "#121a15",
  panelHi: "#18221c",
  line: "rgba(214, 224, 213, 0.10)",
  lineHi: "rgba(214, 224, 213, 0.22)",
  text: "#e9e7dc",
  faint: "#8b968b",
  brass: "#c9a36a",
  green: "#56b384",
  red: "#d75c40",
  amber: "#d9a13f",
};

const d2Mono = { fontFamily: "'JetBrains Mono', monospace" };

const d2Base = {
  fontFamily: "'Archivo', sans-serif",
  background: D2.bg,
  color: D2.text,
  width: "100%",
  height: "100%",
  padding: "48px 56px",
  position: "relative",
  overflow: "hidden",
};

function D2Tag({ children, color = D2.faint, style = {} }) {
  return (
    <span style={{ ...d2Mono, fontSize: 10.5, letterSpacing: "0.14em", textTransform: "uppercase", color, ...style }}>{children}</span>
  );
}

function d2Btn(primary) {
  return {
    ...d2Mono, fontSize: 12, letterSpacing: "0.06em", padding: "10px 18px", cursor: "pointer",
    background: primary ? D2.brass : "transparent",
    color: primary ? "#171307" : D2.text,
    border: `1px solid ${primary ? D2.brass : D2.lineHi}`,
    borderRadius: 4, fontWeight: primary ? 600 : 400, textTransform: "uppercase",
  };
}

function d2Dot(s) {
  const c = s === "eaten" ? D2.green : s === "cooked" ? D2.amber : s === "affected" ? D2.red : "transparent";
  return (
    <span style={{
      width: 7, height: 7, borderRadius: "50%", display: "inline-block", flexShrink: 0,
      background: c, border: s === "planned" ? `1px solid ${D2.faint}` : "none",
      boxShadow: s !== "planned" ? `0 0 8px ${c}` : "none",
    }}></span>
  );
}

function D2Week() {
  const W = window.MEAL.week;
  const rows = [["b", "BRK"], ["l", "LCH"], ["din", "DIN"]];
  return (
    <div style={d2Base} data-screen-label="D2 Week plan">
      {/* Header */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
            <h1 style={{ margin: 0, fontSize: 34, fontWeight: 600, letterSpacing: "-0.01em" }}>This week’s plan</h1>
            <span style={{ ...d2Mono, fontSize: 10.5, letterSpacing: "0.14em", color: D2.green, border: `1px solid ${D2.green}`, borderRadius: 3, padding: "4px 8px" }}>● ACTIVE</span>
          </div>
          <div style={{ ...d2Mono, fontSize: 12.5, color: D2.faint, marginTop: 10 }}>{W.range} / {W.meta.toUpperCase()}</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d2Btn(false)}>↺ History</button>
          <button style={d2Btn(false)}>⟳ Re-optimise</button>
        </div>
      </div>

      {/* Ticker stats */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", border: `1px solid ${D2.line}`, borderRadius: 6, marginTop: 28, background: D2.panel }}>
        {W.stats.map((s, i) => (
          <div key={i} style={{ padding: "16px 22px", borderLeft: i ? `1px solid ${D2.line}` : "none" }}>
            <D2Tag color={s.warn ? D2.amber : D2.faint}>{s.warn ? "⚠ " : ""}{s.label}</D2Tag>
            <div style={{ ...d2Mono, fontSize: 22, marginTop: 8, color: s.warn ? D2.amber : D2.text, fontWeight: 500 }}>{s.value}</div>
            {s.sub && <div style={{ ...d2Mono, fontSize: 11, color: D2.faint, marginTop: 4 }}>{s.sub}</div>}
          </div>
        ))}
      </div>

      {/* Fix advisory */}
      <div style={{ border: `1px solid ${D2.red}55`, background: `linear-gradient(180deg, ${D2.panelHi}, ${D2.panel})`, borderRadius: 6, marginTop: 20, overflow: "hidden" }}>
        <div style={{ padding: "14px 22px", borderBottom: `1px solid ${D2.line}`, display: "flex", justifyContent: "space-between", alignItems: "center", background: `${D2.red}14` }}>
          <D2Tag color={D2.red}>▲ Advisory — {W.fix.title}</D2Tag>
          <D2Tag>{W.fix.sub}</D2Tag>
        </div>
        <div style={{ padding: "18px 22px", display: "grid", gap: 12 }}>
          {W.fix.swaps.map((sw, i) => (
            <div key={i} style={{ display: "flex", alignItems: "baseline", gap: 14, fontSize: 14.5 }}>
              <span style={{ ...d2Mono, fontSize: 11.5, color: D2.faint, width: 90, textTransform: "uppercase" }}>{sw.slot}</span>
              <span style={{ textDecoration: "line-through", color: D2.faint }}>{sw.from}</span>
              <span style={{ color: D2.brass }}>→</span>
              <span style={{ fontWeight: 600 }}>{sw.to}</span>
              {sw.note && <span style={{ ...d2Mono, fontSize: 10.5, color: D2.green, border: `1px solid ${D2.green}44`, borderRadius: 3, padding: "3px 7px" }}>{sw.note.toUpperCase()}</span>}
            </div>
          ))}
        </div>
        <div style={{ padding: "14px 22px", borderTop: `1px solid ${D2.line}`, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ ...d2Mono, fontSize: 12, color: D2.brass }}>{W.fix.impact.toUpperCase()}</span>
          <div style={{ display: "flex", gap: 10 }}>
            <button style={d2Btn(false)}>Dismiss</button>
            <button style={d2Btn(true)}>Accept changes</button>
          </div>
        </div>
      </div>

      {/* Matrix */}
      <div style={{ marginTop: 26, border: `1px solid ${D2.line}`, borderRadius: 6, overflow: "hidden" }}>
        <div style={{ display: "grid", gridTemplateColumns: "64px repeat(7, 1fr)" }}>
          <div style={{ background: D2.panel, borderBottom: `1px solid ${D2.line}` }}></div>
          {W.days.map((day, i) => (
            <div key={i} style={{
              padding: "12px 10px", textAlign: "center", borderLeft: `1px solid ${D2.line}`, borderBottom: `1px solid ${D2.line}`,
              background: day.today ? `${D2.brass}1d` : D2.panel,
            }}>
              <D2Tag color={day.today ? D2.brass : D2.faint}>{day.d} {String(day.n).padStart(2, "0")}</D2Tag>
              {day.today && <div style={{ ...d2Mono, fontSize: 9, letterSpacing: "0.2em", color: D2.brass, marginTop: 4 }}>— TODAY —</div>}
            </div>
          ))}
          {rows.map(([key, lab], r) => (
            <React.Fragment key={key}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "center", background: D2.panel, borderBottom: r < 2 ? `1px solid ${D2.line}` : "none" }}>
                <D2Tag style={{ writingMode: "vertical-rl", transform: "rotate(180deg)", letterSpacing: "0.3em" }}>{lab}</D2Tag>
              </div>
              {W.days.map((day, c) => {
                const meal = day[key];
                const affected = meal.s === "affected";
                return (
                  <div key={c} style={{
                    padding: "14px 12px", minHeight: 86, borderLeft: `1px solid ${D2.line}`,
                    borderBottom: r < 2 ? `1px solid ${D2.line}` : "none",
                    background: affected ? `${D2.red}10` : day.today ? `${D2.brass}0a` : "transparent",
                    display: "flex", flexDirection: "column", gap: 8, justifyContent: "space-between",
                  }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 7 }}>
                      {d2Dot(meal.s)}
                      <span style={{ fontSize: 13.5, lineHeight: 1.3, color: affected ? D2.red : meal.s === "eaten" ? D2.faint : D2.text, fontWeight: 500 }}>{meal.name}</span>
                    </div>
                    {meal.batch && <D2Tag color={D2.green} style={{ fontSize: 9 }}>≋ BATCH</D2Tag>}
                  </div>
                );
              })}
            </React.Fragment>
          ))}
        </div>
      </div>

      {/* Legend */}
      <div style={{ display: "flex", gap: 26, marginTop: 16, alignItems: "center" }}>
        {[["eaten", "eaten"], ["cooked", "cooked"], ["planned", "planned"], ["affected", "affected by suggestion"]].map(([s, lab]) => (
          <span key={s} style={{ display: "inline-flex", alignItems: "center", gap: 7 }}>
            {d2Dot(s)}<D2Tag>{lab}</D2Tag>
          </span>
        ))}
        <span style={{ display: "inline-flex", alignItems: "center", gap: 7 }}>
          <span style={{ ...d2Mono, color: D2.green, fontSize: 11 }}>≋</span><D2Tag>batch-cook link</D2Tag>
        </span>
      </div>
    </div>
  );
}

function D2Day() {
  const D = window.MEAL.day;
  return (
    <div style={d2Base} data-screen-label="D2 Daily dashboard">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <h1 style={{ margin: 0, fontSize: 34, fontWeight: 600 }}>{D.greeting}</h1>
          <div style={{ ...d2Mono, fontSize: 12.5, color: D2.faint, marginTop: 10 }}>{D.date.toUpperCase()} / WEEK PLAN DAY 4 OF 7</div>
        </div>
        <span style={{ ...d2Mono, fontSize: 10.5, letterSpacing: "0.14em", color: D2.green, border: `1px solid ${D2.green}`, borderRadius: 3, padding: "5px 10px" }}>● PLAN ACTIVE</span>
      </div>

      {/* Timeline rail */}
      <div style={{ marginTop: 30, display: "grid", gap: 12 }}>
        {D.meals.map((m, i) => {
          const sc = m.status === "eaten" ? D2.green : m.status === "cooked" ? D2.amber : D2.faint;
          return (
            <div key={i} style={{
              display: "grid", gridTemplateColumns: "110px 1fr auto", gap: 22, alignItems: "center",
              background: D2.panel, border: `1px solid ${D2.line}`, borderLeft: `3px solid ${sc}`,
              borderRadius: 6, padding: "20px 24px",
            }}>
              <div>
                <div style={{ ...d2Mono, fontSize: 20, fontWeight: 500 }}>{m.time}</div>
                <D2Tag>{m.slot}</D2Tag>
              </div>
              <div>
                <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                  <span style={{ fontSize: 18, fontWeight: 600 }}>{m.name}</span>
                  {m.batch && <D2Tag color={D2.green}>≋ BATCH</D2Tag>}
                </div>
                <div style={{ fontSize: 13.5, color: D2.faint, marginTop: 5 }}>{m.who}</div>
                {m.alert && <div style={{ ...d2Mono, fontSize: 12, color: D2.amber, marginTop: 6 }}>❄ {m.alert.toUpperCase()}</div>}
              </div>
              <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                <span style={{ ...d2Mono, fontSize: 10.5, letterSpacing: "0.12em", color: sc, border: `1px solid ${sc}55`, borderRadius: 3, padding: "5px 10px", textTransform: "uppercase" }}>{m.status}</span>
                {m.action && <button style={d2Btn(m.status === "planned")}>{m.action}</button>}
              </div>
            </div>
          );
        })}
      </div>

      {/* Instrument panel */}
      <div style={{ display: "grid", gridTemplateColumns: "1.25fr 1fr", gap: 14, marginTop: 14 }}>
        <div style={{ background: D2.panel, border: `1px solid ${D2.line}`, borderRadius: 6, padding: "22px 24px" }}>
          <D2Tag>Today’s nutrition</D2Tag>
          <div style={{ marginTop: 18, display: "grid", gap: 16 }}>
            {D.nutrition.map((n, i) => {
              const pct = Math.min(100, (n.val / n.max) * 100);
              const segs = 24;
              const lit = Math.round((pct / 100) * segs);
              const col = n.behind ? D2.amber : D2.green;
              return (
                <div key={i}>
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 7 }}>
                    <span style={{ fontSize: 13.5, fontWeight: 500 }}>{n.label}</span>
                    <span style={{ ...d2Mono, fontSize: 12, color: n.behind ? D2.amber : D2.faint }}>{n.fmt}{n.behind ? " · BEHIND" : ""}</span>
                  </div>
                  <div style={{ display: "flex", gap: 3 }}>
                    {Array.from({ length: segs }).map((_, s) => (
                      <span key={s} style={{ flex: 1, height: 8, borderRadius: 1, background: s < lit ? col : D2.line, boxShadow: s < lit ? `0 0 6px ${col}66` : "none" }}></span>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
          <button style={{ ...d2Btn(false), marginTop: 20 }}>+ Log a snack</button>
        </div>
        <div style={{ display: "grid", gap: 14, alignContent: "start" }}>
          <div style={{ background: D2.panel, border: `1px solid ${D2.line}`, borderRadius: 6, padding: "22px 24px" }}>
            <D2Tag color={D2.amber}>⚠ Needs attention</D2Tag>
            <div style={{ marginTop: 14, display: "grid", gap: 11 }}>
              {D.attention.map((a, i) => (
                <div key={i} style={{ display: "flex", gap: 10, fontSize: 13.5, lineHeight: 1.45 }}>
                  <span style={{ ...d2Mono, color: a.kind === "expiry" ? D2.red : a.kind === "defrost" ? D2.amber : D2.brass, fontSize: 12 }}>{a.kind === "expiry" ? "▲" : a.kind === "defrost" ? "❄" : "✦"}</span>
                  <span>{a.text}</span>
                </div>
              ))}
            </div>
          </div>
          <div style={{ background: D2.panel, border: `1px solid ${D2.line}`, borderRadius: 6, padding: "22px 24px" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
              <D2Tag>Week budget</D2Tag>
              <span style={{ ...d2Mono, fontSize: 16 }}>{D.budget.spent} <span style={{ color: D2.faint, fontSize: 12 }}>/ {D.budget.total}</span></span>
            </div>
            <div style={{ display: "flex", gap: 3, marginTop: 12 }}>
              {Array.from({ length: 24 }).map((_, s) => (
                <span key={s} style={{ flex: 1, height: 8, borderRadius: 1, background: s < Math.round(0.695 * 24) ? D2.brass : D2.line }}></span>
              ))}
            </div>
            <div style={{ ...d2Mono, fontSize: 11, color: D2.green, marginTop: 9 }}>{D.budget.note.toUpperCase()}</div>
          </div>
        </div>
      </div>

      {/* AI suggestion bar */}
      <div style={{
        marginTop: 14, border: `1px solid ${D2.brass}44`, background: `${D2.brass}0d`, borderRadius: 6,
        padding: "18px 24px", display: "flex", justifyContent: "space-between", alignItems: "center",
      }}>
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <span style={{ color: D2.brass }}>✦</span>
            <span style={{ fontSize: 15.5, fontWeight: 600 }}>{D.suggestion.title}</span>
          </div>
          <div style={{ fontSize: 13, color: D2.faint, marginTop: 5, marginLeft: 24 }}>{D.suggestion.sub}</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d2Btn(false)}>Review</button>
          <button style={d2Btn(true)}>Accept</button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { D2Week, D2Day });
