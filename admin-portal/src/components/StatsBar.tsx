import { useState, type ReactNode } from "react";
import Stats from "./Stats";
import { describeRange, todayIso } from "../utils/dates";

export interface StatItem {
  label: string;
  value: number | string;
  tone?: "ok" | "warn" | "bad" | "neutral";
}

/**
 * Summary tiles on demand, shown in a popup behind a single "Stats" button.
 * Dated data gets From / To pickers inside the popup, prefilled with today, so
 * today's numbers show first and any range is one change away. Returns the
 * toolbar control and the popup element (null while closed).
 */
export function useStatsBar(props: {
  compute: (range: { from: string; to: string } | null) => StatItem[];
  /** Offers the date range (for data with a timestamp). */
  dated?: boolean;
  /** Popup title. */
  title?: string;
}): { controls: ReactNode; panel: ReactNode } {
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState(todayIso());
  const [to, setTo] = useState(todayIso());

  function show() {
    if (props.dated) {
      setFrom(todayIso());
      setTo(todayIso());
    }
    setOpen(true);
  }

  const controls = (
    <span className="stats-controls">
      <button className="action" onClick={show}>Stats</button>
    </span>
  );

  const range = props.dated ? { from, to } : null;
  const caption = props.dated
    ? (from === todayIso() && to === todayIso() ? `Today, ${describeRange(from, to)}` : describeRange(from, to))
    : "All data";

  const panel = !open ? null : (
    <div className="overlay" onClick={() => setOpen(false)}>
      <div className="modal stats-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>{props.title ?? "Stats"}</h3>
        {props.dated && (
          <div className="stats-range">
            <label>
              From
              <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
            </label>
            <label>
              To
              <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
            </label>
            <button
              className="link"
              style={{ alignSelf: "flex-end", marginBottom: 6 }}
              onClick={() => { setFrom(todayIso()); setTo(todayIso()); }}
            >
              Today
            </button>
            <button
              className="link"
              style={{ alignSelf: "flex-end", marginBottom: 6 }}
              onClick={() => { setFrom(""); setTo(""); }}
            >
              All dates
            </button>
          </div>
        )}
        <Stats items={props.compute(range)} />
        <p className="stats-caption">{caption}</p>
        <div className="row">
          <button className="action" onClick={() => setOpen(false)}>Close</button>
        </div>
      </div>
    </div>
  );

  return { controls, panel };
}
