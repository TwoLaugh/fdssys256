/**
 * Order timeline primitive (design-language §primitives): five dots +
 * connecting rules, filled olive up to the current state.
 */
export function OrderTimeline({ steps, at }: { steps: string[]; at: number }) {
  return (
    <div>
      <div className="order-dots">
        {steps.map((step, i) => (
          <div
            key={step}
            className="order-dot-seg"
            style={{ flex: i < steps.length - 1 ? 1 : "none" }}
          >
            <span className={`order-dot${i <= at ? " done" : ""}`} />
            {i < steps.length - 1 && (
              <span className={`order-rule${i < at ? " done" : ""}`} />
            )}
          </div>
        ))}
      </div>
      <div className="order-step-labels">
        {steps.map((step, i) => (
          <span key={step} className={i === at ? "current" : undefined}>
            {step}
          </span>
        ))}
      </div>
    </div>
  );
}
