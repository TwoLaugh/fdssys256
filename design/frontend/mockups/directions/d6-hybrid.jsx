// Direction 6 — "Hybrid": D3's Swiss skeleton on D4's warm canvas. Schibsted Grotesk
// numerals, Instrument Sans body, Instrument Serif italic reserved for the advisor's
// voice (suggestions, greeting). Terracotta = action, red = danger only, olive = done,
// amber = time-sensitive. D2's segmented bars. D1's errata concept, unrotated.
const D6 = {
  bg: "#faf6ec",
  card: "#fffdf6",
  ink: "#262019",
  muted: "#6f6553",
  line: "#e6decb",
  lineHi: "#cfc4a9",
  terra: "#c14e28",
  terraDark: "#9c3c1d",
  red: "#b3261e",
  olive: "#5f7036",
  amber: "#9a6a1c",
};

const d6Base = {
  fontFamily: "'Instrument Sans', sans-serif",
  background: D6.bg,
  color: D6.ink,
  width: "100%",
  minHeight: "100%",
  padding: "44px 56px 56px",
  boxSizing: "border-box",
};

function D6Label({ children, color = D6.muted, style = {} }) {
  return (
    <span style={{ fontSize: 11, letterSpacing: "0.14em", textTransform: "uppercase", color, fontWeight: 600, ...style }}>{children}</span>
  );
}

function D6Serif({ children, size = 22, style = {} }) {
  return (
    <span style={{ fontFamily: "'Instrument Serif', serif", fontStyle: "italic", fontSize: size, lineHeight: 1.15, ...style }}>{children}</span>
  );
}

function D6Num({ children, size = 30, color = D6.ink, style = {} }) {
  return (
    <span style={{ fontFamily: "'Schibsted Grotesk', sans-serif", fontWeight: 700, fontSize: size, lineHeight: 1, letterSpacing: "-0.01em", color, fontVariantNumeric: "tabular-nums", ...style }}>{children}</span>
  );
}

function d6Btn(primary) {
  return {
    fontFamily: "'Instrument Sans', sans-serif", fontSize: 13, fontWeight: 600,
    padding: "8px 18px", cursor: "pointer", borderRadius: 8,
    background: primary ? D6.terra : "transparent",
    color: primary ? "#fff7ef" : D6.ink,
    border: `1px solid ${primary ? D6.terra : D6.lineHi}`,
  };
}

function D6Chip({ children, color = D6.olive }) {
  return (
    <span style={{ fontSize: 11, letterSpacing: "0.1em", textTransform: "uppercase", fontWeight: 700, color, border: `1px solid ${color}`, borderRadius: 999, padding: "3px 10px" }}>{children}</span>
  );
}

function d6Mark(s) {
  const base = { fontSize: 12, width: 16, flexShrink: 0, textAlign: "center", fontWeight: 700 };
  if (s === "eaten") return <span style={{ ...base, color: D6.olive }}>✓</span>;
  if (s === "cooked") return <span style={{ ...base, color: D6.amber }}>●</span>;
  if (s === "affected") return <span style={{ ...base, color: D6.red }}>✕</span>;
  return <span style={{ ...base, color: "#9c9077" }}>○</span>;
}

function D6Segments({ pct, color, width = 170 }) {
  const total = 22;
  const filled = Math.round(Math.min(1, pct) * total);
  return (
    <div style={{ display: "flex", gap: 2, width }}>
      {Array.from({ length: total }, (_, i) => (
        <span key={i} style={{ flex: 1, height: 8, borderRadius: 1, background: i < filled ? color : D6.line }}></span>
      ))}
    </div>
  );
}

function D6MealCell({ meal }) {
  const affected = meal.s === "affected";
  return (
    <div style={{ display: "flex", alignItems: "baseline", gap: 7, minWidth: 0 }}>
      {d6Mark(meal.s)}
      <span style={{
        fontSize: 15, fontWeight: meal.s === "planned" || affected ? 500 : 400,
        color: affected ? D6.red : meal.s === "eaten" ? D6.muted : D6.ink,
        textDecoration: affected ? "line-through" : "none",
        whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
      }}>{meal.name}</span>
      {meal.batch && <span style={{ fontSize: 10, letterSpacing: "0.08em", color: D6.olive, fontWeight: 700, flexShrink: 0 }}>BATCH</span>}
    </div>
  );
}

function D6Fix() {
  const F = window.MEAL.week.fix;
  return (
    <div style={{ background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "20px 26px", marginTop: 26 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span style={{ width: 8, height: 8, borderRadius: "50%", background: D6.terra }}></span>
          <D6Label color={D6.terraDark}>Suggested fix</D6Label>
        </div>
        <span style={{ fontSize: 12, color: D6.muted }}>{F.sub}</span>
      </div>
      <div style={{ marginTop: 8 }}><D6Serif size={23}>{F.title}</D6Serif></div>
      <div style={{ marginTop: 14, display: "grid", gap: 8 }}>
        {F.swaps.map((sw, i) => (
          <div key={i} style={{ display: "flex", alignItems: "baseline", gap: 10, fontSize: 14.5 }}>
            <span style={{ width: 80, color: D6.muted, fontSize: 12.5, flexShrink: 0 }}>{sw.slot}</span>
            <span style={{ textDecoration: "line-through", color: D6.muted }}>{sw.from}</span>
            <span style={{ color: D6.lineHi }}>→</span>
            <span style={{ fontWeight: 600 }}>{sw.to}</span>
            {sw.note && <span style={{ fontSize: 11, fontWeight: 600, color: D6.olive, background: "#eef0e2", borderRadius: 999, padding: "2px 9px" }}>{sw.note}</span>}
          </div>
        ))}
      </div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 16, borderTop: `1px solid ${D6.line}`, paddingTop: 14 }}>
        <span style={{ fontSize: 13, color: D6.muted }}>{F.impact}</span>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d6Btn(false)}>Dismiss</button>
          <button style={d6Btn(true)}>Accept changes</button>
        </div>
      </div>
    </div>
  );
}

function D6Week() {
  const W = window.MEAL.week;
  return (
    <div style={d6Base} data-screen-label="D6 Week plan">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <span style={{ fontFamily: "'Schibsted Grotesk', sans-serif", fontWeight: 700, fontSize: 32, letterSpacing: "-0.015em" }}>{W.title}</span>
            <D6Chip>Active</D6Chip>
          </div>
          <div style={{ fontSize: 13.5, color: D6.muted, marginTop: 6 }}>{W.range} · {W.meta}</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d6Btn(false)}>History</button>
          <button style={d6Btn(false)}>Re-optimise</button>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", marginTop: 26, border: `1px solid ${D6.line}`, borderRadius: 12, background: D6.card, overflow: "hidden" }}>
        {W.stats.map((s, i) => (
          <div key={i} style={{ padding: "16px 22px", borderLeft: i > 0 ? `1px solid ${D6.line}` : "none" }}>
            <D6Label color={s.warn ? D6.amber : D6.muted}>{s.label}</D6Label>
            <div style={{ marginTop: 7 }}>
              <D6Num size={24} color={s.warn ? D6.amber : D6.ink}>{s.value}</D6Num>
            </div>
            {s.sub && <div style={{ fontSize: 12, color: D6.muted, marginTop: 4 }}>{s.sub}</div>}
          </div>
        ))}
      </div>

      <D6Fix />

      <div style={{ marginTop: 30 }}>
        <div style={{ display: "grid", gridTemplateColumns: "118px 1fr 1fr 1fr", gap: "0 24px", paddingBottom: 9, borderBottom: `2px solid ${D6.ink}` }}>
          <span></span>
          <D6Label>Breakfast</D6Label>
          <D6Label>Lunch</D6Label>
          <D6Label>Dinner</D6Label>
        </div>
        {W.days.map((day, i) => (
          <div key={i} style={{
            display: "grid", gridTemplateColumns: "118px 1fr 1fr 1fr", gap: "0 24px",
            alignItems: "baseline", padding: "13px 0",
            borderBottom: `1px solid ${D6.line}`,
            background: day.today ? D6.card : "transparent",
            boxShadow: day.today ? `inset 3px 0 0 ${D6.terra}` : "none",
            paddingLeft: day.today ? 13 : 0, marginLeft: day.today ? -13 : 0,
          }}>
            <div style={{ display: "flex", alignItems: "baseline", gap: 8, whiteSpace: "nowrap" }}>
              <D6Num size={17} color={day.today ? D6.terra : D6.ink}>{day.d} {day.n}</D6Num>
              {day.today && <span style={{ fontSize: 9.5, letterSpacing: "0.14em", color: D6.terra, fontWeight: 700 }}>TODAY</span>}
            </div>
            <D6MealCell meal={day.b} />
            <D6MealCell meal={day.l} />
            <D6MealCell meal={day.din} />
          </div>
        ))}
        <div style={{ display: "flex", gap: 22, marginTop: 16, fontSize: 12, color: D6.muted, justifyContent: "center" }}>
          <span><span style={{ color: D6.olive, fontWeight: 700 }}>✓</span> eaten</span>
          <span><span style={{ color: D6.amber }}>●</span> cooked</span>
          <span><span style={{ color: D6.lineHi }}>○</span> planned</span>
          <span><span style={{ color: D6.red, fontWeight: 700 }}>✕</span> affected by suggestion</span>
          <span><span style={{ color: D6.olive, fontWeight: 700, fontSize: 10 }}>BATCH</span> batch-cook link</span>
        </div>
      </div>
    </div>
  );
}

function D6Day() {
  const D = window.MEAL.day;
  return (
    <div style={d6Base} data-screen-label="D6 Daily dashboard">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", borderBottom: `2px solid ${D6.ink}`, paddingBottom: 22 }}>
        <div>
          <D6Label color={D6.terraDark}>{D.date} · {D.progress}</D6Label>
          <div style={{ marginTop: 10 }}><D6Serif size={42}>{D.greeting}</D6Serif></div>
        </div>
        <D6Chip>Plan active</D6Chip>
      </div>

      <div>
        {D.meals.map((m, i) => (
          <div key={i} style={{ display: "grid", gridTemplateColumns: "104px 1fr auto", gap: 22, alignItems: "center", padding: "20px 0", borderBottom: `1px solid ${D6.line}` }}>
            <div>
              <D6Num size={21}>{m.time}</D6Num>
              <div style={{ marginTop: 4 }}><D6Label>{m.slot}</D6Label></div>
            </div>
            <div>
              <div style={{ display: "flex", alignItems: "baseline", gap: 9 }}>
                <span style={{ fontSize: 18, fontWeight: 600 }}>{m.name}</span>
                {m.batch && <span style={{ fontSize: 10, letterSpacing: "0.08em", color: D6.olive, fontWeight: 700 }}>BATCH</span>}
              </div>
              <div style={{ fontSize: 13.5, color: D6.muted, marginTop: 4 }}>{m.who}</div>
              {m.alert && <div style={{ fontSize: 13, color: D6.amber, marginTop: 4, fontWeight: 600 }}>❄ {m.alert}</div>}
            </div>
            <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
              <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
                {d6Mark(m.status === "eaten" ? "eaten" : m.status === "cooked" ? "cooked" : "planned")}
                <D6Label color={m.status === "eaten" ? D6.olive : m.status === "cooked" ? D6.amber : D6.muted}>{m.status}</D6Label>
              </span>
              {m.action && <button style={d6Btn(m.status === "planned")}>{m.action}</button>}
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 22, marginTop: 28, background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "20px 26px" }}>
        {D.nutrition.map((n, i) => (
          <div key={i}>
            <D6Label color={n.behind ? D6.amber : D6.muted}>{n.label}{n.behind ? " · behind" : ""}</D6Label>
            <div style={{ margin: "8px 0 10px", display: "flex", alignItems: "baseline", gap: 6 }}>
              <D6Num size={30} color={n.behind ? D6.amber : D6.ink}>{n.fmt.split(" / ")[0]}</D6Num>
              <span style={{ fontSize: 13, color: D6.muted }}>/ {n.fmt.split(" / ")[1]}</span>
            </div>
            <D6Segments pct={n.val / n.max} color={n.behind ? D6.amber : D6.olive} width={150} />
          </div>
        ))}
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1.25fr 1fr", gap: 40, marginTop: 28 }}>
        <div>
          <D6Label>Needs attention</D6Label>
          <div style={{ marginTop: 12, display: "grid", gap: 11 }}>
            {D.attention.map((a, i) => (
              <div key={i} style={{ display: "flex", gap: 12, fontSize: 14, lineHeight: 1.45, alignItems: "baseline" }}>
                <D6Num size={13} color={a.kind === "expiry" ? D6.red : a.kind === "defrost" ? D6.amber : D6.terra}>{String(i + 1).padStart(2, "0")}</D6Num>
                <span>{a.text}</span>
              </div>
            ))}
          </div>
          <button style={{ ...d6Btn(false), marginTop: 18 }}>+ Log a snack</button>
        </div>
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
            <D6Label>Week budget</D6Label>
            <span><D6Num size={20}>{D.budget.spent}</D6Num><span style={{ fontSize: 13, color: D6.muted }}> of {D.budget.total}</span></span>
          </div>
          <div style={{ marginTop: 10 }}><D6Segments pct={D.budget.pct / 100} color={D6.olive} width={250} /></div>
          <div style={{ fontSize: 12.5, color: D6.olive, marginTop: 7, fontWeight: 600 }}>{D.budget.note}</div>
        </div>
      </div>

      <div style={{ marginTop: 28, background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "18px 26px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ width: 8, height: 8, borderRadius: "50%", background: D6.terra }}></span>
            <D6Label color={D6.terraDark}>Suggestion · from your feedback</D6Label>
          </div>
          <div style={{ marginTop: 7 }}><D6Serif size={21}>{D.suggestion.title}</D6Serif></div>
          <div style={{ fontSize: 13, color: D6.muted, marginTop: 4 }}>{D.suggestion.sub}</div>
        </div>
        <div style={{ display: "flex", gap: 10, flexShrink: 0 }}>
          <button style={d6Btn(false)}>Review</button>
          <button style={d6Btn(true)}>Accept</button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { D6Week, D6Day });
