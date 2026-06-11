// Direction 5 — "Nocturne": premium dark bento. Charcoal glass layers, luminous
// coral + mint, Young Serif display + Albert Sans. Evening mode.
const D5 = {
  bg: "#121217",
  glass: "rgba(255,255,255,0.045)",
  glassHi: "rgba(255,255,255,0.08)",
  line: "rgba(255,255,255,0.09)",
  text: "#ececf1",
  faint: "#8e8e9c",
  coral: "#ff8366",
  mint: "#7fe0b2",
  gold: "#e8c47c",
  lilac: "#b9a7f5",
};

const d5Base = {
  fontFamily: "'Albert Sans', sans-serif",
  background: `radial-gradient(1100px 500px at 80% -10%, rgba(255,131,102,0.10), transparent 60%), radial-gradient(900px 500px at 0% 110%, rgba(127,224,178,0.07), transparent 60%), ${D5.bg}`,
  color: D5.text,
  width: "100%",
  height: "100%",
  padding: "48px 56px",
  overflow: "hidden",
  position: "relative",
};

const d5Serif = { fontFamily: "'Young Serif', serif", fontWeight: 400 };

function D5Card({ children, style = {}, glow }) {
  return (
    <div style={{
      background: D5.glass, border: `1px solid ${glow ? glow + "44" : D5.line}`,
      borderRadius: 18, padding: "22px 24px", position: "relative",
      boxShadow: glow ? `0 0 40px ${glow}14, inset 0 1px 0 rgba(255,255,255,0.06)` : "inset 0 1px 0 rgba(255,255,255,0.05)",
      ...style,
    }}>{children}</div>
  );
}

function D5Cap({ children, color = D5.faint, style = {} }) {
  return <div style={{ fontSize: 11, letterSpacing: "0.16em", textTransform: "uppercase", fontWeight: 600, color, ...style }}>{children}</div>;
}

function d5Btn(primary) {
  return {
    fontFamily: "'Albert Sans', sans-serif", fontSize: 13, fontWeight: 600,
    padding: "10px 20px", cursor: "pointer", borderRadius: 12,
    background: primary ? `linear-gradient(135deg, ${D5.coral}, #e85f43)` : D5.glassHi,
    color: primary ? "#241008" : D5.text,
    border: primary ? "none" : `1px solid ${D5.line}`,
    boxShadow: primary ? `0 6px 22px ${D5.coral}44` : "none",
  };
}

function d5StatusColor(s) {
  return s === "eaten" ? D5.mint : s === "cooked" ? D5.gold : s === "affected" ? D5.coral : D5.faint;
}

function D5Week() {
  const W = window.MEAL.week;
  return (
    <div style={d5Base} data-screen-label="D5 Week plan">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <D5Cap color={D5.mint}>Plan active · {W.range}</D5Cap>
          <h1 style={{ ...d5Serif, margin: "10px 0 0", fontSize: 42, letterSpacing: "-0.01em" }}>This week’s plan</h1>
          <div style={{ fontSize: 14, color: D5.faint, marginTop: 8 }}>{W.meta}</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d5Btn(false)}>History</button>
          <button style={d5Btn(false)}>Re-optimise</button>
        </div>
      </div>

      {/* Bento stats */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 12, marginTop: 26 }}>
        {W.stats.map((s, i) => (
          <D5Card key={i} glow={s.warn ? D5.gold : undefined} style={{ padding: "16px 20px" }}>
            <D5Cap color={s.warn ? D5.gold : D5.faint}>{s.label}</D5Cap>
            <div style={{ ...d5Serif, fontSize: 24, marginTop: 8, color: s.warn ? D5.gold : D5.text }}>{s.value}</div>
            {s.sub && <div style={{ fontSize: 12, color: D5.faint, marginTop: 3 }}>{s.sub}</div>}
          </D5Card>
        ))}
      </div>

      {/* Fix card */}
      <D5Card glow={D5.coral} style={{ marginTop: 14 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
          <div>
            <D5Cap color={D5.coral}>Suggested fix</D5Cap>
            <div style={{ ...d5Serif, fontSize: 21, marginTop: 8 }}>Chicken breast marked spoiled</div>
            <div style={{ fontSize: 13, color: D5.faint, marginTop: 5 }}>{W.fix.sub}</div>
          </div>
          <span style={{ fontSize: 12.5, color: D5.coral, background: `${D5.coral}18`, borderRadius: 99, padding: "6px 14px", fontWeight: 600 }}>{W.fix.impact}</span>
        </div>
        <div style={{ display: "grid", gap: 10, marginTop: 16 }}>
          {W.fix.swaps.map((sw, i) => (
            <div key={i} style={{
              display: "flex", alignItems: "center", gap: 12, fontSize: 14.5,
              background: D5.glass, border: `1px solid ${D5.line}`, borderRadius: 12, padding: "12px 16px",
            }}>
              <span style={{ width: 84, color: D5.faint, fontSize: 12.5, fontWeight: 600 }}>{sw.slot}</span>
              <span style={{ textDecoration: "line-through", color: D5.faint }}>{sw.from}</span>
              <span style={{ color: D5.coral }}>→</span>
              <span style={{ fontWeight: 600 }}>{sw.to}</span>
              {sw.note && <span style={{ fontSize: 11.5, color: D5.mint, background: `${D5.mint}14`, borderRadius: 99, padding: "3px 10px", fontWeight: 600 }}>{sw.note}</span>}
            </div>
          ))}
        </div>
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 10, marginTop: 16 }}>
          <button style={d5Btn(false)}>Dismiss</button>
          <button style={d5Btn(true)}>Accept changes</button>
        </div>
      </D5Card>

      {/* Week — 7 luminous day columns */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 10, marginTop: 20 }}>
        {W.days.map((day, i) => (
          <div key={i} style={{
            background: day.today ? D5.glassHi : "transparent",
            border: `1px solid ${day.today ? D5.lilac + "55" : "transparent"}`,
            borderRadius: 16, padding: "10px 7px",
            boxShadow: day.today ? `0 0 36px ${D5.lilac}18` : "none",
          }}>
            <div style={{ textAlign: "center", marginBottom: 10 }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: day.today ? D5.lilac : D5.faint, letterSpacing: "0.08em" }}>{day.d.toUpperCase()}</div>
              <div style={{ ...d5Serif, fontSize: 22, marginTop: 2, color: day.today ? D5.lilac : D5.text }}>{day.n}</div>
            </div>
            <div style={{ display: "grid", gap: 6 }}>
              {["b", "l", "din"].map((k) => {
                const meal = day[k];
                const c = d5StatusColor(meal.s);
                const planned = meal.s === "planned";
                return (
                  <div key={k} style={{
                    background: planned ? "transparent" : `${c}0e`,
                    border: `1px solid ${planned ? D5.line : c + "3a"}`,
                    borderRadius: 11, padding: "9px 10px", minHeight: 58,
                    display: "flex", flexDirection: "column", justifyContent: "space-between", gap: 5,
                  }}>
                    <span style={{
                      fontSize: 12, fontWeight: 600, lineHeight: 1.3,
                      color: meal.s === "affected" ? D5.coral : meal.s === "eaten" ? D5.faint : D5.text,
                    }}>{meal.name}</span>
                    <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
                      <span style={{
                        width: 6, height: 6, borderRadius: 99,
                        background: planned ? "transparent" : c,
                        border: planned ? `1px solid ${D5.faint}` : "none",
                        boxShadow: planned ? "none" : `0 0 8px ${c}`,
                      }}></span>
                      {meal.batch && <span style={{ fontSize: 10, color: D5.mint, fontWeight: 700 }}>≋</span>}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>
      <div style={{ display: "flex", gap: 24, marginTop: 16, justifyContent: "center", fontSize: 12, color: D5.faint, fontWeight: 500 }}>
        <span><span style={{ color: D5.mint }}>●</span> eaten</span>
        <span><span style={{ color: D5.gold }}>●</span> cooked</span>
        <span>○ planned</span>
        <span><span style={{ color: D5.coral }}>●</span> affected by suggestion</span>
        <span><span style={{ color: D5.mint }}>≋</span> batch-cook link</span>
      </div>
    </div>
  );
}

function D5Day() {
  const D = window.MEAL.day;
  return (
    <div style={d5Base} data-screen-label="D5 Daily dashboard">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <D5Cap color={D5.lilac}>{D.date} · day 4 of 7</D5Cap>
          <h1 style={{ ...d5Serif, margin: "10px 0 0", fontSize: 42 }}>Good evening, Iren <span style={{ fontSize: 30 }}>☾</span></h1>
        </div>
        <span style={{ fontSize: 12.5, color: D5.mint, background: `${D5.mint}14`, border: `1px solid ${D5.mint}33`, borderRadius: 99, padding: "7px 16px", fontWeight: 600 }}>● Plan active</span>
      </div>

      {/* Bento grid: meals rail (left, tall) + side stack */}
      <div style={{ display: "grid", gridTemplateColumns: "1.5fr 1fr", gap: 14, marginTop: 28 }}>
        {/* Meals */}
        <div style={{ display: "grid", gap: 12, alignContent: "start" }}>
          {D.meals.map((m, i) => {
            const c = d5StatusColor(m.status);
            return (
              <D5Card key={i} glow={m.status === "planned" ? D5.lilac : undefined} style={{ padding: "18px 22px" }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
                  <div style={{ display: "flex", gap: 16 }}>
                    <div style={{ textAlign: "center", minWidth: 52 }}>
                      <div style={{ ...d5Serif, fontSize: 19 }}>{m.time}</div>
                      <D5Cap style={{ fontSize: 9.5, marginTop: 3 }}>{m.slot}</D5Cap>
                    </div>
                    <div>
                      <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
                        <span style={{ fontSize: 17, fontWeight: 600 }}>{m.name}</span>
                        {m.batch && <span style={{ fontSize: 11, color: D5.mint, fontWeight: 700 }}>≋ batch</span>}
                      </div>
                      <div style={{ fontSize: 13, color: D5.faint, marginTop: 4 }}>{m.who}</div>
                      {m.alert && <div style={{ fontSize: 12.5, color: D5.gold, marginTop: 5, fontWeight: 600 }}>❄ {m.alert}</div>}
                    </div>
                  </div>
                  <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 9 }}>
                    <span style={{ fontSize: 11.5, color: c, background: `${c}16`, borderRadius: 99, padding: "4px 12px", fontWeight: 700, letterSpacing: "0.04em" }}>
                      {m.status === "eaten" ? "✓ EATEN" : m.status.toUpperCase()}
                    </span>
                    {m.action && <button style={d5Btn(m.status === "planned")}>{m.action}</button>}
                  </div>
                </div>
              </D5Card>
            );
          })}

          {/* Suggestion */}
          <D5Card glow={D5.coral} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "18px 22px" }}>
            <div>
              <div style={{ display: "flex", gap: 9, alignItems: "center" }}>
                <span style={{ color: D5.coral }}>✦</span>
                <span style={{ fontSize: 15, fontWeight: 600 }}>{D.suggestion.title}</span>
              </div>
              <div style={{ fontSize: 12.5, color: D5.faint, marginTop: 4, marginLeft: 23 }}>{D.suggestion.sub}</div>
            </div>
            <div style={{ display: "flex", gap: 9 }}>
              <button style={d5Btn(false)}>Review</button>
              <button style={d5Btn(true)}>Accept</button>
            </div>
          </D5Card>
        </div>

        {/* Side stack */}
        <div style={{ display: "grid", gap: 12, alignContent: "start" }}>
          <D5Card>
            <D5Cap>Today’s nutrition</D5Cap>
            <div style={{ display: "grid", gap: 13, marginTop: 16 }}>
              {D.nutrition.map((n, i) => {
                const pct = Math.min(100, (n.val / n.max) * 100);
                const col = n.behind ? D5.gold : D5.mint;
                return (
                  <div key={i}>
                    <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                      <span style={{ fontSize: 13, fontWeight: 600 }}>{n.label}</span>
                      <span style={{ fontSize: 12, color: n.behind ? D5.gold : D5.faint }}>{n.fmt}{n.behind ? " · behind" : ""}</span>
                    </div>
                    <div style={{ height: 6, background: "rgba(255,255,255,0.07)", borderRadius: 99 }}>
                      <div style={{ height: 6, width: `${pct}%`, background: col, borderRadius: 99, boxShadow: `0 0 10px ${col}66` }}></div>
                    </div>
                  </div>
                );
              })}
            </div>
            <button style={{ ...d5Btn(false), marginTop: 16 }}>+ Log a snack</button>
          </D5Card>
          <D5Card glow={D5.gold}>
            <D5Cap color={D5.gold}>Needs attention</D5Cap>
            <div style={{ display: "grid", gap: 10, marginTop: 13 }}>
              {D.attention.map((a, i) => (
                <div key={i} style={{ display: "flex", gap: 9, fontSize: 13, lineHeight: 1.45 }}>
                  <span style={{ color: a.kind === "expiry" ? D5.coral : a.kind === "defrost" ? D5.gold : D5.lilac }}>{a.kind === "expiry" ? "⚠" : a.kind === "defrost" ? "❄" : "✦"}</span>
                  <span>{a.text}</span>
                </div>
              ))}
            </div>
          </D5Card>
          <D5Card>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
              <D5Cap>Week budget</D5Cap>
              <span style={{ ...d5Serif, fontSize: 18 }}>{D.budget.spent} <span style={{ color: D5.faint, fontSize: 13 }}>of {D.budget.total}</span></span>
            </div>
            <div style={{ height: 6, background: "rgba(255,255,255,0.07)", borderRadius: 99, marginTop: 12 }}>
              <div style={{ height: 6, width: `${D.budget.pct}%`, background: `linear-gradient(90deg, ${D5.mint}, ${D5.gold})`, borderRadius: 99 }}></div>
            </div>
            <div style={{ fontSize: 12, color: D5.mint, marginTop: 8, fontWeight: 600 }}>{D.budget.note}</div>
          </D5Card>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { D5Week, D5Day });
