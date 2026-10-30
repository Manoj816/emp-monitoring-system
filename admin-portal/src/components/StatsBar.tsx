import { useState, type ReactNode } from "react";
import Stats from "./Stats";
import { describeRange, todayIso } from "../utils/dates";

export interface StatItem {
  label: string;
  value: number | string;
  tone?: "ok" | "warn" | "bad" | "neutral";
}

/**
 * Summary tiles on demand, shown in a popup. Returns the toolbar controls and
 * the popup element (null while closed). Dated data offers "Today's stats" and
 * "Stats for dates…" (From / To inside the popup); other data a single "Stats"
 * button for the whole set. The caller computes the tiles for the requested range.
 */
export function useStatsBar(props: {
  compute: (range: { from: string; to: string } | null) => StatItem[];
  /** Offers Today and a date range (for data with a timestamp). */
  dated?: boolean;
  /** Popup title for the whole-data-set stats. */
  title?: string;
}): { controls: ReactNode; panel: ReactNode } {
  const [mode, setMode] = useState<"hidden" | "all" | "today" | "range">("hidden");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const range =
    mode === "today" ? { from: todayIso(), to: todayIso() } : mode === "range" ? { from, to } : null;
  const title =
    mode === "today" ? "Today's stats" : mode === "range" ? "Stats for dates" : (props.title ?? "Stats");
  const caption =
    mode === "today" ? `Today, ${describeRange(todayIso(), todayIso())}`
      : mode === "range" ? describeRange(from, to)
        : "All data";

  const controls = (
    <span className="stats-controls">
      {props.dated ? (
        <>
          <button className="action" onClick={() => setMode("today")}>Today's stats</button>
          <button className="action" onClick={() => setMode("range")}>Stats for dates…</button>
        </>
      ) : (
        <button className="action" onClick={() => setMode("all")}>Stats</button>
      )}
    </span>
  );

  const close = () => setMode("hidden");

  const panel =
    mode === "hidden" ? null : (
      <div className="overlay" onClick={close}>
        <div className="modal stats-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
          <h3 style={{ marginTop: 0 }}>{title}</h3>
          {mode === "range" && (
            <div className="stats-range">
              <label>
                From
                <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
              </label>
              <label>
                To
                <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
              </label>
            </div>
          )}
          <Stats items={props.compute(range)} />
          <p className="stats-caption">{caption}</p>
          <div className="row">
            <button className="action" onClick={close}>Close</button>
          </div>
        </div>
      </div>
    );

  return { controls, panel };
}
