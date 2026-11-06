import { useEffect, useState } from "react";
import { api } from "../api";

/**
 * The daily check window: each agent opens its one random check inside these
 * hours, in the PC's local time. Saving reaches running agents within a minute —
 * a day whose check has not fired yet is re-planned inside the new window; a day
 * already checked stays checked.
 */
export default function CheckWindowDialog(props: {
  onClose: () => void;
  /** Saved: the parent shows the message and closes the dialog. */
  onSaved: (message: string) => void;
}) {
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .getCheckWindow()
      .then((w) => {
        // The server may say "12:00" or "12:00:00"; the time input wants HH:mm.
        setStart(w.workWindowStart.slice(0, 5));
        setEnd(w.workWindowEnd.slice(0, 5));
        setLoading(false);
      })
      .catch((e) => {
        setError((e as Error).message);
        setLoading(false);
      });
  }, []);

  async function save() {
    if (!start || !end) {
      setError("Both times are required.");
      return;
    }
    if (end <= start) {
      setError("The window must end after it starts.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const saved = await api.updateCheckWindow(start, end);
      props.onSaved(
        `Check window set to ${saved.workWindowStart.slice(0, 5)}–${saved.workWindowEnd.slice(0, 5)}. ` +
          "Running agents pick it up within a minute.",
      );
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>Daily check window</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          Each day's check opens at a random time inside these hours (the employee's local time), so
          nobody can plan around it. A day whose check has not run yet is re-planned inside the new
          window; a day already checked stays checked.
        </p>

        {loading ? (
          <p className="muted">Loading…</p>
        ) : (
          <div className="row" style={{ justifyContent: "flex-start", gap: 12 }}>
            <label>
              From
              <input type="time" value={start} disabled={busy} onChange={(e) => setStart(e.target.value)} />
            </label>
            <label>
              To
              <input type="time" value={end} disabled={busy} onChange={(e) => setEnd(e.target.value)} />
            </label>
          </div>
        )}

        {error && <p className="error-text">{error}</p>}

        <div className="row">
          <button className="action" disabled={busy} onClick={props.onClose}>Cancel</button>
          <button className="primary" disabled={busy || loading} onClick={() => void save()}>
            {busy ? "Saving…" : "Save window"}
          </button>
        </div>
      </div>
    </div>
  );
}
