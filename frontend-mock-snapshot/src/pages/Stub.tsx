/** Placeholder page used by every not-yet-built route so the rail fully navigates. */
export function Stub({ title, route }: { title: string; route: string }) {
  return (
    <div className="stub-page">
      <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
        {route}
      </span>
      <h1 className="stub-title">{title}</h1>
      <p className="stub-body">
        Coming soon — this page is scaffolded so navigation works end to end.
      </p>
    </div>
  );
}
