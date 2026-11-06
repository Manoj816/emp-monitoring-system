import { useEffect, useState } from "react";

/**
 * In-app replacements for window.confirm / window.alert. The browser popups
 * announce the host ("localhost:5176 says"), freeze the whole tab and cannot be
 * styled; these render as ordinary portal modals instead. One <ConfirmHost /> is
 * mounted by App; appConfirm / appAlert queue questions onto it from anywhere
 * and resolve with the person's answer. If the host is somehow not mounted,
 * they fall back to the native popups rather than losing the question.
 */

type Pending = {
  kind: "confirm" | "alert";
  message: string;
  title: string;
  confirmLabel: string;
  danger: boolean;
  resolve: (ok: boolean) => void;
};

let enqueue: ((p: Pending) => void) | null = null;

export function appConfirm(
  message: string,
  opts?: { title?: string; confirmLabel?: string; danger?: boolean },
): Promise<boolean> {
  return new Promise((resolve) => {
    if (!enqueue) {
      resolve(window.confirm(message));
      return;
    }
    enqueue({
      kind: "confirm",
      message,
      title: opts?.title ?? "Please confirm",
      confirmLabel: opts?.confirmLabel ?? "Confirm",
      danger: opts?.danger ?? false,
      resolve,
    });
  });
}

export function appAlert(message: string, title = "Done"): Promise<void> {
  return new Promise<void>((resolve) => {
    if (!enqueue) {
      window.alert(message);
      resolve();
      return;
    }
    enqueue({ kind: "alert", message, title, confirmLabel: "OK", danger: false, resolve: () => resolve() });
  });
}

export default function ConfirmHost() {
  const [queue, setQueue] = useState<Pending[]>([]);

  useEffect(() => {
    enqueue = (p) => setQueue((q) => [...q, p]);
    return () => {
      enqueue = null;
    };
  }, []);

  const current = queue.length > 0 ? queue[0] : null;

  function answer(ok: boolean) {
    if (!current) return;
    current.resolve(ok);
    setQueue((q) => q.slice(1));
  }

  useEffect(() => {
    if (!current) return;
    const onKey = (e: KeyboardEvent) => {
      // Escape always declines; Enter only answers an alert — an accidental
      // Enter must never confirm something destructive.
      if (e.key === "Escape") {
        e.stopPropagation();
        current.resolve(false);
        setQueue((q) => q.slice(1));
      } else if (e.key === "Enter" && current.kind === "alert") {
        e.stopPropagation();
        current.resolve(true);
        setQueue((q) => q.slice(1));
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [current]);

  if (!current) return null;

  return (
    <div className="overlay confirm-overlay" onClick={() => answer(false)}>
      <div
        className="modal"
        onClick={(e) => e.stopPropagation()}
        role={current.kind === "confirm" ? "alertdialog" : "dialog"}
        aria-modal="true"
      >
        <button type="button" className="modal-close" aria-label="Close" title="Close" onClick={() => answer(false)}>
          ×
        </button>
        <h3 style={{ marginTop: 0 }}>{current.title}</h3>
        <p style={{ whiteSpace: "pre-wrap" }}>{current.message}</p>
        <div className="row">
          {current.kind === "confirm" && (
            <button className="action" autoFocus onClick={() => answer(false)}>
              Cancel
            </button>
          )}
          <button
            className={current.danger ? "action danger" : "primary"}
            autoFocus={current.kind === "alert"}
            onClick={() => answer(true)}
          >
            {current.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
