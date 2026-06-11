// Direction 4 — "Harvest": warm & appetizing. Cream, terracotta, olive. Bricolage
// Grotesque + Karla. Rounded cards with food-swatch thumbnails (placeholders).
const D4 = {
  bg: "#f8f1e3",
  card: "#fffcf5",
  ink: "#3b2d20",
  faint: "#94815f",
  line: "#e8ddc6",
  terra: "#c2492a",
  olive: "#6d7a3e",
  honey: "#c98a23",
  cream: "#f3e8d2",
};

// food-swatch palette for thumbnail placeholders (keyed by hash of name)
const d4Swatches = [
  ["#e9c46a", "#d4a437"], ["#d96c47", "#b94d2c"], ["#8a9a5b", "#6d7a3e"],
  ["#c97f5d", "#a85f3d"], ["#b6c199", "#94a36b"], ["#e3b587", "#c99356"],
];
function d4Swatch(name) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 997;
  return d4Swatches[h % d4Swatches.length];
}

const d4Base = {
  fontFamily: "'Karla', sans-serif",
  background: D4.bg,
  color: D4.ink,
  width: "100%",
  height: "100%",
  padding: "52px 60px",
  overflow: "hidden",
  position: "relative",
};

const d4Display = { fontFamily: "'Bricolage Grotesque', sans-serif" };

function D4Chip({ children, color = D4.faint, bg = "transparent", border = true, style = {} }) {
  return (
    <span style={{
      fontSize: 11.5, fontWeight: 700, letterSpacing: "0.04em", color, background: bg,
      border: border ? `1px solid ${color}55` : "none", borderRadius: 999, padding: "4px 11px",
      display: "inline-flex", alignItems: "center", gap: 5, ...style,
    }}>{children}</span>
  );
}

function d4Btn(primary) {
  return {
    fontFamily: "'Bricolage Grotesque', sans-serif", fontSize: 13.5, fontWeight: 700,
    padding: "10px 22px", cursor: "pointer", borderRadius: 999,
    background: primary ? D4.terra : "transparent",
    color: primary ? "#fff8ef" : D4.ink,
    border: primary ? "none" : `1.5px solid ${D4.ink}33`,
  };
}

function d4Thumb(name, size = 34, status) {
  const [a, b] = d4Swatch(name);
  const dim = status === "eaten";
  return (
    <div style={{
      width: size, height: size, borderRadius: size / 3, flexShrink: 0,
      background: `linear-gradient(135deg, ${a}, ${b})`,
      opacity: dim ? 0.45 : 1,
      display: "flex", alignItems: "center", justifyContent: "center",
      color: "#fff9ee", fontWeight: 800, fontSize: size * 0.42,
      fontFamily: "'Bricolage Grotesque', sans-serif",
      boxShadow: "inset 0 -6px 12px rgba(59,45,32,0.18)",
    }}>{name[0]}</div>
  );
}

function d4Status(s) {
  if (s === "eaten") return <D4Chip color={D4.olive} bg="#6d7a3e14">✓ eaten</D4Chip>;
  if (s === "cooked") return <D4Chip color={D4.honey} bg="#c98a2314">cooked</D4Chip>;
  if (s === "affected") return <D4Chip color={D4.terra} bg="#c2492a14">swap</D4Chip>;
  return <D4Chip>planned</D4Chip>;
}

function D4Week() {
  const W = window.MEAL.week;
  return (
    <div style={d4Base} data-screen-label="D4 Week plan">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
            <h1 style={{ ...d4Display, margin: 0, fontSize: 42, fontWeight: 800, letterSpacing: "-0.02em" }}>This week’s plan</h1>
            <D4Chip color={D4.olive} bg="#6d7a3e16">● active</D4Chip>
          </div>
          <div style={{ fontSize: 15, color: D4.faint, marginTop: 8 }}>{W.range} · {W.meta}</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d4Btn(false)}>History</button>
          <button style={d4Btn(false)}>Re-optimise</button>
        </div>
      </div>

      {/* Stats as warm pills */}
      <div style={{ display: "flex", gap: 12, marginTop: 22, flexWrap: "wrap" }}>
        {W.stats.map((s, i) => (
          <div key={i} style={{
            background: s.warn ? "#c98a2316" : D4.card, border: `1px solid ${s.warn ? D4.honey + "66" : D4.line}`,
            borderRadius: 14, padding: "12px 20px", display: "flex", alignItems: "baseline", gap: 8,
          }}>
            <span style={{ ...d4Display, fontSize: 19, fontWeight: 800, color: s.warn ? D4.honey : D4.ink }}>{s.value}</span>
            <span style={{ fontSize: 13, color: s.warn ? D4.honey : D4.faint, fontWeight: 600 }}>{s.warn ? "⚠ " : ""}{s.label}{s.sub ? ` · ${s.sub}` : ""}</span>
          </div>
        ))}
      </div>

      {/* Fix card */}
      <div style={{
        marginTop: 22, background: D4.card, borderRadius: 20, padding: "24px 28px",
        border: `1.5px solid ${D4.terra}44`, boxShadow: "0 14px 34px rgba(59,45,32,0.08)",
      }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
          <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
            <div style={{ width: 40, height: 40, borderRadius: 13, background: "#c2492a16", display: "flex", alignItems: "center", justifyContent: "center", color: D4.terra, fontSize: 18 }}>⇄</div>
            <div>
              <div style={{ ...d4Display, fontSize: 19, fontWeight: 800 }}>Suggested fix — {W.fix.title.toLowerCase()}</div>
              <div style={{ fontSize: 13.5, color: D4.faint, marginTop: 4 }}>{W.fix.sub}</div>
            </div>
          </div>
          <D4Chip color={D4.terra} bg="#c2492a10">2 swaps</D4Chip>
        </div>
        <div style={{ marginTop: 18, display: "grid", gap: 10, marginLeft: 54 }}>
          {W.fix.swaps.map((sw, i) => (
            <div key={i} style={{ display: "flex", alignItems: "center", gap: 12, fontSize: 15 }}>
              <span style={{ width: 80, color: D4.faint, fontSize: 13, fontWeight: 700 }}>{sw.slot}</span>
              <span style={{ textDecoration: "line-through", color: D4.faint }}>{sw.from}</span>
              <span style={{ color: D4.terra, fontWeight: 800 }}>→</span>
              {d4Thumb(sw.to, 26)}
              <span style={{ fontWeight: 700 }}>{sw.to}</span>
              {sw.note && <D4Chip color={D4.olive} bg="#6d7a3e10" style={{ fontSize: 10.5 }}>{sw.note}</D4Chip>}
            </div>
          ))}
        </div>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 18, paddingTop: 16, borderTop: `1px dashed ${D4.line}`, marginLeft: 54 }}>
          <span style={{ fontSize: 13.5, color: D4.faint, fontWeight: 600 }}>{W.fix.impact}</span>
          <div style={{ display: "flex", gap: 10 }}>
            <button style={d4Btn(false)}>Dismiss</button>
            <button style={d4Btn(true)}>Accept changes</button>
          </div>
        </div>
      </div>

      {/* Week as 7 day-columns of stacked meal cards */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 10, marginTop: 26 }}>
        {W.days.map((day, i) => (
          <div key={i} style={{
            background: day.today ? D4.card : "transparent",
            border: day.today ? `1.5px solid ${D4.olive}66` : `1px solid transparent`,
            borderRadius: 18, padding: "12px 8px 10px",
            boxShadow: day.today ? "0 12px 28px rgba(59,45,32,0.09)" : "none",
          }}>
            <div style={{ textAlign: "center", marginBottom: 10 }}>
              <div style={{ ...d4Display, fontSize: 15, fontWeight: 800, color: day.today ? D4.olive : D4.ink }}>{day.d} {day.n}</div>
              {day.today && <div style={{ fontSize: 10, fontWeight: 800, letterSpacing: "0.1em", color: D4.olive }}>TODAY</div>}
            </div>
            <div style={{ display: "grid", gap: 7 }}>
              {["b", "l", "din"].map((k) => {
                const meal = day[k];
                const affected = meal.s === "affected";
                return (
                  <div key={k} style={{
                    background: affected ? "#c2492a0d" : D4.card,
                    border: `1px solid ${affected ? D4.terra + "77" : D4.line}`,
                    borderRadius: 13, padding: "9px 9px", display: "flex", gap: 8, alignItems: "center",
                    minHeight: 56,
                  }}>
                    {d4Thumb(meal.name, 30, meal.s)}
                    <div style={{ minWidth: 0 }}>
                      <div style={{
                        fontSize: 12.5, fontWeight: 700, lineHeight: 1.25,
                        color: affected ? D4.terra : meal.s === "eaten" ? D4.faint : D4.ink,
                      }}>{meal.name}</div>
                      <div style={{ display: "flex", gap: 4, marginTop: 3, alignItems: "center" }}>
                        <span style={{
                          width: 6, height: 6, borderRadius: 99, flexShrink: 0,
                          background: meal.s === "eaten" ? D4.olive : meal.s === "cooked" ? D4.honey : meal.s === "affected" ? D4.terra : "transparent",
                          border: meal.s === "planned" ? `1px solid ${D4.faint}` : "none",
                        }}></span>
                        <span style={{ fontSize: 9.5, fontWeight: 700, color: D4.faint, letterSpacing: "0.04em" }}>
                          {meal.s.toUpperCase()}{meal.batch ? " · ≋" : ""}
                        </span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>
      <div style={{ display: "flex", gap: 22, marginTop: 18, justifyContent: "center", fontSize: 12.5, color: D4.faint, fontWeight: 600 }}>
        <span><span style={{ color: D4.olive }}>●</span> eaten</span>
        <span><span style={{ color: D4.honey }}>●</span> cooked</span>
        <span>○ planned</span>
        <span><span style={{ color: D4.terra }}>●</span> affected by suggestion</span>
        <span>≋ batch-cook link</span>
      </div>
    </div>
  );
}

function D4Day() {
  const D = window.MEAL.day;
  return (
    <div style={d4Base} data-screen-label="D4 Daily dashboard">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <h1 style={{ ...d4Display, margin: 0, fontSize: 42, fontWeight: 800, letterSpacing: "-0.02em" }}>{D.greeting} <span style={{ color: D4.terra }}>☀</span></h1>
          <div style={{ fontSize: 15, color: D4.faint, marginTop: 8 }}>{D.date} · {D.progress}</div>
        </div>
        <D4Chip color={D4.olive} bg="#6d7a3e16" style={{ padding: "8px 16px", fontSize: 13 }}>● Plan active</D4Chip>
      </div>

      {/* Meal cards with big thumbs */}
      <div style={{ display: "grid", gap: 12, marginTop: 26 }}>
        {D.meals.map((m, i) => (
          <div key={i} style={{
            background: D4.card, borderRadius: 20, border: `1px solid ${D4.line}`,
            padding: "18px 22px", display: "grid", gridTemplateColumns: "auto 86px 1fr auto", gap: 20, alignItems: "center",
            boxShadow: "0 10px 26px rgba(59,45,32,0.06)",
          }}>
            <div style={{ width: 64 }}>
              <div style={{ ...d4Display, fontSize: 20, fontWeight: 800 }}>{m.time}</div>
              <div style={{ fontSize: 11.5, fontWeight: 700, color: D4.faint, letterSpacing: "0.06em" }}>{m.slot.toUpperCase()}</div>
            </div>
            {d4Thumb(m.name, 64, m.status === "eaten" ? "eaten" : undefined)}
            <div>
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <span style={{ ...d4Display, fontSize: 20, fontWeight: 800 }}>{m.name}</span>
                {m.batch && <D4Chip color={D4.olive} bg="#6d7a3e10" style={{ fontSize: 10.5 }}>≋ batch</D4Chip>}
              </div>
              <div style={{ fontSize: 14, color: D4.faint, marginTop: 4 }}>{m.who}</div>
              {m.alert && <div style={{ fontSize: 13.5, color: D4.honey, marginTop: 4, fontWeight: 700 }}>❄ {m.alert}</div>}
            </div>
            <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
              {d4Status(m.status)}
              {m.action && <button style={d4Btn(m.status === "planned")}>{m.action}</button>}
            </div>
          </div>
        ))}
      </div>

      {/* Lower grid */}
      <div style={{ display: "grid", gridTemplateColumns: "1.25fr 1fr", gap: 14, marginTop: 14 }}>
        <div style={{ background: D4.card, borderRadius: 20, border: `1px solid ${D4.line}`, padding: "22px 26px" }}>
          <div style={{ ...d4Display, fontSize: 17, fontWeight: 800 }}>Today’s nutrition</div>
          <div style={{ display: "grid", gap: 14, marginTop: 16 }}>
            {D.nutrition.map((n, i) => {
              const pct = Math.min(100, (n.val / n.max) * 100);
              const col = n.behind ? D4.honey : D4.olive;
              return (
                <div key={i}>
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                    <span style={{ fontSize: 14, fontWeight: 700 }}>{n.label}</span>
                    <span style={{ fontSize: 13, color: n.behind ? D4.honey : D4.faint, fontWeight: 700 }}>{n.fmt}{n.behind ? " · behind" : ""}</span>
                  </div>
                  <div style={{ height: 9, background: D4.cream, borderRadius: 99 }}>
                    <div style={{ height: 9, width: `${pct}%`, background: `linear-gradient(90deg, ${col}cc, ${col})`, borderRadius: 99 }}></div>
                  </div>
                </div>
              );
            })}
          </div>
          <button style={{ ...d4Btn(false), marginTop: 18 }}>+ Log a snack</button>
        </div>
        <div style={{ display: "grid", gap: 14, alignContent: "start" }}>
          <div style={{ background: D4.card, borderRadius: 20, border: `1px solid ${D4.line}`, padding: "20px 24px" }}>
            <div style={{ ...d4Display, fontSize: 17, fontWeight: 800 }}>Needs attention</div>
            <div style={{ display: "grid", gap: 11, marginTop: 13 }}>
              {D.attention.map((a, i) => (
                <div key={i} style={{ display: "flex", gap: 10, fontSize: 13.5, lineHeight: 1.45, fontWeight: 600 }}>
                  <span style={{ color: a.kind === "expiry" ? D4.terra : a.kind === "defrost" ? D4.honey : D4.olive }}>{a.kind === "expiry" ? "⚠" : a.kind === "defrost" ? "❄" : "✦"}</span>
                  <span>{a.text}</span>
                </div>
              ))}
            </div>
          </div>
          <div style={{ background: D4.olive, color: "#f8f4e6", borderRadius: 20, padding: "20px 24px" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
              <span style={{ ...d4Display, fontSize: 15, fontWeight: 800 }}>Week budget</span>
              <span style={{ ...d4Display, fontSize: 19, fontWeight: 800 }}>{D.budget.spent} <span style={{ opacity: 0.65, fontSize: 13 }}>of {D.budget.total}</span></span>
            </div>
            <div style={{ height: 9, background: "rgba(248,244,230,0.25)", borderRadius: 99, marginTop: 12 }}>
              <div style={{ height: 9, width: `${D.budget.pct}%`, background: "#f3e8d2", borderRadius: 99 }}></div>
            </div>
            <div style={{ fontSize: 12.5, marginTop: 9, opacity: 0.85, fontWeight: 600 }}>{D.budget.note}</div>
          </div>
        </div>
      </div>

      {/* Suggestion */}
      <div style={{
        marginTop: 14, background: "#c2492a0d", border: `1.5px solid ${D4.terra}33`, borderRadius: 20,
        padding: "18px 24px", display: "flex", justifyContent: "space-between", alignItems: "center",
      }}>
        <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
          <div style={{ width: 38, height: 38, borderRadius: 12, background: "#c2492a16", display: "flex", alignItems: "center", justifyContent: "center", color: D4.terra }}>✦</div>
          <div>
            <div style={{ ...d4Display, fontSize: 16.5, fontWeight: 800 }}>{D.suggestion.title}</div>
            <div style={{ fontSize: 13, color: D4.faint, marginTop: 3, fontWeight: 600 }}>{D.suggestion.sub}</div>
          </div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d4Btn(false)}>Review</button>
          <button style={d4Btn(true)}>Accept</button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { D4Week, D4Day });
