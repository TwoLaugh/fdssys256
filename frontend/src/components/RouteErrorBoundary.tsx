import { Component, type ReactNode } from "react";

/**
 * Catches render errors from the routed page so one broken screen can't unmount
 * the whole app (rail + nav included). Keyed by pathname in Shell so navigating
 * away resets it. Without this, a throw in any page kills the entire React tree
 * and every subsequent click is dead until a manual reload.
 */
export class RouteErrorBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: "2rem", maxWidth: 640 }}>
          <p className="mp-accent" style={{ fontWeight: 600, marginBottom: 8 }}>
            This screen hit an error
          </p>
          <h1 className="mp-serif" style={{ fontSize: "1.6rem", marginBottom: 12 }}>
            Something went wrong rendering this page
          </h1>
          <p style={{ color: "var(--mp-muted, #6b6459)", marginBottom: 16 }}>
            The rest of the app is fine — use the menu on the left to go elsewhere.
          </p>
          <pre
            style={{
              fontSize: 12,
              whiteSpace: "pre-wrap",
              color: "var(--mp-muted, #6b6459)",
            }}
          >
            {this.state.error.message}
          </pre>
        </div>
      );
    }
    return this.props.children;
  }
}
