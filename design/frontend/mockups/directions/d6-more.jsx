// D6 hard-case screens: plan generation (candidate comparison), feedback routing
// confirmation (confidence tiers), grocery list + live order. Reuses the D6 palette
// and helpers declared in d6-hybrid.jsx (loaded first).

function D6Gen() {
  const G = window.MEAL.gen;
  return (
    <div style={d6Base} data-screen-label="D6 Plan generation">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <span style={{ fontFamily: "'Schibsted Grotesk', sans-serif", fontWeight: 700, fontSize: 32, letterSpacing: "-0.015em" }}>{G.title}</span>
          <div style={{ fontSize: 13.5, color: D6.muted, marginTop: 6 }}>{G.context}</div>
        </div>
        <button style={d6Btn(false)}>Adjust constraints</button>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 20, background: "#eef0e2", border: `1px solid #d4d8b8`, borderRadius: 10, padding: "12px 18px" }}>
        <span style={{ color: D6.olive, fontWeight: 700, fontSize: 14 }}>✓</span>
        <span style={{ fontSize: 13.5, color: "#41502a" }}>{G.feasibility}</span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(5, 1fr)", gap: 12, marginTop: 22 }}>
        {G.candidates.map((c) => (
          <div key={c.id} style={{
            background: D6.card, borderRadius: 12, padding: "16px 16px 14px", position: "relative",
            border: c.recommended ? `2px solid ${D6.terra}` : `1px solid ${D6.line}`,
          }}>
            {c.recommended && (
              <div style={{ position: "absolute", top: -11, left: 14, background: D6.terra, color: "#fff7ef", fontSize: 10, fontWeight: 700, letterSpacing: "0.1em", borderRadius: 999, padding: "3px 10px" }}>RECOMMENDED</div>
            )}
            <D6Label>Candidate {c.id}</D6Label>
            <div style={{ margin: "10px 0 2px", display: "flex", alignItems: "baseline", gap: 5 }}>
              <D6Num size={34} color={c.recommended ? D6.terra : D6.ink}>{c.fit}</D6Num>
              <span style={{ fontSize: 12, color: D6.muted }}>/ 100</span>
            </div>
            <div style={{ fontSize: 11.5, color: D6.muted, marginBottom: 12 }}>preference fit</div>
            <div style={{ display: "grid", gap: 7, fontSize: 12.5, borderTop: `1px solid ${D6.line}`, paddingTop: 11 }}>
              <div><span style={{ color: D6.muted }}>Nutrition · </span><span style={{ color: c.nutrition.startsWith("on target") ? D6.olive : D6.amber, fontWeight: 600 }}>{c.nutrition}</span></div>
              <div><span style={{ color: D6.muted }}>Cost · </span><span style={{ fontWeight: 600 }}>{c.cost}</span><div style={{ fontSize: 11, color: D6.muted }}>{c.conf}</div></div>
              <div><span style={{ color: D6.muted }}>Variety · </span><span style={{ fontWeight: 600 }}>{c.variety}</span></div>
              <div><span style={{ color: D6.muted }}>Prep load · </span><span style={{ fontWeight: 600 }}>{c.prep}</span></div>
            </div>
            {c.warn && (
              <div style={{ marginTop: 11, fontSize: 11, fontWeight: 700, color: D6.amber, border: `1px solid ${D6.amber}`, borderRadius: 999, padding: "3px 9px", display: "inline-block" }}>{c.warn}</div>
            )}
          </div>
        ))}
      </div>

      <div style={{ marginTop: 22, background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "20px 26px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span style={{ width: 8, height: 8, borderRadius: "50%", background: D6.terra }}></span>
          <D6Label color={D6.terraDark}>Why candidate 2</D6Label>
        </div>
        <div style={{ marginTop: 8, maxWidth: 880 }}><D6Serif size={21}>{G.reasoning}</D6Serif></div>
        <div style={{ marginTop: 16, borderTop: `1px solid ${D6.line}`, paddingTop: 14 }}>
          <D6Label>Dinner line-up · candidate 2</D6Label>
          <div style={{ display: "flex", gap: 8, marginTop: 10, flexWrap: "wrap" }}>
            {G.preview.map((p, i) => (
              <span key={i} style={{ fontSize: 12.5, border: `1px solid ${D6.lineHi}`, borderRadius: 999, padding: "5px 13px", background: D6.bg }}>{p}</span>
            ))}
          </div>
        </div>
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 10, marginTop: 18 }}>
          <button style={d6Btn(false)}>Regenerate all</button>
          <button style={d6Btn(false)}>Compare day by day</button>
          <button style={d6Btn(true)}>Accept candidate 2</button>
        </div>
      </div>
    </div>
  );
}

function d6Tier(tier) {
  if (tier === "high") return { mark: "✓", color: D6.olive, label: "routed" };
  if (tier === "mid") return { mark: "?", color: D6.amber, label: "check me" };
  return { mark: "…", color: D6.terra, label: "needs you" };
}

function D6Feedback() {
  const F = window.MEAL.feedback;
  return (
    <div style={{ ...d6Base, background: "#3a342a", display: "flex", alignItems: "center", justifyContent: "center", padding: "56px" }} data-screen-label="D6 Feedback routing">
      <div style={{ background: D6.bg, borderRadius: 16, padding: "30px 36px", width: 760, boxShadow: "0 30px 70px rgba(20,16,10,0.45)" }}>
        <D6Label color={D6.terraDark}>Feedback received</D6Label>
        <div style={{ marginTop: 12, background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 10, padding: "14px 18px", fontSize: 15, color: D6.ink }}>
          “{F.input}”
        </div>

        <div style={{ marginTop: 22 }}>
          <D6Label>I heard three things</D6Label>
          <div style={{ display: "grid", gap: 12, marginTop: 12 }}>
            {F.routes.map((r, i) => {
              const t = d6Tier(r.tier);
              return (
                <div key={i} style={{ display: "flex", gap: 14, alignItems: "flex-start", border: `1px solid ${D6.line}`, borderRadius: 12, padding: "14px 18px", background: D6.card }}>
                  <span style={{ width: 26, height: 26, borderRadius: "50%", background: D6.bg, border: `1.5px solid ${t.color}`, color: t.color, fontWeight: 700, fontSize: 13, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0, marginTop: 2 }}>{t.mark}</span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
                      <span style={{ fontSize: 14, fontWeight: 700 }}>{r.dest}</span>
                      <span style={{ fontSize: 11.5, color: D6.muted }}>confidence {r.conf}</span>
                      <span style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.1em", textTransform: "uppercase", color: t.color }}>{t.label}</span>
                    </div>
                    {r.action && <div style={{ fontSize: 13.5, color: D6.ink, marginTop: 5 }}>{r.action}</div>}
                    {r.question && (
                      <div style={{ marginTop: 5 }}>
                        <D6Serif size={17}>{r.question}</D6Serif>
                        <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
                          {r.options.map((o, j) => (
                            <button key={j} style={d6Btn(false)}>{o}</button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                  {r.tier !== "low" && (
                    <button style={{ ...d6Btn(false), fontSize: 12, padding: "6px 12px", color: r.tier === "mid" ? D6.amber : D6.muted, borderColor: r.tier === "mid" ? D6.amber : D6.lineHi, flexShrink: 0 }}>
                      {r.tier === "mid" ? "Correct this" : "This isn't right"}
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 20 }}>
          <span style={{ fontSize: 12, color: D6.muted }}>{F.note}</span>
          <button style={d6Btn(true)}>Done</button>
        </div>
      </div>
    </div>
  );
}

function d6Bought(state) {
  if (state === "bought") return <span style={{ width: 18, height: 18, borderRadius: 5, background: D6.olive, color: "#f3f5e8", fontSize: 11, fontWeight: 700, display: "inline-flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>✓</span>;
  return <span style={{ width: 18, height: 18, borderRadius: 5, border: `1.5px solid ${D6.lineHi}`, display: "inline-flex", flexShrink: 0, boxSizing: "border-box" }}></span>;
}

function D6Grocery() {
  const Gr = window.MEAL.grocery;
  return (
    <div style={d6Base} data-screen-label="D6 Groceries">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <span style={{ fontFamily: "'Schibsted Grotesk', sans-serif", fontWeight: 700, fontSize: 32, letterSpacing: "-0.015em" }}>Groceries</span>
          <div style={{ fontSize: 13.5, color: D6.muted, marginTop: 6 }}>From this week's plan · recalculated after the Thursday fix</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d6Btn(false)}>Export</button>
          <button style={d6Btn(false)}>Recalculate</button>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", marginTop: 24, border: `1px solid ${D6.line}`, borderRadius: 12, background: D6.card, overflow: "hidden" }}>
        {Gr.stats.map((s, i) => (
          <div key={i} style={{ padding: "16px 22px", borderLeft: i > 0 ? `1px solid ${D6.line}` : "none" }}>
            <D6Label color={s.warn ? D6.amber : D6.muted}>{s.label}</D6Label>
            <div style={{ marginTop: 7 }}><D6Num size={22} color={s.warn ? D6.amber : D6.ink}>{s.value}</D6Num></div>
            {s.sub && <div style={{ fontSize: 12, color: D6.muted, marginTop: 4 }}>{s.sub}</div>}
          </div>
        ))}
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1.5fr 1fr", gap: 24, marginTop: 24, alignItems: "start" }}>
        <div>
          {Gr.groups.map((g, gi) => (
            <div key={gi} style={{ marginBottom: 22 }}>
              <div style={{ borderBottom: `2px solid ${D6.ink}`, paddingBottom: 7 }}><D6Label color={D6.ink}>{g.name}</D6Label></div>
              {g.items.map((it, ii) => (
                <div key={ii} style={{ display: "flex", alignItems: "center", gap: 12, padding: "11px 0", borderBottom: `1px solid ${D6.line}` }}>
                  {d6Bought(it.state)}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <span style={{ fontSize: 14.5, fontWeight: 500, color: it.state === "bought" ? D6.muted : D6.ink, textDecoration: it.state === "bought" ? "line-through" : "none", textDecorationThickness: 1 }}>{it.n}</span>
                    {it.note && <span style={{ fontSize: 11, fontWeight: 600, color: D6.terraDark, background: "#f6e3d9", borderRadius: 999, padding: "2px 9px", marginLeft: 9 }}>{it.note}</span>}
                  </div>
                  <span style={{ fontSize: 12.5, color: D6.muted, width: 78 }}>{it.q}</span>
                  <span style={{ fontSize: 13.5, fontWeight: 600, width: 60, textAlign: "right", color: D6.ink }}>{it.price}</span>
                  <span style={{ width: 58, textAlign: "right" }}>
                    {it.stale && <span style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", color: D6.amber }}>STALE</span>}
                  </span>
                </div>
              ))}
            </div>
          ))}
          <div style={{ fontSize: 12, color: D6.muted }}>Prices marked stale haven't been observed in 2+ weeks — projections regress them toward neutral.</div>
        </div>

        <div style={{ display: "grid", gap: 18 }}>
          <div style={{ background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "18px 22px" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
              <D6Label>Active order</D6Label>
              <D6Chip color={D6.olive}>{Gr.order.state}</D6Chip>
            </div>
            <div style={{ fontSize: 15, fontWeight: 600, marginTop: 10 }}>{Gr.order.provider}</div>
            <div style={{ fontSize: 13, color: D6.muted, marginTop: 3 }}>{Gr.order.eta}</div>
            <div style={{ display: "flex", alignItems: "center", gap: 0, marginTop: 16 }}>
              {Gr.order.steps.map((st, i) => (
                <div key={i} style={{ display: "flex", alignItems: "center", flex: i < Gr.order.steps.length - 1 ? 1 : "none" }}>
                  <span style={{ width: 10, height: 10, borderRadius: "50%", flexShrink: 0, background: i <= Gr.order.at ? D6.olive : "transparent", border: `1.5px solid ${i <= Gr.order.at ? D6.olive : D6.lineHi}`, boxSizing: "border-box" }}></span>
                  {i < Gr.order.steps.length - 1 && <span style={{ flex: 1, height: 1.5, background: i < Gr.order.at ? D6.olive : D6.line }}></span>}
                </div>
              ))}
            </div>
            <div style={{ display: "flex", justifyContent: "space-between", marginTop: 7, fontSize: 10.5, color: D6.muted }}>
              {Gr.order.steps.map((st, i) => (
                <span key={i} style={{ fontWeight: i === Gr.order.at ? 700 : 400, color: i === Gr.order.at ? D6.olive : D6.muted }}>{st}</span>
              ))}
            </div>
            <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
              <button style={{ ...d6Btn(false), fontSize: 12, padding: "6px 13px" }}>Refresh status</button>
              <button style={{ ...d6Btn(false), fontSize: 12, padding: "6px 13px" }}>Cancel order</button>
            </div>
          </div>

          <div style={{ background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "18px 22px" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ width: 8, height: 8, borderRadius: "50%", background: D6.terra }}></span>
              <D6Label color={D6.terraDark}>Substitution to resolve</D6Label>
            </div>
            <div style={{ marginTop: 10 }}><D6Serif size={18}>{Gr.substitution.reason} — swap suggested</D6Serif></div>
            <div style={{ marginTop: 10, fontSize: 13.5 }}>
              <span style={{ textDecoration: "line-through", color: D6.muted }}>{Gr.substitution.from}</span>
              <span style={{ color: D6.lineHi }}> → </span>
              <span style={{ fontWeight: 600 }}>{Gr.substitution.to}</span>
              <span style={{ fontSize: 12, color: D6.olive, fontWeight: 700, marginLeft: 8 }}>{Gr.substitution.delta}</span>
            </div>
            <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
              <button style={{ ...d6Btn(false), fontSize: 12, padding: "6px 13px" }}>Reject</button>
              <button style={{ ...d6Btn(true), fontSize: 12, padding: "6px 13px" }}>Accept swap</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function D6Recipe() {
  const R = window.MEAL.recipe;
  return (
    <div style={d6Base} data-screen-label="D6 Recipe detail">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 18 }}>
        <span style={{ fontSize: 13.5, color: D6.muted }}>← Recipes</span>
        <div style={{ display: "flex", gap: 10 }}>
          <button style={d6Btn(false)}>Give feedback</button>
          <button style={d6Btn(false)}>Edit</button>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "400px minmax(0,1fr)", gap: 26, alignItems: "start" }}>
        <div style={{ height: 280, borderRadius: 14, overflow: "hidden", background: "#e8dcc8", position: "relative" }}>
          <img src={R.img} alt={R.name} style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }}
               onError={(e) => { e.target.style.display = "none"; }} />
        </div>
        <div>
          <span style={{ fontFamily: "'Schibsted Grotesk', sans-serif", fontWeight: 700, fontSize: 30, letterSpacing: "-0.015em" }}>{R.name}</span>
          <div style={{ fontSize: 13, color: D6.muted, marginTop: 6 }}>{R.source}</div>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 14 }}>
            {R.chips.map((c, i) => (
              <span key={i} style={c === "User verified"
                ? { fontSize: 12, fontWeight: 600, padding: "5px 12px", borderRadius: 999, background: "#eef0e2", color: "#41502a" }
                : { fontSize: 12, padding: "5px 12px", borderRadius: 999, border: `1px solid ${D6.lineHi}`, color: D6.ink }}>{c}</span>
            ))}
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 14, marginTop: 22 }}>
            {R.ratings.map((r, i) => (
              <div key={i}>
                <D6Label>{r.label}</D6Label>
                <div style={{ margin: "7px 0 8px" }}><D6Num size={26}>{r.val}</D6Num></div>
                <D6Segments pct={r.val / 100} color={D6.olive} width={110} />
              </div>
            ))}
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 20, flexWrap: "wrap" }}>
            {R.nutrition.map((n, i) => (
              <span key={i} style={{ fontSize: 12.5, padding: "5px 13px", borderRadius: 999, background: D6.card, border: `1px solid ${D6.line}`, color: D6.muted }}>{n}</span>
            ))}
            <span style={{ fontSize: 12.5, padding: "5px 13px", color: D6.muted }}>per serving</span>
          </div>
        </div>
      </div>

      <div style={{ marginTop: 24, background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "16px 22px", display: "flex", alignItems: "center", gap: 16 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ width: 8, height: 8, borderRadius: "50%", background: D6.terra }}></span>
            <D6Label color={D6.terraDark}>Suggested change · from your feedback</D6Label>
          </div>
          <div style={{ marginTop: 7 }}><D6Serif size={20}>{R.pending.title}</D6Serif></div>
          <div style={{ fontSize: 12.5, color: D6.muted, marginTop: 4 }}>{R.pending.sub}</div>
          <div style={{ fontSize: 13.5, marginTop: 6 }}>
            <span style={{ textDecoration: "line-through", color: D6.muted }}>{R.pending.from}</span>
            <span style={{ color: D6.lineHi }}> → </span>
            <span style={{ fontWeight: 600 }}>{R.pending.to}</span>
          </div>
        </div>
        <div style={{ display: "flex", gap: 10, flexShrink: 0 }}>
          <button style={d6Btn(false)}>Reject</button>
          <button style={d6Btn(true)}>Accept</button>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1.25fr", gap: 24, marginTop: 24 }}>
        <div style={{ background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "18px 22px" }}>
          <div style={{ borderBottom: `2px solid ${D6.ink}`, paddingBottom: 7, marginBottom: 4 }}><D6Label color={D6.ink}>Ingredients</D6Label></div>
          {R.ingredients.map((it, i) => (
            <div key={i} style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 10, padding: "9px 0", borderBottom: `1px solid ${D6.line}` }}>
              <span style={{ fontSize: 14, minWidth: 0 }}>
                {it.n}
                {it.swap && <span style={{ fontSize: 11, fontWeight: 600, color: "#41502a", background: "#eef0e2", borderRadius: 999, padding: "2px 9px", marginLeft: 9 }}>{it.swap}</span>}
              </span>
              <span style={{ fontSize: 13, color: D6.muted, whiteSpace: "nowrap" }}>{it.q}</span>
            </div>
          ))}
          <div style={{ fontSize: 12.5, color: D6.muted, marginTop: 10 }}>{R.moreIngredients}</div>
        </div>
        <div style={{ background: D6.card, border: `1px solid ${D6.line}`, borderRadius: 12, padding: "18px 22px" }}>
          <div style={{ borderBottom: `2px solid ${D6.ink}`, paddingBottom: 7, marginBottom: 12 }}><D6Label color={D6.ink}>Method</D6Label></div>
          <div style={{ display: "grid", gap: 12 }}>
            {R.steps.map((s, i) => (
              <div key={i} style={{ display: "flex", gap: 12, fontSize: 14, lineHeight: 1.5 }}>
                <D6Num size={14} color={D6.terra}>{String(i + 1).padStart(2, "0")}</D6Num>
                <span style={{ minWidth: 0 }}>{s}</span>
              </div>
            ))}
          </div>
          <div style={{ fontSize: 12.5, color: D6.muted, marginTop: 12 }}>{R.moreSteps}</div>
        </div>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 22, fontSize: 12.5 }}>
        <D6Label>Versions</D6Label>
        {R.versions.map((v, i) => (
          <span key={i} style={i === 0
            ? { fontWeight: 700, color: "#41502a", background: "#eef0e2", borderRadius: 999, padding: "3px 11px", fontSize: 12 }
            : { color: D6.muted }}>{v}</span>
        ))}
        <span style={{ color: "#185FA5" }}>View diff</span>
      </div>
    </div>
  );
}

Object.assign(window, { D6Gen, D6Feedback, D6Grocery, D6Recipe });
