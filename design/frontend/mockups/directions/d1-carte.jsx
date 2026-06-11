// Direction 1 — "La Carte": editorial tasting-menu. Ivory paper, Instrument Serif,
// hairline rules, statuses as typographic marks, the fix as a paper "errata slip".
const D1 = {
  paper: "#f6f1e6",
  card: "#fdfaf2",
  ink: "#211d15",
  faint: "#8a8170",
  line: "#d9d0bc",
  red: "#a33518",
  olive: "#5c6b3f",
  amber: "#9a6a1c",
};

const d1Base = {
  fontFamily: "'Instrument Sans', sans-serif",
  background: D1.paper,
  color: D1.ink,
  width: "100%",
  height: "100%",
  padding: "56px 64px",
  position: "relative",
  overflow: "hidden",
};

function D1Serif({ children, size = 28, italic = false, style = {} }) {
  return (
    <span style={{ fontFamily: "'Instrument Serif', serif", fontStyle: italic ? "italic" : "normal", fontSize: size, lineHeight: 1.1, ...style }}>{children}</span>
  );
}

function D1Label({ children, style = {} }) {
  return (
    <span style={{ fontSize: 11, letterSpacing: "0.18em", textTransform: "uppercase", color: D1.faint, fontWeight: 600, ...style }}>{children}</span>
  );
}

function d1Mark(s) {
  if (s === "eaten") return <span style={{ color: D1.faint }}>✓</span>;
  if (s === "cooked") return <span style={{ color: D1.amber }}>●</span>;
  if (s === "affected") return <span style={{ color: D1.red }}>✕</span>;
  return <span style={{ color: "#c4baa3" }}>○</span>;
}

function D1MealCell({ meal }) {
  const affected = meal.s === "affected";
  return (
    <div style={{ display: "flex", alignItems: "baseline", gap: 8, minWidth: 0 }}>
      <span style={{ fontSize: 13, width: 14, flexShrink: 0, textAlign: "center" }}>{d1Mark(meal.s)}</span>
      <span style={{
        fontFamily: "'Instrument Serif', serif", fontSize: 19,
        color: affected ? D1.red : meal.s === "eaten" ? D1.faint : D1.ink,
        textDecoration: affected ? "line-through" : "none",
        textDecorationThickness: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
      }}>{meal.name}</span>
      {meal.batch && <span style={{ fontSize: 10, letterSpacing: "0.1em", color: D1.olive, fontWeight: 600, flexShrink: 0 }}>BATCH</span>}
    </div>
  );
}

function D1Week() {
  const W = window.MEAL.week;
  return (
    <div style={d1Base} data-screen-label="D1 Week plan">
      {/* Masthead */}
      <div style={{ textAlign: "center", borderBottom: `1px solid ${D1.ink}`, paddingBottom: 28 }}>
        <D1Label style={{ color: D1.olive }}>Weekly Menu · Plan Active</D1Label>
        <div style={{ marginTop: 10 }}>
          <D1Serif size={54}>This Week’s Plan</D1Serif>
        </div>
        <div style={{ marginTop: 10, fontSize: 14, color: D1.faint }}>
          {W.range} · {W.meta}
        </div>
        <div style={{ display: "flex", justifyContent: "center", gap: 28, marginTop: 22, fontSize: 13.5 }}>
          {W.stats.map((s, i) => (
            <span key={i} style={{ color: s.warn ? D1.amber : D1.ink }}>
              <span style={{ color: s.warn ? D1.amber : D1.faint }}>{s.label} — </span>
              <strong style={{ fontWeight: 600 }}>{s.value}</strong>
              {s.sub && <span style={{ color: D1.faint }}> · {s.sub}</span>}
            </span>
          ))}
        </div>
        <div style={{ display: "flex", justifyContent: "center", gap: 12, marginTop: 22 }}>
          <button style={d1Btn(false)}>History</button>
          <button style={d1Btn(false)}>Re-optimise</button>
        </div>
      </div>

      {/* Errata slip */}
      <div style={{
        margin: "30px auto 0", maxWidth: 780, background: D1.card,
        border: `1px solid ${D1.line}`, borderTop: `3px solid ${D1.red}`,
        padding: "24px 32px", boxShadow: "0 12px 30px rgba(33,29,21,0.08)",
        transform: "rotate(-0.4deg)",
      }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
          <D1Label style={{ color: D1.red }}>Errata — Suggested Fix</D1Label>
          <span style={{ fontSize: 12, color: D1.faint }}>{W.fix.sub}</span>
        </div>
        <div style={{ marginTop: 8 }}>
          <D1Serif size={24} italic>{W.fix.title}</D1Serif>
        </div>
        <div style={{ marginTop: 16, display: "grid", gap: 10 }}>
          {W.fix.swaps.map((sw, i) => (
            <div key={i} style={{ display: "flex", alignItems: "baseline", gap: 12, fontSize: 15 }}>
              <span style={{ width: 84, color: D1.faint, fontSize: 13 }}>{sw.slot}</span>
              <span style={{ textDecoration: "line-through", color: D1.faint, fontFamily: "'Instrument Serif', serif", fontSize: 17 }}>{sw.from}</span>
              <span style={{ color: D1.faint }}>→</span>
              <span style={{ fontFamily: "'Instrument Serif', serif", fontSize: 17, fontWeight: 500 }}>{sw.to}</span>
              {sw.note && <span style={{ fontSize: 12, color: D1.olive, fontStyle: "italic" }}>{sw.note}</span>}
            </div>
          ))}
        </div>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 18, borderTop: `1px dashed ${D1.line}`, paddingTop: 14 }}>
          <span style={{ fontSize: 13, color: D1.faint }}>{W.fix.impact}</span>
          <div style={{ display: "flex", gap: 10 }}>
            <button style={d1Btn(false)}>Dismiss</button>
            <button style={d1Btn(true)}>Accept changes</button>
          </div>
        </div>
      </div>

      {/* Menu — days as typeset rows */}
      <div style={{ marginTop: 36 }}>
        <div style={{ display: "grid", gridTemplateColumns: "110px 1fr 1fr 1fr", gap: "0 28px", paddingBottom: 10, borderBottom: `1px solid ${D1.ink}` }}>
          <D1Label> </D1Label>
          <D1Label>Breakfast</D1Label>
          <D1Label>Lunch</D1Label>
          <D1Label>Dinner</D1Label>
        </div>
        {W.days.map((day, i) => (
          <div key={i} style={{
            display: "grid", gridTemplateColumns: "110px 1fr 1fr 1fr", gap: "0 28px",
            alignItems: "baseline", padding: "15px 0",
            borderBottom: `1px solid ${D1.line}`,
            background: day.today ? D1.card : "transparent",
            boxShadow: day.today ? `inset 3px 0 0 ${D1.olive}` : "none",
            paddingLeft: day.today ? 14 : 0, marginLeft: day.today ? -14 : 0,
          }}>
            <div>
              <D1Serif size={21} italic={!!day.today}>{day.d} {day.n}</D1Serif>
              {day.today && <div style={{ fontSize: 10, letterSpacing: "0.16em", color: D1.olive, fontWeight: 700, marginTop: 3 }}>TODAY</div>}
            </div>
            <D1MealCell meal={day.b} />
            <D1MealCell meal={day.l} />
            <D1MealCell meal={day.din} />
          </div>
        ))}
        <div style={{ display: "flex", gap: 24, marginTop: 18, fontSize: 12.5, color: D1.faint, justifyContent: "center" }}>
          <span>✓ eaten</span>
          <span><span style={{ color: D1.amber }}>●</span> cooked</span>
          <span>○ planned</span>
          <span><span style={{ color: D1.red }}>✕</span> affected by suggestion</span>
          <span><span style={{ color: D1.olive }}>BATCH</span> batch-cook link</span>
        </div>
      </div>
    </div>
  );
}

function d1Btn(primary) {
  return {
    fontFamily: "'Instrument Sans', sans-serif", fontSize: 13, fontWeight: 600,
    letterSpacing: "0.04em", padding: "9px 20px", cursor: "pointer",
    background: primary ? D1.ink : "transparent",
    color: primary ? D1.paper : D1.ink,
    border: `1px solid ${D1.ink}`, borderRadius: 999,
  };
}

function D1Day() {
  const D = window.MEAL.day;
  return (
    <div style={d1Base} data-screen-label="D1 Daily dashboard">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", borderBottom: `1px solid ${D1.ink}`, paddingBottom: 24 }}>
        <div>
          <D1Label style={{ color: D1.olive }}>{D.date} · {D.progress}</D1Label>
          <div style={{ marginTop: 8 }}><D1Serif size={48} italic>{D.greeting}</D1Serif></div>
        </div>
        <button style={d1Btn(false)}>Plan active</button>
      </div>

      {/* Courses */}
      <div style={{ marginTop: 8 }}>
        {D.meals.map((m, i) => (
          <div key={i} style={{ display: "grid", gridTemplateColumns: "120px 1fr auto", gap: 24, alignItems: "center", padding: "24px 0", borderBottom: `1px solid ${D1.line}` }}>
            <div>
              <div style={{ fontSize: 22, fontWeight: 500, fontVariantNumeric: "tabular-nums" }}>{m.time}</div>
              <D1Label>{m.slot}</D1Label>
            </div>
            <div>
              <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
                <D1Serif size={26}>{m.name}</D1Serif>
                {m.batch && <span style={{ fontSize: 10, letterSpacing: "0.1em", color: D1.olive, fontWeight: 700 }}>BATCH</span>}
              </div>
              <div style={{ fontSize: 14, color: D1.faint, marginTop: 5 }}>{m.who}</div>
              {m.alert && <div style={{ fontSize: 13.5, color: D1.amber, marginTop: 5, fontStyle: "italic" }}>❄ {m.alert}</div>}
            </div>
            <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
              <span style={{ fontSize: 12, letterSpacing: "0.12em", textTransform: "uppercase", color: m.status === "eaten" ? D1.olive : m.status === "cooked" ? D1.amber : D1.faint, fontWeight: 700 }}>{m.status === "eaten" ? "✓ eaten" : m.status}</span>
              {m.action && <button style={d1Btn(m.status === "planned")}>{m.action}</button>}
            </div>
          </div>
        ))}
      </div>

      {/* Two-column lower: nutrition table + kitchen notes */}
      <div style={{ display: "grid", gridTemplateColumns: "1.2fr 1fr", gap: 56, marginTop: 36 }}>
        <div>
          <D1Label>Today’s Nutrition</D1Label>
          <div style={{ marginTop: 14, display: "grid", gap: 14 }}>
            {D.nutrition.map((n, i) => (
              <div key={i}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", fontSize: 15 }}>
                  <span style={{ fontFamily: "'Instrument Serif', serif", fontSize: 18 }}>{n.label}</span>
                  <span style={{ color: n.behind ? D1.amber : D1.ink, fontVariantNumeric: "tabular-nums", fontSize: 14 }}>
                    {n.fmt}{n.behind && <span style={{ fontStyle: "italic" }}> · behind</span>}
                  </span>
                </div>
                <div style={{ height: 2, background: D1.line, marginTop: 7 }}>
                  <div style={{ height: 2, width: `${Math.min(100, (n.val / n.max) * 100)}%`, background: n.behind ? D1.amber : D1.olive }}></div>
                </div>
              </div>
            ))}
          </div>
          <button style={{ ...d1Btn(false), marginTop: 20 }}>+ Log a snack</button>
        </div>
        <div>
          <D1Label>Kitchen Notes</D1Label>
          <div style={{ marginTop: 14, display: "grid", gap: 12 }}>
            {D.attention.map((a, i) => (
              <div key={i} style={{ display: "flex", gap: 10, fontSize: 14.5, lineHeight: 1.45 }}>
                <span style={{ color: a.kind === "expiry" ? D1.red : a.kind === "defrost" ? D1.amber : D1.olive }}>—</span>
                <span>{a.text}</span>
              </div>
            ))}
          </div>
          <div style={{ marginTop: 26, borderTop: `1px dashed ${D1.line}`, paddingTop: 16 }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
              <D1Label>Week Budget</D1Label>
              <span style={{ fontFamily: "'Instrument Serif', serif", fontSize: 20 }}>{D.budget.spent} <span style={{ color: D1.faint, fontSize: 15 }}>of {D.budget.total}</span></span>
            </div>
            <div style={{ height: 2, background: D1.line, marginTop: 9 }}>
              <div style={{ height: 2, width: `${D.budget.pct}%`, background: D1.olive }}></div>
            </div>
            <div style={{ fontSize: 12.5, color: D1.olive, marginTop: 7, fontStyle: "italic" }}>{D.budget.note}</div>
          </div>
        </div>
      </div>

      {/* Suggestion slip */}
      <div style={{
        marginTop: 36, background: D1.card, border: `1px solid ${D1.line}`, borderTop: `3px solid ${D1.olive}`,
        padding: "20px 28px", display: "flex", justifyContent: "space-between", alignItems: "center",
        boxShadow: "0 10px 24px rgba(33,29,21,0.07)", transform: "rotate(0.3deg)",
      }}>
        <div>
          <D1Label style={{ color: D1.olive }}>Chef’s Suggestion</D1Label>
          <div style={{ marginTop: 6 }}><D1Serif size={21} italic>{D.suggestion.title}</D1Serif></div>
          <div style={{ fontSize: 13, color: D1.faint, marginTop: 5 }}>{D.suggestion.sub}</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d1Btn(false)}>Review</button>
          <button style={d1Btn(true)}>Accept</button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { D1Week, D1Day });
