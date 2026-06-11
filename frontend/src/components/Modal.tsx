import { useEffect } from "react";
import type { ReactNode } from "react";

/**
 * Faux-modal: an in-flow overlay div (D6 card on dim). Closes on Esc and on
 * clicking the dim backdrop; the card itself swallows clicks.
 */
export function Modal({
  label,
  onClose,
  wide = false,
  children,
}: {
  label: string;
  onClose: () => void;
  /** 720px card for the feedback-routing layout (default 440px). */
  wide?: boolean;
  children: ReactNode;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div
        className={`modal-card mp-card${wide ? " wide" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}
