import { useStore } from "../mock/store";

/**
 * Bottom-centre toast stack. Info = confirmations / replay notices;
 * warn = 409/422-style guard messages (amber — time-sensitive, never red:
 * red is reserved for danger per the design language).
 */
export function ToastHost() {
  const toasts = useStore((s) => s.toasts);
  if (toasts.length === 0) return null;
  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast${t.tone === "warn" ? " warn" : ""}`}>
          {t.text}
        </div>
      ))}
    </div>
  );
}
