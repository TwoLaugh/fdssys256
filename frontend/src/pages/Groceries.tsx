import { AdvisorPanel } from "../components/AdvisorPanel";
import { OrderTimeline } from "../components/OrderTimeline";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import { SwapLine } from "../components/SwapLine";
import { TintChip } from "../components/TintChip";
import {
  advanceOrder,
  cancelOrder,
  markBought,
  resolveSubstitution,
  useStore,
} from "../mock/store";
import type { GroceryItem } from "../mock/types";

function BoughtBox({
  item,
  onToggle,
}: {
  item: GroceryItem;
  onToggle: () => void;
}) {
  const bought = item.state === "bought";
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={bought}
      aria-label={`${item.n} bought`}
      className={`bought-box${bought ? " bought" : ""}`}
      onClick={onToggle}
    >
      {bought ? "✓" : ""}
    </button>
  );
}

export function Groceries() {
  const grocery = useStore((s) => s.grocery);

  const allItems = grocery.groups.flatMap((g) => g.items);
  const boughtCount = allItems.filter((it) => it.state === "bought").length;
  const staleCount = allItems.filter((it) => it.stale).length;

  return (
    <div>
      <PageHeader
        title="Groceries"
        meta={grocery.contextLine}
        actions={
          <>
            <button className="btn" disabled title="Coming with live wiring">
              Export
            </button>
            <button className="btn" disabled title="Coming with live wiring">
              Recalculate
            </button>
          </>
        }
      />

      <div style={{ marginTop: 24 }}>
        <StatStrip
          numeralSize={22}
          cells={[
            {
              label: "Projected total",
              value: grocery.projectedTotal,
              sub: grocery.projectedConf,
            },
            {
              label: "Items bought",
              value: `${boughtCount} of ${allItems.length}`,
            },
            {
              label: "Stale prices",
              value: String(staleCount),
              sub: "not updated in 2 weeks",
              warn: staleCount > 0,
            },
            {
              label: "Budget headroom",
              value: grocery.headroom,
              sub: grocery.headroomSub,
            },
          ]}
        />
      </div>

      <div className="grocery-layout">
        <div>
          {grocery.groups.map((group, gi) => (
            <div key={group.name} style={{ marginBottom: 22 }}>
              <div className="group-head">
                <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
                  {group.name}
                </span>
              </div>
              {group.items.map((item, ii) => {
                const bought = item.state === "bought";
                return (
                  <div key={item.n} className="grocery-row">
                    <BoughtBox
                      item={item}
                      onToggle={() => markBought(gi, ii)}
                    />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <span
                        className={`grocery-name${bought ? " bought" : ""}`}
                      >
                        {item.n}
                      </span>
                      {item.note && (
                        <span style={{ marginLeft: 9 }}>
                          <TintChip tone="terra">{item.note}</TintChip>
                        </span>
                      )}
                    </div>
                    <span className="grocery-qty">{item.q}</span>
                    <span className="grocery-price">{item.price}</span>
                    <span className="grocery-stale">
                      {item.stale && <span className="stale-tag">STALE</span>}
                    </span>
                  </div>
                );
              })}
            </div>
          ))}
          <div className="grocery-footnote">
            Prices marked stale haven't been observed in 2+ weeks — projections
            regress them toward neutral.
          </div>
        </div>

        <div style={{ display: "grid", gap: 18, alignContent: "start" }}>
          <div className="mp-card order-card">
            <div className="order-card-head">
              <span className="mp-label">Active order</span>
              {grocery.order && (
                <span className="mp-chip">{grocery.order.state}</span>
              )}
            </div>
            {grocery.order ? (
              <>
                <div className="order-provider">{grocery.order.provider}</div>
                <div className="order-eta">{grocery.order.eta}</div>
                <div style={{ marginTop: 16 }}>
                  <OrderTimeline
                    steps={grocery.order.steps}
                    at={grocery.order.at}
                  />
                </div>
                <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
                  <button className="btn btn-small" onClick={advanceOrder}>
                    Refresh status
                  </button>
                  <button className="btn btn-small" onClick={cancelOrder}>
                    Cancel order
                  </button>
                </div>
              </>
            ) : (
              <div className="order-empty">
                No active order — place one from the next quote.
              </div>
            )}
          </div>

          {grocery.substitution && (
            <AdvisorPanel
              small
              label="Substitution to resolve"
              title={`${grocery.substitution.reason} — swap suggested`}
              titleSize={18}
              acceptLabel="Accept swap"
              dismissLabel="Reject"
              onAccept={() => resolveSubstitution(true)}
              onDismiss={() => resolveSubstitution(false)}
            >
              <div style={{ marginTop: 10 }}>
                <SwapLine
                  from={grocery.substitution.from}
                  to={grocery.substitution.to}
                  delta={grocery.substitution.delta}
                />
              </div>
            </AdvisorPanel>
          )}
        </div>
      </div>
    </div>
  );
}
